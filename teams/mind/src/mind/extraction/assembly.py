"""Assembling an extraction into the outbound contract.

This is the single place where what the model guessed meets what we already
knew. `tenant_id` and `source_type` come from the intake event, `confidence`
comes from our own scorer, and the rest comes from the LLM — keeping that
boundary in one function is what stops an identity or a source type from ever
being model-generated.

The classification fields (tier, urgency, role_context) and the effort
estimate (#352) are filled in here when #351's switch was on and left as None
when it was off. The estimate's confidence is derived from its basis, in
`confidence.py`, and never touches the overall `confidence`.

`source_ref` comes from Bridge, never from the model (#353): Inbox requires it,
and its UI builds the link to the source from source_type + source_ref.

`deadline` (#960) is resolved here, the one place that has both the model's
answer and when the text was written: the model reports what the content
says, and `resolve_deadline` turns it into a moment.
"""

import calendar
import logging
from datetime import UTC, date, datetime, time, timedelta
from uuid import UUID

from mind.config import DeadlineSettings
from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.extraction.confidence import effort_confidence
from mind.extraction.deadline import (
    OffsetUnit,
    PeriodEnd,
    at_end_of_day,
    complete_year,
    resolve_offset,
    resolve_period_end,
    resolve_weekday,
)
from mind.extraction.extractor import ExtractedTask, ExtractionSuccess
from mind.extraction.models import ClassificationFields, DeadlineKind, StatedDeadline

logger = logging.getLogger("mind.extraction")

#: Marks a source_ref that is Bridge's id for the item rather than the source
#: system's: there is no external link to build from it.
BRIDGE_REF_PREFIX = "bridge:"


def source_ref_for_inbox(bridge_source_ref: str | None, bridge_correlation_id: UUID) -> str:
    """The source system's id for the item when Bridge has it, else Bridge's own.

    Never blank: Inbox's creation request rejects a blank one (`@NotBlank`)
    and the task would never be created. What extraction read from the
    content is deliberately not a candidate — a pointer the text mentions is
    not the item's own id, and a link built from it leads somewhere else.
    """

    if bridge_source_ref is not None and bridge_source_ref.strip():
        return bridge_source_ref.strip()
    return f"{BRIDGE_REF_PREFIX}{bridge_correlation_id}"


def to_task_creation_requested(
    result: ExtractionSuccess,
    tenant_id: UUID,
    *,
    bridge_source_ref: str | None,
    bridge_correlation_id: UUID,
    written_at: datetime,
    deadline_settings: DeadlineSettings,
) -> list[TaskCreationRequested]:
    """Build one Inbox-facing proposal per extracted task (#690), in order.

    Each is a proposal, not a decision: Inbox is the only team that creates a
    task record and issues a real task id. A text with no task gives an empty
    list, so nothing is sent for it.

    Every task from one item carries that item's `source_ref`: it names where
    the tasks came from, not which task this is. Inbox tells them apart by
    correlation_id, one per task.

    `written_at` is when the text was written, not when Mind read it: the
    event's `source_timestamp`, else its `ingested_at`. "By Friday" means the
    Friday after that moment. Required, not defaulted: a caller that forgot
    it would send every task without a deadline and nothing would say so.
    """

    source_ref = source_ref_for_inbox(bridge_source_ref, bridge_correlation_id)
    return [
        _proposal(task, result.source_type, tenant_id, source_ref, written_at, deadline_settings)
        for task in result.tasks
    ]


def resolve_deadline(
    stated: StatedDeadline, written_at: datetime, settings: DeadlineSettings
) -> datetime | None:
    """The moment a stated deadline points to, in UTC, or None if there is none.

    Resolved in the configured zone, so "today" is the writer's today. A year
    or a time the content wrote is used as written; without a time, the end
    of the working day. A date no calendar has (February 30, or a February 29
    with no leap year near) is None and logged: the model read it wrong, or
    the content says it wrong, and either way there is no moment to send.
    """

    local = written_at.astimezone(settings.zone)
    end_of_day, working_days = settings.end_of_day, settings.weekday_numbers
    moment: datetime | None
    match stated.kind:
        case DeadlineKind.DATE if stated.month and stated.day:
            moment = _on_date(stated, stated.month, stated.day, local, settings)
        case DeadlineKind.OFFSET if stated.amount and stated.unit:
            moment = resolve_offset(
                stated.amount, stated.unit, local, end_of_day=end_of_day, working_days=working_days
            )
        case DeadlineKind.WEEKDAY if stated.weekday:
            moment = resolve_weekday(
                stated.weekday.number, stated.next_week, local, end_of_day=end_of_day
            )
        case DeadlineKind.PERIOD_END if stated.period:
            moment = resolve_period_end(
                stated.period, local, end_of_day=end_of_day, working_days=working_days
            )
        case _:
            # The model's own validator makes every kind carry what it needs.
            moment = None
    if moment is None:
        logger.warning(
            "extraction: the %s deadline names no moment; recording it as null", stated.kind.value
        )
        return None
    if stated.time_of_day is not None and not _on_the_clock(stated):
        # A time the content names ("by 12:30", "by 09:00 tomorrow") replaces
        # the end of the working day, on whichever day the kind landed.
        moment = datetime.combine(moment.date(), stated.time_of_day, tzinfo=local.tzinfo)
        if moment <= local:
            moment = _next_one_still_ahead(stated, moment, stated.time_of_day)
    return moment.astimezone(UTC)


