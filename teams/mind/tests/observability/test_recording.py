"""Recording the stages that are not a model call (src/mind/observability/recording.py) -- #482.

Chain requests and scores go through record_call() from the outside, so what
must hold is twofold: every request and score is in the ledger with the
right status, and the code under it behaves exactly as without the recording
-- a Chain failure still falls back to the baseline, and a raise still raises.

The fallback is the point of `degraded`: a score whose Chain lookup failed is
the baseline's own, the same number a root task gets, and nothing in the
payload says which one it was. The ledger does.
"""

import http.server
import socket
import threading
import time
from datetime import UTC, datetime

import httpx
import pytest

from mind.evaluation.interfaces import ScoreContext
from mind.observability.recording import (
    RecordedScorer,
    RecordingClient,
    failed_requests_in_this_thread,
    name_by_last_path_segment,
)
from mind.observability.tracking import (
    CallKind,
    CallStatus,
    ModelCallRecord,
    _reset_for_tests,
    get_recent_calls,
)
from mind.scoring.ancestor_source import ChainAncestorSource
from mind.scoring.chain_hierarchy_client import ChainHierarchyClient, ChainHierarchySettings
from mind.scoring.hierarchical_model import HierarchicalRankingModel
from mind.scoring.inbox_task import InboxTask
from mind.scoring.segmented_model import SegmentedRankingModel

CONTEXT = ScoreContext(current_datetime=datetime(2026, 10, 26, tzinfo=UTC))
CHAIN = "http://chain.test"


@pytest.fixture(autouse=True)
def _clean_call_records():
    _reset_for_tests()


def chain_client(handler) -> ChainHierarchyClient:
    """R3's Chain client over a RecordingClient, as the runner builds it, on a mock transport."""
    http_client = RecordingClient(
        call_name=name_by_last_path_segment("chain"),
        kind=CallKind.CHAIN,
        base_url=CHAIN,
        transport=httpx.MockTransport(handler),
    )
    return ChainHierarchyClient(ChainHierarchySettings(base_url=CHAIN), http_client)


def chain_down(request: httpx.Request) -> httpx.Response:
    raise httpx.ConnectError("Chain is not running", request=request)


def records(kind: CallKind) -> list[ModelCallRecord]:
    return [r for r in get_recent_calls() if r.kind == kind]


def a_task(task_id: str = "task-1") -> InboxTask:
    return InboxTask(
        task_id=task_id,
        tenant_id="00000000-0000-0000-0000-000000000001",
        title="Finish the updated cost sheet",
        description="Before the pricing proposal goes out.",
        deadline=None,
        effort_estimate=240,
        tier=None,
        parent_task_id="goal-1",
    )


# --- call names ---------------------------------------------------------------------


def test_a_request_is_named_by_its_endpoint_not_its_task_id():
    name = name_by_last_path_segment("chain")
    assert name(httpx.Request("GET", f"{CHAIN}/tasks/abc/ancestors")) == "chain-ancestors"
    assert name(httpx.Request("GET", f"{CHAIN}/tasks/xyz/children/")) == "chain-children"
    assert name(httpx.Request("GET", f"{CHAIN}/")) == "chain"


# --- RecordingClient: every request recorded, nothing else changed -------------------


def test_an_answered_request_is_recorded_as_a_success():
    client = chain_client(lambda r: httpx.Response(200, json=[{"id": "goal-1"}]))

    assert client.get_ancestors("task-1") == ["goal-1"]

    [call] = records(CallKind.CHAIN)
    assert (call.model_name, call.status, call.degraded) == (
        "chain-ancestors",
        CallStatus.SUCCESS,
        False,
    )
    assert call.duration_ms >= 0
    # Latency only: no CPU, tokens or dollars for a call to another service.
    assert (call.cpu_s, call.cost, call.prompt_tokens) == (None, None, None)


def test_chain_unreachable_is_recorded_as_an_error_and_still_falls_back():
    client = chain_client(chain_down)

    assert client.get_direct_children("task-1") == []

    [call] = records(CallKind.CHAIN)
    assert (call.model_name, call.status) == ("chain-children", CallStatus.ERROR)


