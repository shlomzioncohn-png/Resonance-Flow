"""Creation requests Inbox has not answered in time (#353).

Inbox down or the message lost, a request would otherwise wait in silence for
ever. Past the limit it is reported once, as a warning — and kept, because a
late reply should still be matched.

The limit starts as a fixed guess. Inbox answers in seconds, so ten minutes
already means something is wrong rather than slow. Every matched reply logs
how long it took (`reply.py`), so it can later be set from real latencies —
twice the usual — instead.
"""

import logging
from datetime import datetime, timedelta
from uuid import UUID

from mind.task_creation.pending import PendingStore

logger = logging.getLogger("mind.task_creation")

DEFAULT_MAX_WAIT = timedelta(minutes=10)


async def report_overdue(
    pending: PendingStore, *, now: datetime, max_wait: timedelta = DEFAULT_MAX_WAIT
) -> list[UUID]:
    """Warn about each request waiting longer than `max_wait`, once; return them."""

    reported: list[UUID] = []
    for correlation_id, request in await pending.all():
        waited = now - request.sent_at
        if waited <= max_wait:
            continue
        # Only now: marking a request reported is a change, not a check, and
        # done for a young one it would silence the warning it is owed later.
        if await pending.mark_reported(correlation_id):
            logger.warning(
                "task_creation: no task.created for request %s (Bridge item %s, task %d) "
                "after %s; still waiting",
                correlation_id,
                request.bridge_correlation_id,
                request.task_index,
                waited,
            )
            reported.append(correlation_id)
    return reported
