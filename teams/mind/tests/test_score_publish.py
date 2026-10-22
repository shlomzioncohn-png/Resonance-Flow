"""#357: task.score.proposed is published at creation, re-published when a
field the score depends on changes, and not re-published otherwise.
No broker: the sink is an in-memory list."""

import threading
import time
from datetime import UTC, datetime
from uuid import UUID

import pytest
from google.protobuf.timestamp_pb2 import Timestamp

import mind.scoring.score_publisher as score_publisher_module
from mind.contracts.task_enums import TaskTier as ContractTier
from mind.contracts.task_score_proposed import TaskScoreProposed
from mind.evaluation.interfaces import ScoreContext, ScoreFactors, ScoreResult
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent, TaskTier
from mind.messaging.task_updated_event_pb2 import TaskUpdatedEvent
from mind.scoring.inbox_task import to_scoring_task
from mind.scoring.score_publisher import ScorePublisher
from mind.scoring.segmented_model import SegmentedRankingModel

TASK_A = "3f2c1a9e-7b4d-4e8a-9c1f-0a2b3c4d5e6f"
TASK_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
TENANT = "7d6c5b4a-3e2f-4d1c-8b0a-9f8e7d6c5b4a"
NOW = datetime(2026, 10, 13, 9, 0, tzinfo=UTC)
DEADLINE = datetime(2026, 10, 20, 9, 0, tzinfo=UTC)
# When Inbox wrote each state of the task: created first, updated after.
CREATED_AT = datetime(2026, 10, 13, 8, 0, tzinfo=UTC)
UPDATED_AT = datetime(2026, 10, 13, 8, 5, tzinfo=UTC)


class ListSink:
    def __init__(self) -> None:
        self.sent: list[TaskScoreProposed] = []

    def send(self, payload: TaskScoreProposed) -> None:
        self.sent.append(payload)


class FailsOnceSink(ListSink):
    def __init__(self) -> None:
        super().__init__()
        self._failed = False

    def send(self, payload: TaskScoreProposed) -> None:
        if not self._failed:
            self._failed = True
            raise RuntimeError("broker down")
        super().send(payload)


def _timestamp(dt: datetime) -> Timestamp:
    ts = Timestamp()
    ts.FromDatetime(dt)
    return ts


def _created(task_id: str = TASK_A) -> TaskCreatedEvent:
    return TaskCreatedEvent(
        id=task_id,
        tenant_id=TENANT,
        title="Fix the checkout crash",
        description="Customers cannot pay since this morning's deploy.",
        tier=TaskTier.TASK_TIER_OPERATIONAL,
        effort_estimate_minutes=30,
        deadline=_timestamp(DEADLINE),
        created_at=_timestamp(CREATED_AT),
    )


def _updated(task_id: str = TASK_A, **changes) -> TaskUpdatedEvent:
    """The same task as `_created`, as task.updated, with `changes` applied."""
    fields = {
        "task_id": task_id,
        "tenant_id": TENANT,
        "title": "Fix the checkout crash",
        "description": "Customers cannot pay since this morning's deploy.",
        "tier": TaskTier.TASK_TIER_OPERATIONAL,
        "effort_estimate_minutes": 30,
        "deadline": _timestamp(DEADLINE),
        "updated_at": _timestamp(UPDATED_AT),
    }
    fields.update(changes)
    return TaskUpdatedEvent(**fields)


@pytest.fixture
def sink() -> ListSink:
    return ListSink()


@pytest.fixture
def publisher(sink: ListSink) -> ScorePublisher:
    return ScorePublisher(sink, clock=lambda: NOW)


# --- publish at creation ----------------------------------------------------


def test_a_created_task_is_published_once(publisher, sink):
    publisher.handle_task_created(_created())

    assert len(sink.sent) == 1
    assert sink.sent[0].task_id == UUID(TASK_A)
    assert sink.sent[0].scored_at == NOW


def test_the_published_score_is_the_models_base_score(publisher, sink):
    event = _created()
    expected = SegmentedRankingModel().score(
        to_scoring_task(event), ScoreContext(current_datetime=NOW)
    )

    publisher.handle_task_created(event)

    assert expected.factors is not None
    assert sink.sent[0].mind_score == expected.factors.base_score


