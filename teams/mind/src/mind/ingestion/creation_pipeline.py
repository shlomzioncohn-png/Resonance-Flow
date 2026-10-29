"""#919: what the landed runner does with each text -- extract its tasks
(#690: none, one or several), check the tenant's index for each, and ask Inbox
to create the new ones.

    ExtractionItem ─► extract ─► to_task_creation_requested ─► find_duplicate ─► owner ─► flow.send
                        │              (one per task)          (tenant's index)
                  failure: see                                     │
                  below                                   match: logged at INFO,
                                                          not sent

The owner (#132) is looked up only for a task that is sent, and only when the
pipeline has a directory: see task_creation/README.md, "The owner".

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

`task_index` -- which, with Bridge's correlation_id, makes the request's
correlation_id -- is `item position * TASK_SLOTS_PER_ITEM + task position`,
never a count of the tasks sent. An item that failed this time and passes on
redelivery, or one whose task list comes back longer, must not shift another
item's numbers, or their correlation_ids change and Inbox makes second tasks.
The first task of the first item is 0, the number a one-task item always had.
"""

import logging
from collections.abc import Awaitable, Callable
from typing import Protocol
from uuid import UUID

from mind.config import DeadlineSettings
from mind.contracts import RawObjectEvent
from mind.contracts.raw_object_event_enums import Sensitivity
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.embeddings.base import SearchResult
from mind.extraction.assembly import to_task_creation_requested
from mind.extraction.extractor import ExtractionFailure, ExtractionItem, ExtractionOutcome
from mind.extraction.models import ExtractedFields
from mind.ingestion.event_objects import tenant_uuid
from mind.task_creation.owner import OwnerDirectory, choose_owner, what_to_look_up

logger = logging.getLogger(__name__)

#: How many task numbers each item of an event owns. Fixed for ever, like the
#: correlation namespace: changing it renumbers every multi-item event, and
#: each request already sent would be repeated as a new one. Far above
#: extraction's MAX_TASKS_PER_TEXT, so that cap can grow without touching
#: this; a test fails if it ever passes it.
TASK_SLOTS_PER_ITEM = 100


def task_index_for(item_position: int, task_position: int) -> int:
    """The number of task `task_position` of the event's item `item_position`."""

    return item_position * TASK_SLOTS_PER_ITEM + task_position


#: One item through the extractor: `BasicExtractor.extract` with the item's fields.
Extract = Callable[[ExtractionItem], ExtractionOutcome]


class ExtractionUnavailable(Exception):
    """Extraction cannot run here now: the provider is down, rate-limited, or
    refuses the account (key, credit). Like `MediaUnavailable`, raised so the
    process stops and the message comes again, rather than every item landed
    meanwhile being marked processed with nothing extracted."""


#: (tenant_id, title, description) -> the closest open task of that tenant, if
#: it clears the threshold: `find_duplicate` on `index.for_tenant(tenant_id)`
#: (#841: one tenant's task is never another's duplicate).
FindDuplicate = Callable[[UUID, str, str], Awaitable[SearchResult | None]]


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
        dependency_mentions: tuple[str, ...] = (),
    ) -> UUID: ...


class CreationPipeline:
    def __init__(
        self,
        *,
        extract: Extract,
        find_duplicate: FindDuplicate,
        sender: Sender,
        deadline_settings: DeadlineSettings,
        directory: OwnerDirectory | None = None,
    ) -> None:
        self._extract = extract
        self._find_duplicate = find_duplicate
        self._sender = sender
        self._deadline_settings = deadline_settings
        # Inbox's users, for the suggested owner (#132). None sends no owner.
        self._directory = directory

    def _with_owner(
        self,
        request: TaskCreationRequested,
        fields: ExtractedFields,
        item: ExtractionItem,
        tenant_id: UUID,
    ) -> TaskCreationRequested:
        """The request with Mind's suggested owner, or as it was when there is none."""

        if self._directory is None:
            return request
        look_up = what_to_look_up(
            fields.owner_mention,
            fields.owner_is_recipient,
            sender=item.sender,
            recipients=item.recipients,
        )
        if look_up is None:
            return request
        # A blocking call, like the extraction above: #131 answers from its
        # cache, and asks Inbox only when that has expired. It promises not to
        # raise; if it does, the task goes without an owner, which is optional,
        # rather than the message failing and coming back for ever.
        # The error is logged by type: its message may hold the name or the
        # address asked for, which no owner log carries. The trace is at DEBUG.
        try:
            candidates = self._directory.find_candidates(tenant_id, look_up)
        except Exception as error:
            logger.error(
                "creation: the owner directory failed (%s); the task goes with no owner",
                type(error).__name__,
            )
            logger.debug("creation: the owner directory's error", exc_info=True)
            return request
        owner = choose_owner(candidates)
        if owner is None:
            return request
        return request.model_copy(
            update={
                "suggested_owner_user_id": owner.user_id,
                "suggested_owner_confidence": owner.confidence,
            }
        )

    async def handle(self, event: RawObjectEvent, items: list[ExtractionItem]) -> list[UUID]:
        """Send one creation request per new task the items hold; return their correlation_ids."""

        # Already checked by extraction_items, so this does not raise here.
        tenant_id = tenant_uuid(event)
        sent: list[UUID] = []
        for item_position, item in enumerate(items):
            # A blocking LLM call, on purpose: this loop serves nothing else,
            # and the consumer waits for the message anyway.
            outcome = self._extract(item)
            if isinstance(outcome, ExtractionFailure):
                if outcome.environmental:
                    raise ExtractionUnavailable(
                        f"{event.correlation_id} {item.item_key}: {outcome.reason}: {outcome.detail}"
                    )
                continue

            requests = to_task_creation_requested(
                outcome,
                tenant_id,
                bridge_source_ref=event.source_ref,
                bridge_correlation_id=event.correlation_id,
                # When the text was written, for "by tomorrow" (#960): the
                # source's own time when Bridge has it, else when it landed.
                written_at=event.source_timestamp or event.ingested_at,
                deadline_settings=self._deadline_settings,
            )
            # One request per extracted task, in the same order. The task's
            # dependency mentions are not in the contract: they travel beside
            # the request to #834's resolver, which reads them off the pending
            # entry once Inbox answers (models.ExtractedFields, #359).
            tasks = zip(outcome.tasks, requests, strict=True)
            for task_position, (task, request) in enumerate(tasks):
                task_index = task_index_for(item_position, task_position)
                match = await self._find_duplicate(
                    tenant_id, request.title, request.description or ""
                )
                if match is not None:
                    # Mind has no update path and Inbox no "possible duplicate"
                    # field: sending would only make a second task.
                    logger.info(
                        "creation: %s task %d not sent, %s is a duplicate of task %s "
                        "(similarity %.3f)",
                        event.correlation_id,
                        task_index,
                        _loggable_title(event, request.title),
                        match.task_id,
                        match.similarity,
                    )
                    continue

                request = self._with_owner(request, task.fields, item, tenant_id)
                sent.append(
                    await self._sender.send(
                        request,
                        bridge_correlation_id=event.correlation_id,
                        revision=event.revision,
                        task_index=task_index,
                        dependency_mentions=tuple(task.fields.dependency_mentions),
                    )
                )
        return sent
