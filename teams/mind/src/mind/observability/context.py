"""Which item, task and stage a recorded call belongs to (#482).

record_call() reads the current context and puts it on the record, so the
calls of one item can be added up across the pipeline -- OCR or ASR,
extraction, the duplicate check and the indexing in the landed runner, and
scoring in the API -- without any call site passing it. The context is set
once, where a message is handled, with `call_context(...)`:

    with call_context(item=item_ref(tenant_id, source_ref), stage="landed"):
        handle(message)

It is a `contextvars.ContextVar`, so it follows the work: into an asyncio task
created inside the block, and into `asyncio.to_thread` (the embedding calls).
A nested `call_context` overrides only the fields it names; leaving the
block restores the outer context.

Ids only, never content:

* `item` -- `item_ref(tenant_id, source_ref)`, a 16-hex-digit digest of the
  item's `source_ref` as Mind sends it to Inbox. Every task from one item
  carries that `source_ref` back on task.created / task.updated, so the API
  can name the same item without being told -- and a `source_ref` (a mail id,
  a Slack link) never lands in a file.
* `task` -- Inbox's task id, a UUID.
* `stage` -- one word from STAGES, saying which handler did the work.

A value that is not one of these shapes is refused with ValueError, so
nothing else can be put in the ledger by mistake. A message handler sets it
with `message_context(...)` instead, which leaves a bad field out rather than
fail the message.
"""

from __future__ import annotations

import hashlib
import logging
import re
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, replace
from uuid import UUID

logger = logging.getLogger("mind.observability")

#: The stages a call can be attributed to (documented in the README).
STAGES = frozenset(
    {
        "landed",  # landed runner: one bridge.raw.landed item, end to end
        "to_text",  # its objects read and turned into text: OCR, ASR
        "extraction",  # the LLM calls that turn the text into tasks
        "duplicate_check",  # each candidate task against the index
        "task_created",  # Inbox's task.created back: indexing, dependencies
        "score",  # API: one task.created / task.updated scored
    }
)

_ITEM_REF = re.compile(r"^[0-9a-f]{16}$")


@dataclass(frozen=True)
class CallContext:
    item: str | None = None
    task: str | None = None
    stage: str | None = None


_EMPTY = CallContext()
_current: ContextVar[CallContext] = ContextVar("mind_call_context", default=_EMPTY)


def item_ref(tenant_id: str, source_ref: str) -> str:
    """The item's id in the ledger: a digest of (tenant, source_ref), not either value.

    Both the landed runner and the API can compute it: the same `source_ref`
    Mind sends Inbox comes back on every task.created / task.updated.
    """
    digest = hashlib.sha256(f"{tenant_id}\x00{source_ref}".encode()).hexdigest()
    return digest[:16]


def current_context() -> CallContext:
    """The context record_call() puts on a record; all None outside any block."""
    return _current.get()


@contextmanager
def call_context(
    *, item: str | None = None, task: str | UUID | None = None, stage: str | None = None
) -> Iterator[CallContext]:
    """Attribute every call recorded inside the block to `item`, `task` and `stage`.

    Fields left None are inherited from the enclosing context.

    Raises:
        ValueError: `item` is not an item_ref(), `task` not a UUID, or `stage`
            not one of STAGES. Raised before anything is set.
    """
    changes: dict[str, str] = {}
    if item is not None:
        if not _ITEM_REF.match(item):
            raise ValueError(f"item must be an item_ref(), got {item!r}")
        changes["item"] = item
    if task is not None:
        changes["task"] = str(task if isinstance(task, UUID) else UUID(task))
    if stage is not None:
        if stage not in STAGES:
            raise ValueError(f"unknown stage {stage!r}, expected one of {sorted(STAGES)}")
        changes["stage"] = stage
    token = _current.set(replace(_current.get(), **changes))
    try:
        yield _current.get()
    finally:
        _current.reset(token)


@contextmanager
def message_context(
    *,
    stage: str,
    tenant_id: str | None = None,
    source_ref: str | None = None,
    task: str | None = None,
) -> Iterator[CallContext]:
    """call_context() for a message handler, from the message's own fields.

    Never raises for the message's sake: a field that is missing or the wrong
    shape (no source_ref, a task id that is not a UUID) is left out, and the
    message is handled all the same -- its calls are just attributed to less.
    `item` is item_ref(tenant_id, source_ref) when both are non-empty.
    """
    item = item_ref(tenant_id, source_ref) if tenant_id and source_ref else None
    task_id: str | None = None
    if task:
        try:
            task_id = str(UUID(task))
        except ValueError:
            logger.debug("call context: task id %r is not a UUID, left out", task)
    with call_context(item=item, task=task_id, stage=stage) as context:
        yield context