# --- re-publish when the score can change -----------------------------------


@pytest.mark.parametrize(
    "change",
    [
        {"title": "Fix the checkout crash before the sale"},
        {"description": "Only some customers are affected."},
        {"tier": TaskTier.TASK_TIER_TACTICAL},
        {"deadline": _timestamp(datetime(2026, 10, 14, 9, 0, tzinfo=UTC))},
        {"effort_estimate_minutes": 90},
    ],
    ids=["title", "description", "tier", "deadline", "effort"],
)
def test_a_change_the_model_reads_is_re_published(publisher, sink, change):
    publisher.handle_task_created(_created())

    publisher.handle_task_updated(_updated(**change))

    assert len(sink.sent) == 2


def test_a_task_that_gets_its_tier_later_is_re_published(publisher, sink):
    created = _created()
    created.ClearField("tier")
    publisher.handle_task_created(created)

    publisher.handle_task_updated(_updated())

    assert len(sink.sent) == 2


# --- no re-publish otherwise (the Chain feedback loop) ----------------------


def test_chains_scheduling_update_is_not_re_published(publisher, sink):
    # Chain's update reaches us as task.updated with only is_available changed.
    publisher.handle_task_created(_created())

    publisher.handle_task_updated(_updated(is_available=True))

    assert len(sink.sent) == 1


def test_an_urgency_change_is_not_re_published(publisher, sink):
    # The model never reads the urgency field, so the score cannot change.
    publisher.handle_task_created(_created())

    publisher.handle_task_updated(_updated(urgency=5))

    assert len(sink.sent) == 1


def test_tasks_are_tracked_separately(publisher, sink):
    publisher.handle_task_created(_created(TASK_A))
    publisher.handle_task_created(_created(TASK_B))

    publisher.handle_task_updated(_updated(TASK_B, effort_estimate_minutes=90))

    assert [p.task_id for p in sink.sent] == [UUID(TASK_A), UUID(TASK_B), UUID(TASK_B)]


# --- state ------------------------------------------------------------------


def test_an_update_for_an_unseen_task_is_published(publisher, sink):
    # e.g. after a restart, when the in-memory state is empty.
    publisher.handle_task_updated(_updated())

    assert len(sink.sent) == 1


def test_a_failed_send_is_not_recorded_so_it_is_retried():
    sink = FailsOnceSink()
    publisher = ScorePublisher(sink, clock=lambda: NOW)
    with pytest.raises(RuntimeError):
        publisher.handle_task_created(_created())

    # Kafka redelivers the same event, since the offset was not committed.
    publisher.handle_task_created(_created())

    assert len(sink.sent) == 1


# --- events that arrive out of order ----------------------------------------


def test_an_older_event_handled_after_a_newer_one_is_dropped(publisher, sink):
    # The two topics have no ordering between them: the update can be handled
    # before the task.created it followed.
    update = _updated(deadline=_timestamp(datetime(2026, 10, 15, 9, 0, tzinfo=UTC)))
    publisher.handle_task_updated(update)

    publisher.handle_task_created(_created())

    expected = SegmentedRankingModel().score(
        to_scoring_task(update), ScoreContext(current_datetime=NOW)
    )
    assert expected.factors is not None
    assert len(sink.sent) == 1
    assert sink.sent[0].mind_score == expected.factors.base_score


def test_an_update_that_publishes_nothing_still_counts_as_seen(publisher, sink):
    # Chain's update changes no scored field, so nothing is sent, but an event
    # older than it is still stale.
    publisher.handle_task_created(_created())
    later = datetime(2026, 10, 13, 8, 10, tzinfo=UTC)
    publisher.handle_task_updated(_updated(is_available=True, updated_at=_timestamp(later)))

    publisher.handle_task_updated(_updated(effort_estimate_minutes=90))  # at UPDATED_AT

    assert len(sink.sent) == 1


def test_at_an_equal_time_task_created_counts_as_older(publisher, sink):
    # Inbox's clock has millisecond precision, so a create and an update can share
    # a timestamp. The update is the newer state and must not be overwritten.
    publisher.handle_task_updated(
        _updated(effort_estimate_minutes=90, updated_at=_timestamp(CREATED_AT))
    )

    publisher.handle_task_created(_created())  # created_at == CREATED_AT

    assert len(sink.sent) == 1


