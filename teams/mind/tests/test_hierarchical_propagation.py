"""#474: hierarchical value propagation.

Three things are checked, deliberately apart:

- The formula itself (0.93*own + 0.07*mean(ancestors' raw values), cycle ->
  raise), against #473's seven constructed graphs, via `FixtureAncestorSource`.
  Averaging rather than summing happens not to produce any tie here (unlike
  the old additive design, nothing clamps to a hard ceiling), so every
  fixture -- including "branch" -- gets the same strict, adjacent-pair
  check. No fixture exercises `ChainAncestorSource` or the rescore cascade:
  Chain never hands Mind a cycle, and none of the seven graphs are about Chain.
- `ChainHierarchyClient`'s own fallback behaviour -- every failure (timeout,
  unreachable, non-200, malformed body) degrades to []/None and never
  raises, over a mocked transport, exactly as `tests/extraction/test_deepseek.py`
  mocks its own provider's HTTP calls.
- `HierarchicalRankingModel`'s own remaining responsibility no fixture
  touches: that a Chain outage (ancestor_values always []) still produces a
  score exactly equal to the baseline's own (no ancestors means no
  averaging, not a special-cased normalization). The model is pure -- no
  cascade, no side effects; `ScorePublisher` owns that (test_score_publish.py).
"""

from __future__ import annotations

import http.server
import threading
import time
from datetime import UTC, datetime
from unittest.mock import Mock

import httpx
import pytest

from mind.evaluation.interfaces import ScoreContext, ScoreFactors, ScoreResult
from mind.scoring.ancestor_source import ChainAncestorSource, CycleError, FixtureAncestorSource
from mind.scoring.chain_hierarchy_client import ChainHierarchyClient, ChainHierarchySettings
from mind.scoring.hierarchical_model import HierarchicalRankingModel
from mind.scoring.segmented_model import SegmentedRankingModel
from tests.propagation_fixtures import load_all_propagation_fixtures

CONTEXT = ScoreContext(current_datetime=datetime(2026, 1, 1, tzinfo=UTC))

# This file's own fixture-leaf stand-in: `importance` is what the fixtures'
# hand-verified arithmetic calls a task's "own" score, in place of whatever
# SegmentedRankingModel would have derived from a real title/tier/deadline.
# Propagation picks up after that derivation, not before it.


class _FixtureTask:
    def __init__(self, task_id: str, parent_task_id: str | None, importance: float) -> None:
        self.task_id = task_id
        self.parent_task_id = parent_task_id
        self.importance = importance
        self.title = "fixture task"
        self.description: str | None = None
        self.deadline: datetime | None = None
        self.effort_estimate: int | None = None
        self.tier = None
        self.blocked = False


class _ImportanceEchoBaseline:
    """A leaf's own base_score is exactly its fixture-given `importance`."""

    def score(self, task: _FixtureTask, context: ScoreContext) -> ScoreResult:
        return ScoreResult(
            score=task.importance,
            factors=ScoreFactors(
                contributions={"own_importance": task.importance},
                blocked=task.blocked,
                base_score=task.importance,
            ),
        )


FIXTURES = load_all_propagation_fixtures()
_CYCLE_FIXTURES = [f for f in FIXTURES if f.expected_error is not None]
_STRICT_FIXTURES = [f for f in FIXTURES if f.expected_error is None]


def _score_leaves(fixture) -> dict[str, float]:
    model = HierarchicalRankingModel(
        FixtureAncestorSource(fixture.graph), baseline=_ImportanceEchoBaseline()
    )
    by_id = fixture.graph.by_id()
    scores = {}
    for leaf_id in fixture.leaf_ids():
        leaf = by_id[leaf_id]
        task = _FixtureTask(leaf.task_id, leaf.parent_task_id, leaf.importance)
        result = model.score(task, CONTEXT)
        assert result.factors is not None
        scores[leaf_id] = result.factors.base_score
    return scores


@pytest.mark.parametrize("fixture", _STRICT_FIXTURES, ids=lambda f: f.name)
def test_propagation_reproduces_the_fixtures_strict_order(fixture) -> None:
    scores = _score_leaves(fixture)
    for higher, lower in zip(fixture.expected_order, fixture.expected_order[1:]):
        assert scores[higher] > scores[lower], (
            f"{higher} ({scores[higher]}) must outrank {lower} ({scores[lower]})"
        )


