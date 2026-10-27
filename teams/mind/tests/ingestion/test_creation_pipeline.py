"""#919: a landed item through extraction, the duplicate check and the
creation flow -- and Inbox's reply back through the same flow.

No broker and no model: Bridge's message is built by hand, the LLM is
scripted, the producer is a fake and the index is R3's in-memory double. The
flow itself is the real `TaskCreationFlow`, so the request a test sees is
the one Inbox would receive. The issue's acceptance criteria are the first
three tests.
"""

import asyncio
import logging
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

import pytest
from tests.extraction.fakes import DEADLINE_SETTINGS, ScriptedProvider, answer_of
from tests.ingestion.test_landed_consumer import BODY, ITEM, KEY, TENANT, _message, _read
from tests.test_index_consumer import FakeVectorIndex

from mind.contracts import RawObjectEvent
from mind.contracts.base_enums import SourceType
from mind.embeddings.base import SearchResult
from mind.extraction.extractor import BasicExtractor, ExtractionItem
from mind.extraction.models import MAX_TASKS_PER_TEXT
from mind.extraction.providers.base import (
    LLMAccessDenied,
    LLMRateLimited,
    LLMRejected,
    LLMTimeout,
    LLMUnavailable,
)
from mind.ingestion.creation_pipeline import (
    TASK_SLOTS_PER_ITEM,
    CreationPipeline,
    ExtractionUnavailable,
)
from mind.ingestion.landed_consumer import LandedHandler
from mind.ingestion.seen_revisions import InMemoryRevisionStore
from mind.messaging.task_created_event_pb2 import TaskCreatedEvent
from mind.task_creation.correlation import correlation_id_for
from mind.task_creation.flow import TaskCreationFlow
from mind.task_creation.pending import PendingRequests
from mind.task_creation.reply import MatchedReply

TITLE = "Send the updated price quote"
TASK_ID = "4d0c6f2a-8b1e-4c3d-9a7f-1e2b3c4d5e6f"  # what Inbox hands out
TASK: dict[str, object] = {
    "title": TITLE,
    "description": "Asked for in Slack, due tomorrow.",
    "source_ref": None,
    "has_dependencies": False,
    "tier": "OPERATIONAL",
    "urgency": 4,
    "role_context": "SALESPERSON",
}
ANSWER = answer_of(TASK)
SECOND_TITLE = "Book the room for Thursday's review"
TWO_TASKS = answer_of(TASK, {**TASK, "title": SECOND_TITLE})


@pytest.fixture(autouse=True)
def _no_embedding_model(monkeypatch: pytest.MonkeyPatch) -> None:
    """Indexing a matched task embeds it; the real model downloads on first use."""

    monkeypatch.setattr("mind.embeddings.index_consumer.embed", lambda _text: [0.1, 0.2, 0.3])


class FakeProducer:
    def __init__(self) -> None:
        self.sent: list[tuple[str, Any, str | None]] = []

    def publish(self, topic: str, message: object, key: str | None = None) -> None:
        self.sent.append((topic, message, key))


class BrokenProducer:
    def publish(self, topic: str, message: object, key: str | None = None) -> None:
        raise ConnectionError("Kafka unavailable")


class Index:
    """`find_duplicate` on a tenant's index: what it was asked, and what it answers."""

    def __init__(self, match: SearchResult | None = None) -> None:
        self.match = match
        self.asked: list[tuple[UUID, str, str]] = []

    async def __call__(self, tenant_id: UUID, title: str, description: str) -> SearchResult | None:
        self.asked.append((tenant_id, title, description))
        return self.match


class Parts:
    """A pipeline on the real flow, with every outside party faked."""

    def __init__(
        self,
        *answers: str | Exception,
        match: SearchResult | None = None,
        producer: Any = None,
        index: "Index | None" = None,
    ) -> None:
        self.producer = producer or FakeProducer()
        self.store = FakeVectorIndex()
        self.index = index or Index(match)
        self.matched: list[MatchedReply] = []

        async def on_matched(matched: MatchedReply) -> None:
            self.matched.append(matched)

        self.flow = TaskCreationFlow(
            producer=self.producer,
            index=self.store,
            pending=PendingRequests(),
            on_matched=on_matched,
        )
        extractor = BasicExtractor(ScriptedProvider(*answers), sleep=lambda _: None)
        self.pipeline = CreationPipeline(
            extract=lambda item: extractor.extract(item.text, item.source_type, item.item_key),
            find_duplicate=self.index,
            sender=self.flow,
            deadline_settings=DEADLINE_SETTINGS,
        )