def test_an_event_without_a_timestamp_is_not_dropped(publisher, sink):
    publisher.handle_task_created(_created())
    event = _updated(effort_estimate_minutes=90)
    event.ClearField("updated_at")

    publisher.handle_task_updated(event)

    assert len(sink.sent) == 2


# --- events that can never be scored ----------------------------------------


def test_an_event_that_cannot_be_scored_is_skipped_not_raised(publisher, sink):
    # Raising would leave the offset uncommitted, and the same message would
    # fail on every redelivery and stall the partition.
    bad = _created()
    bad.id = "not-a-uuid"

    publisher.handle_task_created(bad)

    assert sink.sent == []


def test_the_next_event_is_still_handled_after_a_skipped_one(publisher, sink):
    bad = _created()
    bad.tenant_id = ""
    publisher.handle_task_created(bad)

    publisher.handle_task_created(_created(TASK_B))

    assert [p.task_id for p in sink.sent] == [UUID(TASK_B)]


def test_other_errors_still_propagate():
    # Only an unscorable event is swallowed; a broker error must be retried.
    publisher = ScorePublisher(FailsOnceSink(), clock=lambda: NOW)

    with pytest.raises(RuntimeError):
        publisher.handle_task_created(_created())


# --- re-score after hierarchical propagation (#474) ------------------------


class StubModel:
    """Returns `base` as the score and remembers every task it was given."""

    def __init__(self) -> None:
        self.base = 0.5
        self.tasks: list = []

    def score(self, task, context) -> ScoreResult:
        self.tasks.append(task)
        return ScoreResult(
            score=self.base,
            factors=ScoreFactors(contributions={"impact": self.base}, base_score=self.base),
        )


@pytest.fixture
def model() -> StubModel:
    return StubModel()


@pytest.fixture
def propagating(sink: ListSink, model: StubModel) -> ScorePublisher:
    return ScorePublisher(sink, model=model, clock=lambda: NOW)


def test_a_rescored_task_is_published_with_the_models_new_score(propagating, sink, model):
    propagating.handle_task_created(_created())
    model.base = 0.9  # the parent changed, so propagation now gives more

    propagating.rescore(TASK_A)
    assert propagating.drain_rescores() == 1

    assert [p.mind_score for p in sink.sent] == [0.5, 0.9]
    payload = sink.sent[1]
    assert payload.tenant_id == UUID(TENANT)
    assert payload.tier == ContractTier.OPERATIONAL
    assert payload.scored_at == NOW


def test_rescore_only_queues(propagating, sink):
    propagating.handle_task_created(_created())

    propagating.rescore(TASK_A)

    assert len(sink.sent) == 1  # nothing until the worker runs


def test_rescore_uses_the_newest_state(propagating, model):
    propagating.handle_task_created(_created())
    moved = datetime(2026, 10, 15, 9, 0, tzinfo=UTC)
    propagating.handle_task_updated(_updated(deadline=_timestamp(moved)))

    propagating.rescore(TASK_A)
    propagating.drain_rescores()

    assert model.tasks[-1].deadline == moved


def test_rescore_scores_with_the_full_task(propagating, model):
    # The model reads the text, so the state kept for rescore must keep it.
    propagating.handle_task_created(_created())

    propagating.rescore(TASK_A)
    propagating.drain_rescores()

    assert model.tasks[-1].title == "Fix the checkout crash"
    assert model.tasks[-1].description == "Customers cannot pay since this morning's deploy."


def test_an_unknown_task_is_not_rescored(propagating, sink):
    # e.g. a child no Inbox event has reached since a restart.
    propagating.rescore(TASK_B)

    assert propagating.drain_rescores() == 0
    assert sink.sent == []


def test_a_rescored_task_id_is_matched_in_any_spelling(propagating, model):
    propagating.handle_task_created(_created())
    model.base = 0.9

    propagating.rescore(TASK_A.upper())

    assert propagating.drain_rescores() == 1