@pytest.mark.parametrize("fixture", _CYCLE_FIXTURES, ids=lambda f: f.name)
def test_a_cyclic_parent_chain_raises_instead_of_looping(fixture) -> None:
    model = HierarchicalRankingModel(
        FixtureAncestorSource(fixture.graph), baseline=_ImportanceEchoBaseline()
    )
    by_id = fixture.graph.by_id()
    [leaf_id] = fixture.leaf_ids()
    leaf = by_id[leaf_id]
    task = _FixtureTask(leaf.task_id, leaf.parent_task_id, leaf.importance)
    with pytest.raises(CycleError):
        model.score(task, CONTEXT)


def test_an_unimportant_ancestor_can_pull_a_tasks_score_below_its_own_baseline() -> None:
    """Averaging, not summing: unlike the old additive boost (always >= 0),
    a task's own high baseline can be dragged down by an unimportant
    ancestor -- #473's 02_branch fixture's task_low_1 is exactly this, and
    still ranks correctly below task_high_1/2 once averaged (see the
    README's "A parent can only add, never remove" design note, which this
    formula deliberately does not satisfy, by explicit decision)."""

    fixture = next(f for f in FIXTURES if f.name == "branch")
    model = HierarchicalRankingModel(
        FixtureAncestorSource(fixture.graph), baseline=_ImportanceEchoBaseline()
    )
    by_id = fixture.graph.by_id()
    leaf = by_id["task_low_1"]  # own importance 0.8, under goal_low's 0.15
    task = _FixtureTask(leaf.task_id, leaf.parent_task_id, leaf.importance)

    result = model.score(task, CONTEXT)

    assert result.factors is not None and result.factors.base_score is not None
    assert result.factors.base_score < leaf.importance


# --- ChainHierarchyClient: every failure degrades, never raises -------------


def _client(handler) -> ChainHierarchyClient:
    transport = httpx.MockTransport(handler)
    http_client = httpx.Client(transport=transport, base_url="http://chain.test")
    return ChainHierarchyClient(ChainHierarchySettings(base_url="http://chain.test"), http_client)


def test_get_ancestors_reads_every_id_nearest_first() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/tasks/leaf-1/ancestors"
        return httpx.Response(200, json=[{"id": "parent-1"}, {"id": "grandparent-1"}])

    assert _client(handler).get_ancestors("leaf-1") == ["parent-1", "grandparent-1"]


def test_get_ancestors_is_empty_for_a_root() -> None:
    assert _client(lambda r: httpx.Response(200, json=[])).get_ancestors("root-1") == []


def test_get_direct_children_reads_every_id() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/tasks/parent-1/children"
        return httpx.Response(200, json=[{"id": "child-1"}, {"id": "child-2"}])

    assert _client(handler).get_direct_children("parent-1") == ["child-1", "child-2"]


def test_a_timeout_degrades_to_no_ancestors() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("Chain is slow")

    assert _client(handler).get_ancestors("leaf-1") == []