def _next_one_still_ahead(stated: StatedDeadline, passed: datetime, at: time) -> datetime:
    """Where a named time already gone today points instead, if anywhere.

    Only a kind that comes round again moves: "by Thursday 10:00" or "by the
    end of the week, 12:00" written on Thursday afternoon cannot mean a moment
    already gone, so it is next week's (decision 9, among those still ahead);
    "by the end of the month, 12:00" on its last working day after noon is the
    month's last day, like decision 10. A day that does not come round stays
    missed: "by 12:30" written at 15:00, or an explicit date.
    """

    period = stated.period if stated.kind is DeadlineKind.PERIOD_END else None
    if stated.kind is DeadlineKind.WEEKDAY or period is PeriodEnd.WEEK:
        return datetime.combine(passed.date() + timedelta(days=7), at, tzinfo=passed.tzinfo)
    if period is PeriodEnd.MONTH:
        last_day = passed.date().replace(day=calendar.monthrange(passed.year, passed.month)[1])
        if last_day > passed.date():
            return datetime.combine(last_day, at, tzinfo=passed.tzinfo)
    return passed


def _on_the_clock(stated: StatedDeadline) -> bool:
    # "Within 3 hours" is already an exact moment; a time beside it adds nothing.
    return stated.kind is DeadlineKind.OFFSET and stated.unit in (
        OffsetUnit.MINUTES,
        OffsetUnit.HOURS,
    )


def _on_date(
    stated: StatedDeadline, month: int, day: int, local: datetime, settings: DeadlineSettings
) -> datetime | None:
    if stated.year is None:
        on = complete_year(month, day, local.date())
    else:
        try:
            on = date(stated.year, month, day)
        except ValueError:
            on = None
    if on is None:
        return None
    return at_end_of_day(on, local, settings.end_of_day)


def _proposal(
    task: ExtractedTask,
    source_type: SourceType,
    tenant_id: UUID,
    source_ref: str,
    written_at: datetime,
    deadline_settings: DeadlineSettings,
) -> TaskCreationRequested:
    """One task's proposal: its own fields and confidence, the shared rest."""

    # With classification switched off the fields object genuinely has no
    # `tier` attribute — reading one raises rather than returning None — so
    # the shape has to be checked before it is read. An isinstance narrowing
    # rather than `getattr(..., "tier", None)`: here the field names are real
    # attributes that mypy verifies, where a misspelled string would quietly
    # return None for ever.
    classification = task.fields if isinstance(task.fields, ClassificationFields) else None
    stated = classification.deadline if classification else None

    return TaskCreationRequested(
        tenant_id=tenant_id,
        title=task.fields.title,
        description=task.fields.description,
        source_type=source_type,
        source_ref=source_ref,
        has_dependencies=task.fields.has_dependencies,
        confidence=task.confidence.value,
        # None here means two different things that happen to deserve the same
        # answer: nobody asked for a classification, or the model was asked and
        # honestly declined to guess. Both leave Inbox with an empty field,
        # which is exactly the contract's default.
        tier=classification.tier if classification else None,
        urgency=classification.urgency if classification else None,
        role_context=classification.role_context if classification else None,
        effort_estimate_minutes=(
            classification.effort_estimate_minutes if classification else None
        ),
        effort_estimate_confidence=(
            effort_confidence(classification.effort_estimate_basis) if classification else None
        ),
        deadline=(
            resolve_deadline(stated, written_at, deadline_settings) if stated is not None else None
        ),
    )
