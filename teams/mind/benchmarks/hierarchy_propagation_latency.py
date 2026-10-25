"""#474: ChainHierarchyClient's own call overhead, and HierarchicalRankingModel's
total cost over the plain baseline, against #473's seven constructed graphs.
No real Chain is reachable from this environment, so this measures the httpx
code path (request construction, response parsing) against an in-process
httpx.MockTransport serving the same graph shape Chain's real /ancestors and
/children would return -- not real network round-trip time to a live Chain.
See scoring/README.md's "Latency" for why that number still has to be
measured separately, once Chain is reachable.

Run: python -m benchmarks.hierarchy_propagation_latency
"""

import json
import statistics
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

import httpx
from tests.propagation_fixtures import PropagationFixture, load_all_propagation_fixtures

from mind.evaluation.interfaces import ScoreContext
from mind.scoring.ancestor_source import ChainAncestorSource
from mind.scoring.chain_hierarchy_client import ChainHierarchyClient, ChainHierarchySettings
from mind.scoring.hierarchical_model import HierarchicalRankingModel
from mind.scoring.hierarchy import HierarchyFixture, HierarchyNode
from mind.scoring.inbox_task import InboxTask
from mind.scoring.segmented_model import SegmentedRankingModel

_RESULTS_PATH = Path(__file__).resolve().parent / "hierarchy_propagation_latency_results.json"
_CONTEXT = ScoreContext(current_datetime=datetime(2026, 1, 1, tzinfo=UTC))
_TENANT = str(uuid4())


def _node_to_task(node: HierarchyNode) -> InboxTask:
    return InboxTask(
        task_id=node.task_id,
        tenant_id=_TENANT,
        title=node.label,
        description=None,
        deadline=None,
        effort_estimate=None,
        tier=None,
    )


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


def _p95(values: list[float]) -> float:
    return statistics.quantiles(values, n=20)[18] if len(values) >= 2 else values[0]


def _score_comparison(fixtures: list[PropagationFixture]) -> dict[str, Any]:
    """#474 round-9 review: how much does hierarchical scoring add over the
    plain baseline, end to end -- not just the raw Chain calls above, but
    `HierarchicalRankingModel.score()`'s whole cost (baseline call on the
    task itself, plus re-scoring every ancestor through the same baseline)
    against `SegmentedRankingModel.score()` on the same leaf tasks."""

    print(f"\n{'fixture':<28} {'leaves':>6} {'mean_diff_ms':>13} {'p95_diff_ms':>13}")
    per_fixture: dict[str, Any] = {}
    all_diffs: list[float] = []
    for fixture in fixtures:
        tasks_by_id = {node.task_id: _node_to_task(node) for node in fixture.graph.nodes}
        baseline = SegmentedRankingModel()
        transport = httpx.MockTransport(_handler_for(fixture.graph))
        http_client = httpx.Client(transport=transport, base_url="http://chain.test")
        client = ChainHierarchyClient(ChainHierarchySettings(base_url="http://chain.test"), http_client)
        ancestor_source = ChainAncestorSource(client, baseline, tasks_by_id.get)
        hierarchical = HierarchicalRankingModel(ancestor_source, baseline=baseline)

        diffs_ms = []
        for leaf_id in sorted(fixture.leaf_ids()):
            leaf = tasks_by_id[leaf_id]
            hierarchical_ms = _timed_ms(hierarchical.score, leaf, _CONTEXT)
            baseline_ms = _timed_ms(baseline.score, leaf, _CONTEXT)
            diffs_ms.append(hierarchical_ms - baseline_ms)

        all_diffs.extend(diffs_ms)
        mean_diff = statistics.mean(diffs_ms)
        p95_diff = _p95(diffs_ms)
        per_fixture[fixture.name] = {
            "leaves": len(diffs_ms),
            "mean_diff_ms": mean_diff,
            "p95_diff_ms": p95_diff,
        }
        print(f"{fixture.name:<28} {len(diffs_ms):>6} {mean_diff:>13.3f} {p95_diff:>13.3f}")

    overall = {
        "leaves": len(all_diffs),
        "mean_diff_ms": statistics.mean(all_diffs),
        "p95_diff_ms": _p95(all_diffs),
    }
    print(f"\nOverall: {overall['leaves']} leaves across all {len(fixtures)} fixtures")
    print(f"  mean diff: {overall['mean_diff_ms']:.3f}ms  p95 diff: {overall['p95_diff_ms']:.3f}ms")
    return {"per_fixture": per_fixture, "overall": overall}


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
        p95_ms = _p95(durations_ms)
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
        "p95_ms": _p95(all_durations),
        "max_ms": max(all_durations),
    }
    print(f"\nOverall: {overall['calls']} calls across all {len(fixtures)} fixtures")
    print(f"  mean: {overall['mean_ms']:.3f}ms  p95: {overall['p95_ms']:.3f}ms  max: {overall['max_ms']:.3f}ms")

    score_comparison = _score_comparison(fixtures)

    _RESULTS_PATH.write_text(
        json.dumps(
            {
                "raw_calls": {"per_fixture": per_fixture, "overall": overall},
                "score_comparison": score_comparison,
            },
            indent=2,
        )
    )
    print(f"\nWrote {_RESULTS_PATH.name} (local reference, gitignored)")


if __name__ == "__main__":
    main()