def test_a_genuinely_slow_chain_fails_fast_not_at_the_old_2s_default() -> None:
    """#474 round-9 review: httpx.MockTransport never actually enforces the
    client's configured timeout (it runs the handler to completion, however
    long that takes) -- only a real socket does. A real local server that
    sleeps past the timeout is the only way to prove the client gives up
    close to DEFAULT_TIMEOUT_SECONDS, not the old 2s (let alone the ~4s two
    such calls used to cost under the lock before it moved outside)."""

    class SlowHandler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            time.sleep(1.0)  # well past DEFAULT_TIMEOUT_SECONDS (0.2s)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b"[]")

        def log_message(self, format: str, *args: object) -> None:
            pass  # quiet: this is expected to "fail", not a real error

    server = http.server.HTTPServer(("127.0.0.1", 0), SlowHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        base_url = f"http://127.0.0.1:{server.server_port}"
        settings = ChainHierarchySettings(base_url=base_url)
        # trust_env=False: a real socket, unlike every other test here, so
        # it would otherwise pick up a dev machine's own SSL_CERT_FILE/proxy
        # env vars -- irrelevant to a plain local http:// connection.
        http_client = httpx.Client(
            base_url=base_url, timeout=httpx.Timeout(settings.timeout_seconds), trust_env=False
        )
        client = ChainHierarchyClient(settings, http_client)
        try:
            start = time.perf_counter()
            result = client.get_ancestors("leaf-1")
            elapsed = time.perf_counter() - start
        finally:
            http_client.close()  # client.close() is a no-op: it doesn't own this one

        assert result == []
        # Generous upper bound for CI jitter -- still an order of magnitude
        # under the old 2s-per-call (4s-per-task-under-the-lock) behavior.
        assert elapsed < 1.0, f"took {elapsed:.3f}s -- timeout is not being enforced"
    finally:
        server.shutdown()
        thread.join(timeout=5)


def test_connection_refused_degrades_to_no_ancestors() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("Chain is down")

    assert _client(handler).get_ancestors("leaf-1") == []


def test_a_non_200_degrades_to_no_ancestors() -> None:
    assert _client(lambda r: httpx.Response(500)).get_ancestors("leaf-1") == []


def test_a_body_that_is_not_json_degrades_to_no_ancestors() -> None:
    assert _client(lambda r: httpx.Response(200, text="<html>not json</html>")).get_ancestors(
        "leaf-1"
    ) == []


def test_a_body_that_is_not_a_list_degrades_to_no_ancestors() -> None:
    assert _client(lambda r: httpx.Response(200, json={"id": "parent-1"})).get_ancestors(
        "leaf-1"
    ) == []


def test_a_node_without_an_id_is_skipped_not_raised() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=[{"not_id": "parent-1"}])

    assert _client(handler).get_ancestors("leaf-1") == []


# --- ChainAncestorSource: each ancestor's own value, never Chain's --------


def test_ancestor_values_rescores_each_ancestors_own_cached_state() -> None:
    client = Mock(spec=ChainHierarchyClient)
    client.get_ancestors.return_value = ["parent-1", "grandparent-1"]
    cached = {
        "parent-1": _FixtureTask("parent-1", None, importance=0.0),
        "grandparent-1": _FixtureTask("grandparent-1", None, importance=0.0),
    }

    def _score(task: _FixtureTask, context: ScoreContext) -> ScoreResult:
        value = 0.9 if task.task_id == "parent-1" else 0.5
        return ScoreResult(
            score=0.0,
            factors=ScoreFactors(contributions={"x": value}, blocked=False, base_score=value),
        )

    baseline = Mock()
    baseline.score.side_effect = _score
    source = ChainAncestorSource(client, baseline, lambda task_id: cached.get(task_id))
    task = _FixtureTask("leaf-1", "parent-1", importance=0.0)

    assert source.ancestor_values(task, CONTEXT) == [0.9, 0.5]


def test_ancestor_values_is_not_cached_across_calls() -> None:
    """#474 round-9 review: an earlier version cached an ancestor's own_base
    by its fields, but its urgency genuinely depends on the current time --
    a cache keyed on fields alone would freeze it between edits. Always
    re-scores, even for the same ancestor across two different descendants."""

    client = Mock(spec=ChainHierarchyClient)
    client.get_ancestors.return_value = ["parent-1"]
    parent_task = _FixtureTask("parent-1", None, importance=0.0)
    cached = {"parent-1": parent_task}
    baseline = Mock()
    baseline.score.return_value = ScoreResult(
        score=0.0, factors=ScoreFactors(contributions={"x": 0.6}, blocked=False, base_score=0.6)
    )
    source = ChainAncestorSource(client, baseline, lambda task_id: cached.get(task_id))

    source.ancestor_values(_FixtureTask("leaf-1", "parent-1", importance=0.0), CONTEXT)
    source.ancestor_values(_FixtureTask("leaf-2", "parent-1", importance=0.0), CONTEXT)

    assert baseline.score.call_count == 2