def _classification(sensitivity: str) -> dict[str, Any]:
    return {
        "decided_by": "MODEL",
        "sensitivity": {"value": sensitivity, "confidence": 0.9},
        "reason": "test",
        "classifier": {"model": "test", "prompt_version": "1"},
    }


def _event(
    revision: int = 0, sensitivity: str | None = None, source_timestamp: str | None = None
) -> RawObjectEvent:
    classification = _classification(sensitivity) if sensitivity else None
    return RawObjectEvent.model_validate(
        {
            "classification": classification,
            "schema_version": "1.0",
            "source_type": "SLACK",
            "tenant_id": TENANT,
            "correlation_id": str(ITEM),
            "ingested_at": "2026-10-18T09:00:00Z",
            "source_timestamp": source_timestamp,
            "scope_decision": "INCLUDED",
            "revision": revision,
            "change_type": "UPSERT",
            "change_significance_score": 80,
            "objects": [
                {
                    "role": "BODY",
                    "object_key": KEY,
                    "content_hash": "sha256:" + "a" * 64,
                    "modality": "TEXT",
                }
            ],
        }
    )


def _item(key: str = KEY) -> ExtractionItem:
    return ExtractionItem(text=BODY.decode(), source_type=SourceType.SLACK, item_key=key)


def _landed_handler(parts: Parts) -> tuple[LandedHandler, InMemoryRevisionStore]:
    """`LandedHandler` wired as the runner wires it: one loop for the pipeline."""

    loop = asyncio.new_event_loop()
    store = InMemoryRevisionStore()

    def on_items(event: RawObjectEvent, items: list[ExtractionItem]) -> None:
        loop.run_until_complete(parts.pipeline.handle(event, items))

    return LandedHandler(read_object=_read, store=store, on_items=on_items), store


def test_a_landed_event_becomes_one_creation_request() -> None:
    parts = Parts(ANSWER)
    handle, store = _landed_handler(parts)

    handle(_message(revision=3), None)

    ((topic, request, key),) = parts.producer.sent
    assert topic == "task.creation.requested"
    assert key == str(correlation_id_for(ITEM, 0))
    assert (request.title, request.tenant_id) == (TITLE, TENANT)
    # Bridge's item had no source_ref of its own: Inbox gets Bridge's id for it.
    assert request.source_ref == f"bridge:{ITEM}"
    # Searched in the item's own tenant only (#841).
    assert parts.index.asked == [(UUID(TENANT), TITLE, "Asked for in Slack, due tomorrow.")]
    assert store.processed_revision(ITEM) == 3


DUE_TOMORROW = answer_of(
    {**TASK, "deadline": {"phrase": "by tomorrow", "kind": "OFFSET", "amount": 1, "unit": "DAYS"}}
)


@pytest.mark.parametrize(
    ("source_timestamp", "expected"),
    [
        # No source time: counted from when it landed, Sunday the 18th -> Monday
        # the 19th, 17:00 in Israel.
        (None, datetime(2026, 10, 19, 14, 0, tzinfo=UTC)),
        # The source's own time wins: written Thursday the 15th -> Friday the 16th.
        ("2026-10-15T09:00:00Z", datetime(2026, 10, 16, 14, 0, tzinfo=UTC)),
    ],
)
async def test_the_deadline_is_counted_from_when_the_text_was_written(
    source_timestamp: str | None, expected: datetime
) -> None:
    """#960: "by tomorrow" in the Slack message reaches Inbox as a moment."""

    parts = Parts(DUE_TOMORROW)

    await parts.pipeline.handle(_event(source_timestamp=source_timestamp), [_item()])

    ((_, request, _),) = parts.producer.sent
    assert request.deadline.ToDatetime(tzinfo=UTC) == expected


async def _duplicate_log_line(caplog: pytest.LogCaptureFixture, event: RawObjectEvent) -> str:
    """Run a duplicate through the pipeline; return the one line it logged."""

    caplog.set_level(logging.INFO, logger="mind.ingestion.creation_pipeline")
    parts = Parts(ANSWER, match=SearchResult(task_id=TASK_ID, similarity=0.93, confidence=0.7))

    sent = await parts.pipeline.handle(event, [_item()])

    assert sent == []
    assert parts.producer.sent == []
    (record,) = [r for r in caplog.records if r.name == "mind.ingestion.creation_pipeline"]
    assert record.levelno == logging.INFO
    assert TASK_ID in record.getMessage()
    return record.getMessage()


async def test_a_duplicate_is_not_sent_and_logged_once_at_info(
    caplog: pytest.LogCaptureFixture,
) -> None:
    line = await _duplicate_log_line(caplog, _event(sensitivity="LOW"))

    assert TITLE in line


