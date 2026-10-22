"""#919: what the landed runner does with each text -- extract a task, check
the index for it, and ask Inbox to create it.

    ExtractionItem ─► extract ─► to_task_creation_requested ─► find_duplicate ─► flow.send
                        │                                          │
                  failure: logged,                        match: logged at INFO,
                  next item                               not sent

Failures, the same two kinds as `landed_consumer.py`:

- skip: an extraction the item itself failed -- an answer that never fit the
  schema, a request or content the provider refused (400, a safety block) --
  or a duplicate. The extractor logged the failure at ERROR. The other items
  go on.
- retry (raise): what the environment caused, so every other item would fail
  too -- an extraction that failed on the provider (down or rate-limited past
  its retries, the key, the credit: `ExtractionUnavailable`), the index
  search, the publish. The message is not marked processed, and comes again
  after the restart. Items sent before the failure are sent again with the
  same correlation_id, and Inbox dedups them.

`task_index` is the item's position in the event, not a count of the tasks
sent: an item that failed this time and passes on redelivery must not shift
the next item's number, or its correlation_id changes and Inbox makes a
second task.
"""

import logging
from collections.abc import Awaitable, Callable
from typing import Protocol
from uuid import UUID

from mind.contracts import RawObjectEvent
from mind.contracts.raw_object_event_enums import Sensitivity
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.embeddings.base import SearchResult
from mind.extraction.assembly import to_task_creation_requested
from mind.extraction.extractor import ExtractionFailure, ExtractionItem, ExtractionOutcome
from mind.ingestion.event_objects import tenant_uuid

logger = logging.getLogger(__name__)

#: One item through the extractor: `BasicExtractor.extract` with the item's fields.
Extract = Callable[[ExtractionItem], ExtractionOutcome]


class ExtractionUnavailable(Exception):
    """Extraction cannot run here now: the provider is down, rate-limited, or
    refuses the account (key, credit). Like `MediaUnavailable`, raised so the
    process stops and the message comes again, rather than every item landed
    meanwhile being marked processed with nothing extracted."""


#: (title, description) -> the closest open task, if it clears the threshold:
#: `find_duplicate` bound to the index.
FindDuplicate = Callable[[str, str], Awaitable[SearchResult | None]]


def _loggable_title(event: RawObjectEvent, title: str) -> str:
    """The title, quoted, only when Bridge judged the item not sensitive.

    The title is written from the content, and logs travel further than the
    content was meant to. An item Bridge did not classify is treated like a
    HIGH one, as the contract treats an unknown sensitivity: only the
    length goes out, and the matched task_id still says which task it was.
    """

    classification = event.classification
    if classification is not None and classification.sensitivity.value is not Sensitivity.HIGH:
        return repr(title)
    return f"a title of {len(title)} chars (withheld, item not classified as non-sensitive)"


class Sender(Protocol):
    """The part of `TaskCreationFlow` the pipeline uses."""

    async def send(
        self,
        request: TaskCreationRequested,
        *,
        bridge_correlation_id: UUID,
        revision: int,
        task_index: int = 0,
    ) -> UUID: ...


class CreationPipeline:
    def __init__(self, *, extract: Extract, find_duplicate: FindDuplicate, sender: Sender) -> None:
        self._extract = extract
        self._find_duplicate = find_duplicate
        self._sender = sender

    async def handle(self, event: RawObjectEvent, items: list[ExtractionItem]) -> list[UUID]:
        """Send one creation request per item that is a new task; return their correlation_ids."""

        # Already checked by extraction_items, so this does not raise here.
        tenant_id = tenant_uuid(event)
        sent: list[UUID] = []
        for task_index, item in enumerate(items):
            # A blocking LLM call, on purpose: this loop serves nothing else,
            # and the consumer waits for the message anyway.
            outcome = self._extract(item)
            if isinstance(outcome, ExtractionFailure):
                if outcome.environmental:
                    raise ExtractionUnavailable(
                        f"{event.correlation_id} {item.item_key}: {outcome.reason}: {outcome.detail}"
                    )
                continue

            request = to_task_creation_requested(
                outcome,
                tenant_id,
                bridge_source_ref=event.source_ref,
                bridge_correlation_id=event.correlation_id,
            )
            match = await self._find_duplicate(request.title, request.description or "")
            if match is not None:
                # Mind has no update path and Inbox no "possible duplicate"
                # field: sending would only make a second task.
                logger.info(
                    "creation: %s task %d not sent, %s is a duplicate of task %s (similarity %.3f)",
                    event.correlation_id,
                    task_index,
                    _loggable_title(event, request.title),
                    match.task_id,
                    match.similarity,
                )
                continue

            sent.append(
                await self._sender.send(
                    request,
                    bridge_correlation_id=event.correlation_id,
                    revision=event.revision,
                    task_index=task_index,
                )
            )
        return sent
