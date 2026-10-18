"""Reporting creation requests Inbox has not answered in time — once each, and kept.

Why ten minutes, and when a request is marked: README.md, "A reply that never comes".
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
        # Only past the wait: marking is a change, not a check.
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