@pytest.mark.parametrize("sensitivity", ["HIGH", None])
async def test_a_duplicate_of_a_sensitive_or_unclassified_item_logs_no_title(
    caplog: pytest.LogCaptureFixture, sensitivity: str | None
) -> None:
    # The title is written from the content, and logs travel further than the
    # content was meant to: only Bridge's "not sensitive" lets it out.
    line = await _duplicate_log_line(caplog, _event(sensitivity=sensitivity))

    assert "price quote" not in line
    assert f"{len(TITLE)} chars" in line


async def test_task_created_for_the_request_is_matched_indexed_and_reported() -> None:
    parts = Parts(ANSWER)
    (correlation_id,) = await parts.pipeline.handle(_event(revision=2), [_item()])
    assert parts.store.rows == {}  # not indexed before Inbox confirms

    reply = TaskCreatedEvent(
        id=TASK_ID, tenant_id=TENANT, title=TITLE, description="Asked for in Slack, due tomorrow."
    )
    await parts.flow.on_task_created(reply, [("correlation_id", str(correlation_id).encode())])

    # Indexed under the item's tenant (#841).
    assert parts.store.rows[TASK_ID][3] == UUID(TENANT)
    (matched,) = parts.matched
    assert (matched.correlation_id, matched.task_id) == (correlation_id, TASK_ID)
    assert (matched.request.bridge_correlation_id, matched.request.revision) == (ITEM, 2)


async def test_a_failed_extraction_does_not_shift_the_next_items_number() -> None:
    # The first item fails now and may pass on redelivery: the second must
    # keep its correlation_id either way, or Inbox gets a second task.
    parts = Parts(LLMRejected("400 invalid request"), ANSWER)

    sent = await parts.pipeline.handle(_event(), [_item("a.txt"), _item("b.txt")])

    assert sent == [correlation_id_for(ITEM, TASK_SLOTS_PER_ITEM)]
    assert [key for _, _, key in parts.producer.sent] == [
        str(correlation_id_for(ITEM, TASK_SLOTS_PER_ITEM))
    ]


# --- several tasks per text (#690) ---------------------------------------------


async def test_each_task_of_a_text_gets_its_own_request() -> None:
    parts = Parts(TWO_TASKS)

    sent = await parts.pipeline.handle(_event(), [_item()])

    assert sent == [correlation_id_for(ITEM, 0), correlation_id_for(ITEM, 1)]
    assert [request.title for _, request, _ in parts.producer.sent] == [TITLE, SECOND_TITLE]
    # Every task is checked for a duplicate on its own.
    assert [title for _, title, _ in parts.index.asked] == [TITLE, SECOND_TITLE]


async def _matched_for_each_sent(parts: Parts, sent: list[UUID]) -> list[MatchedReply]:
    """Inbox's task.created for every request sent, through the real flow."""

    for number, correlation_id in enumerate(sent):
        reply = TaskCreatedEvent(id=f"{TASK_ID[:-1]}{number}", tenant_id=TENANT, title=TITLE)
        await parts.flow.on_task_created(reply, [("correlation_id", str(correlation_id).encode())])
    return parts.matched


async def test_a_tasks_dependency_mentions_reach_the_matched_reply() -> None:
    # #359: extraction found them, but the pipeline did not hand them on, so
    # #834's adapter (on_matched) always read an empty list and published nothing.
    depends_on_the_second = {
        **TASK,
        "has_dependencies": True,
        "dependency_mentions": [SECOND_TITLE],  # grounded by its sibling's title
    }
    parts = Parts(answer_of(depends_on_the_second, {**TASK, "title": SECOND_TITLE}))

    sent = await parts.pipeline.handle(_event(), [_item()])
    first, second = await _matched_for_each_sent(parts, sent)

    assert first.request.dependency_mentions == (SECOND_TITLE,)
    assert second.request.dependency_mentions == ()


async def test_a_task_without_dependency_mentions_hands_on_none() -> None:
    parts = Parts(ANSWER)

    sent = await parts.pipeline.handle(_event(), [_item()])
    (matched,) = await _matched_for_each_sent(parts, sent)

    assert matched.request.dependency_mentions == ()


async def test_each_item_owns_its_own_range_of_task_numbers() -> None:
    # Item 0 has two tasks, item 1 one: item 1's task is 100, not 2, so a
    # longer list for item 0 on redelivery cannot take item 1's number.
    parts = Parts(TWO_TASKS, ANSWER)

    sent = await parts.pipeline.handle(_event(), [_item("a.txt"), _item("b.txt")])

    assert sent == [
        correlation_id_for(ITEM, 0),
        correlation_id_for(ITEM, 1),
        correlation_id_for(ITEM, TASK_SLOTS_PER_ITEM),
    ]


