"""#474: ChainHierarchyClient's own call overhead, against #473's seven
constructed graphs. No real Chain is reachable from this environment, so
this measures the httpx code path (request construction, response parsing)
against an in-process httpx.MockTransport serving the same graph shape
Chain's real /ancestors and /children would return -- not real network
round-trip time to a live Chain. See scoring/README.md's "Latency" for why
that number still has to be measured separately, once Chain is reachable.

Run: python -m benchmarks.hierarchy_propagation_latency
"""

import json
import statistics
import time
from pathlib import Path
from typing import Any

import httpx
from tests.propagation_fixtures import load_all_propagation_fixtures

from mind.scoring.chain_hierarchy_client import ChainHierarchyClient, ChainHierarchySettings
from mind.scoring.hierarchy import HierarchyFixture

_RESULTS_PATH = Path(__file__).resolve().parent / "hierarchy_propagation_latency_results.json"


def _handler_for(graph: HierarchyFixture):
    by_id = graph.by_id()

    def handler(request: httpx.Request) -> httpx.Response:
        _, task_id, relation = request.url.path.strip("/").split("/")
        if relation == "ancestors":
            chain: list[dict[str, str]] = []
            seen = {task_id}
            node = by_id.get(task_id)
            current_id = node.parent_task_id if node else None
            while current_id is not None:
                if current_id in seen:  # Chain's own silent-truncate-on-cycle behavior
                    break
                seen.add(current_id)
                chain.append({"id": current_id})
                node = by_id.get(current_id)
                current_id = node.parent_task_id if node else None
            return httpx.Response(200, json=chain)
        if relation == "children":
            kids = [{"id": n.task_id} for n in by_id.values() if n.parent_task_id == task_id]
            return httpx.Response(200, json=kids)
        return httpx.Response(404)

    return handler


def _timed_ms(fn, *args: object) -> float:
    start = time.perf_counter()
    fn(*args)
    return (time.perf_counter() - start) * 1000


def main() -> None:
    fixtures = load_all_propagation_fixtures()

    print(f"{'fixture':<28} {'calls':>6} {'mean_ms':>9} {'p95_ms':>9} {'max_ms':>9}")
    per_fixture: dict[str, Any] = {}
    all_durations: list[float] = []
    for fixture in fixtures:
        by_id = fixture.graph.by_id()
        transport = httpx.MockTransport(_handler_for(fixture.graph))
        http_client = httpx.Client(transport=transport, base_url="http://chain.test")
        client = ChainHierarchyClient(ChainHierarchySettings(base_url="http://chain.test"), http_client)

        durations_ms = [
            d
            for node_id in by_id
            for d in (_timed_ms(client.get_ancestors, node_id), _timed_ms(client.get_direct_children, node_id))
        ]

        all_durations.extend(durations_ms)
        mean_ms = statistics.mean(durations_ms)
        p95_ms = (
            statistics.quantiles(durations_ms, n=20)[18] if len(durations_ms) >= 2 else durations_ms[0]
        )
        max_ms = max(durations_ms)
        per_fixture[fixture.name] = {
            "calls": len(durations_ms),
            "mean_ms": mean_ms,
            "p95_ms": p95_ms,
            "max_ms": max_ms,
        }
        print(f"{fixture.name:<28} {len(durations_ms):>6} {mean_ms:>9.3f} {p95_ms:>9.3f} {max_ms:>9.3f}")

    overall = {
        "calls": len(all_durations),
        "mean_ms": statistics.mean(all_durations),
        "p95_ms": statistics.quantiles(all_durations, n=20)[18],
        "max_ms": max(all_durations),
    }
    print(f"\nOverall: {overall['calls']} calls across all {len(fixtures)} fixtures")
    print(f"  mean: {overall['mean_ms']:.3f}ms  p95: {overall['p95_ms']:.3f}ms  max: {overall['max_ms']:.3f}ms")

    _RESULTS_PATH.write_text(
        json.dumps({"per_fixture": per_fixture, "overall": overall}, indent=2)
    )
    print(f"\nWrote {_RESULTS_PATH.name} (local reference, gitignored)")


if __name__ == "__main__":
    main()