def test_a_timeout_is_recorded_as_an_error_and_still_falls_back():
    def slow(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("Chain is slow", request=request)

    assert chain_client(slow).get_ancestors("task-1") == []
    [call] = records(CallKind.CHAIN)
    assert call.status == CallStatus.ERROR


def test_a_non_2xx_answer_is_an_error_but_is_returned_not_raised():
    client = RecordingClient(
        call_name=name_by_last_path_segment("chain"),
        kind=CallKind.CHAIN,
        base_url=CHAIN,
        transport=httpx.MockTransport(lambda r: httpx.Response(503)),
    )

    response = client.get("/tasks/task-1/ancestors")

    assert response.status_code == 503  # the caller decides what a 503 means
    [call] = records(CallKind.CHAIN)
    assert call.status == CallStatus.ERROR


def test_a_raise_is_recorded_and_raised_unchanged():
    client = RecordingClient(
        call_name=lambda r: "chain",
        kind=CallKind.CHAIN,
        base_url=CHAIN,
        transport=httpx.MockTransport(chain_down),
    )

    with pytest.raises(httpx.ConnectError, match="Chain is not running"):
        client.get("/tasks/task-1/ancestors")
    assert [c.status for c in records(CallKind.CHAIN)] == [CallStatus.ERROR]


def test_each_failed_request_is_counted_in_its_own_thread():
    before = failed_requests_in_this_thread()
    chain_client(chain_down).get_ancestors("task-1")
    chain_client(lambda r: httpx.Response(200, json=[])).get_ancestors("task-1")
    assert failed_requests_in_this_thread() == before + 1


# --- RecordingClient over a real socket -----------------------------------------------


def _closed_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def _real_chain_client(base_url: str, timeout_s: float) -> ChainHierarchyClient:
    settings = ChainHierarchySettings(base_url=base_url, timeout_seconds=timeout_s)
    # trust_env=False: a real socket, so a dev machine's proxy variables
    # would otherwise apply -- irrelevant to a local http:// connection.
    http_client = RecordingClient(
        call_name=name_by_last_path_segment("chain"),
        kind=CallKind.CHAIN,
        base_url=base_url,
        timeout=httpx.Timeout(timeout_s),
        trust_env=False,
    )
    return ChainHierarchyClient(settings, http_client)


def test_a_refused_connection_is_recorded_as_an_error():
    client = _real_chain_client(f"http://127.0.0.1:{_closed_port()}", 0.2)

    assert client.get_ancestors("task-1") == []
    [call] = records(CallKind.CHAIN)
    assert call.status == CallStatus.ERROR


def test_a_chain_slower_than_the_timeout_is_recorded_at_about_the_timeout():
    class SlowHandler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            time.sleep(1.0)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"[]")

        def log_message(self, format: str, *args: object) -> None:
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), SlowHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        client = _real_chain_client(f"http://127.0.0.1:{server.server_port}", 0.2)
        assert client.get_ancestors("task-1") == []
    finally:
        server.shutdown()
        server.server_close()

    [call] = records(CallKind.CHAIN)
    assert call.status == CallStatus.ERROR
    # The time the stage actually lost: the timeout, not the server's full second.
    assert 150 <= call.duration_ms < 900


# --- RecordedScorer ---------------------------------------------------------------------


class _Echo:
    def __init__(self) -> None:
        self.calls = 0

    def score(self, task: object, context: object) -> str:
        self.calls += 1
        return f"scored {task}"


def test_a_score_is_recorded_and_returned_unchanged():
    inner = _Echo()

    assert RecordedScorer(inner, "echo").score("t1", CONTEXT) == "scored t1"

    [call] = records(CallKind.SCORING)
    assert (call.model_name, call.status, call.degraded, inner.calls) == (
        "echo",
        CallStatus.SUCCESS,
        False,
        1,
    )
    assert call.cpu_s is not None  # this thread's CPU, measured


def test_a_score_that_raises_is_recorded_as_an_error_and_raised():
    class Broken:
        def score(self, task: object, context: object) -> str:
            raise RuntimeError("model broke")

    with pytest.raises(RuntimeError, match="model broke"):
        RecordedScorer(Broken(), "broken").score("t1", CONTEXT)
    [call] = records(CallKind.SCORING)
    assert call.status == CallStatus.ERROR


def _hierarchical(handler) -> tuple[RecordedScorer, SegmentedRankingModel]:
    baseline = SegmentedRankingModel()
    model = HierarchicalRankingModel(
        ChainAncestorSource(chain_client(handler), baseline, lambda task_id: None),
        baseline=baseline,
    )
    return RecordedScorer(model, "hierarchical-ranking"), baseline


def test_chain_down_gives_the_baseline_score_recorded_as_degraded():
    # The fallback #474 asks for: the score is the baseline's own, unchanged ...
    scorer, baseline = _hierarchical(chain_down)
    task = a_task()

    result = scorer.score(task, CONTEXT)

    assert result.score == baseline.score(task, CONTEXT).score
    # ... and the ledger says it was the fallback, not the full path.
    [score] = records(CallKind.SCORING)
    assert (score.status, score.degraded) == (CallStatus.SUCCESS, True)
    [chain] = records(CallKind.CHAIN)
    assert (chain.model_name, chain.status) == ("chain-ancestors", CallStatus.ERROR)


def test_a_root_task_gives_the_same_score_but_is_not_degraded():
    # Chain answered: no ancestors. Same number as the fallback above, which
    # is why the payload alone cannot tell the two apart.
    scorer, baseline = _hierarchical(lambda r: httpx.Response(200, json=[]))
    task = a_task()

    result = scorer.score(task, CONTEXT)

    assert result.score == baseline.score(task, CONTEXT).score
    [score] = records(CallKind.SCORING)
    assert (score.status, score.degraded) == (CallStatus.SUCCESS, False)


def test_a_chain_failure_in_another_thread_does_not_mark_this_score():
    other = threading.Thread(target=lambda: chain_client(chain_down).get_ancestors("other"))
    other.start()
    other.join()
    scorer, _ = _hierarchical(lambda r: httpx.Response(200, json=[]))

    scorer.score(a_task(), CONTEXT)

    [score] = records(CallKind.SCORING)
    assert score.degraded is False


def test_a_chain_failure_before_the_score_does_not_mark_it():
    # The rescore cascade asks Chain for children outside any score.
    chain_client(chain_down).get_direct_children("parent-1")
    scorer, _ = _hierarchical(lambda r: httpx.Response(200, json=[]))

    scorer.score(a_task(), CONTEXT)

    [score] = records(CallKind.SCORING)
    assert score.degraded is False