async def test_one_duplicate_task_does_not_hold_back_the_others_of_its_text() -> None:
    class FirstIsADuplicate(Index):
        async def __call__(
            self, tenant_id: UUID, title: str, description: str
        ) -> SearchResult | None:
            await super().__call__(tenant_id, title, description)
            return (
                SearchResult(task_id=TASK_ID, similarity=0.9, confidence=0.7)
                if title == TITLE
                else None
            )

    parts = Parts(TWO_TASKS, index=FirstIsADuplicate())

    sent = await parts.pipeline.handle(_event(), [_item()])

    # The second task keeps its own number, 1: not renumbered to 0.
    assert sent == [correlation_id_for(ITEM, 1)]


def test_one_items_tasks_can_never_take_the_next_items_numbers() -> None:
    # TASK_SLOTS_PER_ITEM is fixed for ever; raising extraction's cap past it
    # needs a new numbering, not a bigger constant.
    assert MAX_TASKS_PER_TEXT <= TASK_SLOTS_PER_ITEM


def test_a_failed_publish_propagates_and_the_item_is_not_marked_processed() -> None:
    parts = Parts(ANSWER, producer=BrokenProducer())
    handle, store = _landed_handler(parts)

    with pytest.raises(ConnectionError):
        handle(_message(), None)

    assert store.processed_revision(ITEM) is None


def test_an_item_with_its_own_source_ref_keeps_it() -> None:
    parts = Parts(ANSWER)
    handle, _ = _landed_handler(parts)

    handle(_message(source_ref="C024BE91L:1728900000.000100"), None)

    ((_, request, _),) = parts.producer.sent
    assert request.source_ref == "C024BE91L:1728900000.000100"


def test_the_sent_request_uses_the_extracted_classification() -> None:
    parts = Parts(ANSWER)
    handle, _ = _landed_handler(parts)

    handle(_message(), None)

    ((_, request, _),) = parts.producer.sent
    assert request.urgency == 4
    assert request.HasField("tier") and request.HasField("role_context")


# --- whose fault a failed extraction is (#919 review) -------------------------
#
# The item's fault: skip it, the rest goes on. The environment's: raise, so the
# message is not marked processed and comes again -- otherwise a spent budget
# marks the whole topic processed with no task created.

_DOWN = [LLMTimeout("t")] * 3  # the first call and its two transient retries


@pytest.mark.parametrize(
    "script",
    [
        pytest.param(_DOWN, id="timeout past retries"),
        pytest.param([LLMRateLimited("429")] * 3, id="429 past retries"),
        pytest.param([LLMUnavailable("503")] * 3, id="5xx past retries"),
        pytest.param([LLMAccessDenied("401 invalid key")], id="401 key"),
        pytest.param([LLMAccessDenied("402 insufficient balance")], id="402 credit"),
        pytest.param([LLMAccessDenied("403 forbidden")], id="403 permission"),
    ],
)
def test_an_environmental_failure_stops_the_message_for_a_retry(
    script: list[Exception],
) -> None:
    parts = Parts(*script)
    handle, store = _landed_handler(parts)

    with pytest.raises(ExtractionUnavailable):
        handle(_message(), None)

    assert parts.producer.sent == []
    assert store.processed_revision(ITEM) is None  # comes again after the restart


@pytest.mark.parametrize(
    "script",
    [
        pytest.param(["nope", "still nope", "nope again"], id="invalid_output"),
        pytest.param([LLMRejected("400 invalid request")], id="400 request"),
        pytest.param([LLMRejected("Gemini blocked the answer (SAFETY)")], id="content blocked"),
    ],
)
def test_a_failure_of_the_item_itself_is_skipped(script: list[str | Exception]) -> None:
    parts = Parts(*script)
    handle, store = _landed_handler(parts)

    handle(_message(), None)

    assert parts.producer.sent == []
    assert store.processed_revision(ITEM) == 0  # skipped: one item cannot stall the topic


async def test_an_environmental_failure_stops_before_the_next_item() -> None:
    # The second item would fail the same way; nothing more is tried or sent.
    parts = Parts(LLMAccessDenied("402 insufficient balance"), ANSWER)

    with pytest.raises(ExtractionUnavailable):
        await parts.pipeline.handle(_event(), [_item("a.txt"), _item("b.txt")])

    assert parts.producer.sent == []