def test_a_task_id_that_is_not_a_uuid_is_ignored_not_raised(propagating, sink):
    # Raising here would fail the scoring of the task the caller was working on.
    propagating.rescore("not-a-uuid")

    assert propagating.drain_rescores() == 0


def test_chains_echo_of_a_rescore_is_not_re_published(propagating, sink, model):
    # The rescore makes Chain update, and Inbox emits task.updated with no
    # scored field changed: that must not start a loop.
    propagating.handle_task_created(_created())
    model.base = 0.9
    propagating.rescore(TASK_A)
    propagating.drain_rescores()

    later = datetime(2026, 10, 13, 8, 10, tzinfo=UTC)
    propagating.handle_task_updated(_updated(is_available=True, updated_at=_timestamp(later)))

    assert len(sink.sent) == 2


def test_rescore_from_inside_score_does_not_deadlock(sink):
    # Propagation's natural place to notice a parent changed is inside
    # score(parent), while the publisher holds its lock.
    class ParentModel(StubModel):
        def score(self, task, context):
            if task.task_id == TASK_A:
                self.base = 0.9  # A's arrival changes what its child B is worth
                publisher.rescore(TASK_B)
            return super().score(task, context)

    publisher = ScorePublisher(sink, model=ParentModel(), clock=lambda: NOW)
    publisher.handle_task_created(_created(TASK_B))

    worker = threading.Thread(target=publisher.handle_task_created, args=(_created(TASK_A),))
    worker.start()
    worker.join(timeout=5)

    assert not worker.is_alive(), "rescore from inside score() deadlocked"
    assert publisher.drain_rescores() == 1
    assert [p.task_id for p in sink.sent] == [UUID(TASK_B), UUID(TASK_A), UUID(TASK_B)]


# --- errors that would repeat on every redelivery ---------------------------


class FailingModel(StubModel):
    """Raises for the ids in `fail_for`, as a model bug one task triggers would."""

    def __init__(self, fail_for: set[str]) -> None:
        super().__init__()
        self.fail_for = fail_for

    def score(self, task, context):
        if task.task_id in self.fail_for:
            raise RuntimeError("model bug for this task")
        return super().score(task, context)


def test_a_model_error_skips_the_task_and_the_next_one_is_handled(sink):
    publisher = ScorePublisher(sink, model=FailingModel({TASK_A}), clock=lambda: NOW)

    publisher.handle_task_created(_created(TASK_A))  # must not raise
    publisher.handle_task_created(_created(TASK_B))

    assert [p.task_id for p in sink.sent] == [UUID(TASK_B)]


def test_a_result_without_base_score_is_skipped(sink):
    class NoBaseScore(StubModel):
        def score(self, task, context):
            return ScoreResult(score=0.5)

    publisher = ScorePublisher(sink, model=NoBaseScore(), clock=lambda: NOW)

    publisher.handle_task_created(_created())

    assert sink.sent == []


def test_a_base_score_outside_0_1_is_skipped(sink):
    # ScoreResult accepts it (its own `score` is valid), but TaskScoreProposed
    # refuses mind_score > 1 with a pydantic ValidationError.
    class BaseScoreAboveOne(StubModel):
        def score(self, task, context):
            return ScoreResult(
                score=0.9,
                factors=ScoreFactors(contributions={"impact": 1.5}, base_score=1.5),
            )

    publisher = ScorePublisher(sink, model=BaseScoreAboveOne(), clock=lambda: NOW)

    publisher.handle_task_created(_created())

    assert sink.sent == []


def test_a_model_error_inside_score_is_skipped(sink):
    # The model's own ScoreResult refuses score > 1, so it raises inside score().
    model = StubModel()
    model.base = 1.5
    publisher = ScorePublisher(sink, model=model, clock=lambda: NOW)

    publisher.handle_task_created(_created())

    assert sink.sent == []


def test_a_rescore_error_skips_that_task_and_the_queue_goes_on(sink):
    model = FailingModel(set())
    publisher = ScorePublisher(sink, model=model, clock=lambda: NOW)
    publisher.handle_task_created(_created(TASK_A))
    publisher.handle_task_created(_created(TASK_B))
    model.fail_for.add(TASK_A)  # after the parent changed, TASK_A hits the bug
    model.base = 0.9

    publisher.rescore(TASK_A)
    publisher.rescore(TASK_B)

    assert publisher.drain_rescores() == 1
    assert [p.task_id for p in sink.sent] == [UUID(TASK_A), UUID(TASK_B), UUID(TASK_B)]