def test_ancestor_values_excludes_an_ancestor_whose_baseline_raises() -> None:
    """A bug scoring one ancestor must not blank out every task under it --
    the same treatment as an ancestor Mind hasn't scored since start-up."""

    client = Mock(spec=ChainHierarchyClient)
    client.get_ancestors.return_value = ["broken-1", "known-1"]
    cached = {
        "broken-1": _FixtureTask("broken-1", None, importance=0.0),
        "known-1": _FixtureTask("known-1", None, importance=0.0),
    }
    baseline = Mock()

    def _score(task: _FixtureTask, context: ScoreContext) -> ScoreResult:
        if task.task_id == "broken-1":
            raise ValueError("boom")
        return ScoreResult(
            score=0.0, factors=ScoreFactors(contributions={"x": 0.7}, blocked=False, base_score=0.7)
        )

    baseline.score.side_effect = _score
    source = ChainAncestorSource(client, baseline, lambda task_id: cached.get(task_id))
    task = _FixtureTask("leaf-1", "broken-1", importance=0.0)

    assert source.ancestor_values(task, CONTEXT) == [0.7]


def test_ancestor_values_is_empty_for_a_root() -> None:
    client = Mock(spec=ChainHierarchyClient)
    client.get_ancestors.return_value = []
    source = ChainAncestorSource(client, Mock(), lambda task_id: None)
    task = _FixtureTask("root-1", None, importance=0.0)

    assert source.ancestor_values(task, CONTEXT) == []


def test_ancestor_values_excludes_an_ancestor_with_no_cached_state() -> None:
    """Unknown since start-up is left out of the average, not counted as 0 --
    0 would be a real, wrong claim about that ancestor's importance."""

    client = Mock(spec=ChainHierarchyClient)
    client.get_ancestors.return_value = ["known-1", "unknown-1"]
    cached = {"known-1": _FixtureTask("known-1", None, importance=0.0)}
    baseline = Mock()
    baseline.score.return_value = ScoreResult(
        score=0.0, factors=ScoreFactors(contributions={"x": 0.7}, blocked=False, base_score=0.7)
    )
    source = ChainAncestorSource(client, baseline, lambda task_id: cached.get(task_id))
    task = _FixtureTask("leaf-1", "known-1", importance=0.0)

    assert source.ancestor_values(task, CONTEXT) == [0.7]


# --- HierarchicalRankingModel: fallback and the rescore cascade -------------


class _RealTask:
    """A task shaped for the real baseline, not the fixtures' echo stub."""

    def __init__(self, task_id: str, blocked: bool = False) -> None:
        self.task_id = task_id
        self.parent_task_id: str | None = None
        self.title = "Ship the thing"
        self.description = "Needs to go out before Friday"
        self.deadline: datetime | None = None
        self.effort_estimate: int | None = None
        self.tier = "operational"
        self.blocked = blocked


def _unreachable_model() -> HierarchicalRankingModel:
    always_down = Mock(spec=ChainHierarchyClient)
    always_down.get_ancestors.return_value = []
    source = ChainAncestorSource(always_down, Mock(), lambda task_id: None)
    return HierarchicalRankingModel(source)


def test_a_task_with_no_ancestors_gets_exactly_its_own_baseline() -> None:
    """No ancestors means no averaging at all -- base_score is exactly the
    baseline's own value, not a special-cased normalization of it."""

    task = _RealTask("task-1")
    baseline_only = SegmentedRankingModel().score(task, CONTEXT)

    result = _unreachable_model().score(task, CONTEXT)

    assert result.factors is not None and result.factors.base_score is not None
    assert baseline_only.factors is not None and baseline_only.factors.base_score is not None
    assert result.factors.base_score == pytest.approx(baseline_only.factors.base_score)
    assert result.score == pytest.approx(baseline_only.score)


def test_with_no_ancestors_a_higher_baseline_score_still_ranks_higher() -> None:
    """The baseline's own ordering survives with no hierarchy involved: an
    urgent task still outranks a non-urgent one."""

    model = _unreachable_model()
    urgent = _RealTask("urgent")
    urgent.deadline = datetime(2026, 1, 1, 2, tzinfo=UTC)  # two hours away
    quiet = _RealTask("quiet")

    assert model.score(urgent, CONTEXT).score > model.score(quiet, CONTEXT).score


def test_with_no_ancestors_a_blocked_task_still_never_outranks_an_unblocked_one() -> None:
    model = _unreachable_model()
    blocked = _RealTask("blocked", blocked=True)
    blocked.deadline = datetime(2026, 1, 1, 1, tzinfo=UTC)  # as urgent as it gets
    unblocked = _RealTask("unblocked")  # no deadline: as unurgent as it gets

    assert model.score(blocked, CONTEXT).score < model.score(unblocked, CONTEXT).score