def test_a_conversion_error_other_than_a_bad_id_is_skipped(publisher, sink, monkeypatch):
    # Any conversion failure repeats on every redelivery, not only InvalidInboxEvent.
    def broken(event):
        raise RuntimeError("conversion bug")

    monkeypatch.setattr(score_publisher_module, "to_scoring_task", broken)

    publisher.handle_task_created(_created())  # must not raise

    assert sink.sent == []


def test_a_kafka_error_in_the_rescore_worker_propagates(model):
    # Only scoring errors are skipped; a send failure must reach the worker's
    # thread, so BackgroundThreads waits and runs it again.
    class FailsOnSecond(ListSink):
        def send(self, payload):
            if self.sent:
                raise RuntimeError("broker down")
            super().send(payload)

    publisher = ScorePublisher(FailsOnSecond(), model=model, clock=lambda: NOW)
    publisher.handle_task_created(_created())
    model.base = 0.9
    publisher.rescore(TASK_A)

    with pytest.raises(RuntimeError, match="broker down"):
        publisher.drain_rescores()


def test_a_rescore_whose_send_fails_is_queued_back(model):
    # Unlike a Kafka message, nothing would deliver the request again: the
    # worker must find it in the queue when it runs again, once Kafka is back.
    class FailsOnceAfterTheFirst(ListSink):
        failed = False

        def send(self, payload):
            if self.sent and not self.failed:
                self.failed = True
                raise RuntimeError("broker down")
            super().send(payload)

    sink = FailsOnceAfterTheFirst()
    publisher = ScorePublisher(sink, model=model, clock=lambda: NOW)
    publisher.handle_task_created(_created())
    model.base = 0.9
    publisher.rescore(TASK_A)
    with pytest.raises(RuntimeError):
        publisher.drain_rescores()

    assert publisher.drain_rescores() == 1  # the worker, run again
    assert [p.mind_score for p in sink.sent] == [0.5, 0.9]


# --- bounding re-scores -----------------------------------------------------


def test_a_rescore_with_an_unchanged_score_is_not_sent(propagating, sink):
    # Nothing new for Chain, and what ends a cycle of re-scores in the hierarchy.
    propagating.handle_task_created(_created())

    propagating.rescore(TASK_A)

    assert propagating.drain_rescores() == 0
    assert len(sink.sent) == 1


def test_a_task_requested_twice_is_rescored_once(propagating, model):
    propagating.handle_task_created(_created())
    model.base = 0.9
    scored_before = len(model.tasks)

    propagating.rescore(TASK_A)
    propagating.rescore(TASK_A)

    assert propagating.drain_rescores() == 1
    assert len(model.tasks) == scored_before + 1


def test_a_request_after_the_task_was_taken_is_queued_again(propagating, model):
    # Once the worker has taken a task, a new request must not be dropped.
    propagating.handle_task_created(_created())
    model.base = 0.9
    propagating.rescore(TASK_A)
    propagating.drain_rescores()

    model.base = 0.95
    propagating.rescore(TASK_A)

    assert propagating.drain_rescores() == 1


# --- stopping the rescore worker (#920) -------------------------------------


def test_the_rescore_worker_handles_the_queue_then_stops_when_asked(propagating, sink, model):
    # In the API the worker runs in a thread and must end on shutdown, even
    # while it waits on an empty queue.
    propagating.handle_task_created(_created())
    model.base = 0.9
    propagating.rescore(TASK_A)
    stop = threading.Event()

    worker = threading.Thread(target=propagating.run_rescores, args=(stop,))
    worker.start()
    deadline = time.monotonic() + 2
    while len(sink.sent) < 2 and time.monotonic() < deadline:
        time.sleep(0.01)
    stop.set()
    worker.join(timeout=3)

    assert not worker.is_alive()
    assert [p.mind_score for p in sink.sent] == [0.5, 0.9]
