"""Turning the TaskCreationRequested contract into the message Inbox reads (#353).

Not a second payload shape: the contract is Mind's (`TaskCreationRequested`)
and the wire format is the registered one (`shared/proto`), and this only
translates between them. What needs more than a copy:

- ids are UUIDs here and strings there;
- enums carry a prefix there (`OPERATIONAL` -> `TASK_TIER_OPERATIONAL`);
- a deadline is a datetime here and a Timestamp there;
- None here must stay *unset* there. In protobuf an unset field reads as 0,
  so None and 0 are only told apart if a None field is never touched — and
  Inbox validates on the difference (`urgency` 0 is rejected, absent is not).
"""

from datetime import UTC, datetime
from uuid import UUID

import pytest

from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.contracts.task_enums import RoleContext, TaskTier
from mind.task_creation.event import to_creation_requested_event

TENANT_ID = UUID("11111111-1111-1111-1111-111111111111")
CORRELATION_ID = UUID("9b2f6c1e-0a4d-4e7b-8c3f-5d1a2e6b7c90")


def _request(**overrides: object) -> TaskCreationRequested:
    fields: dict[str, object] = {
        "tenant_id": TENANT_ID,
        "title": "Send the Q3 revenue report",
        "source_type": SourceType.SLACK,
        "source_ref": "C024BE91L:1728900000.000100",
        "has_dependencies": False,
        "confidence": 0.85,
    }
    fields.update(overrides)
    return TaskCreationRequested(**fields)  # type: ignore[arg-type]


def test_ids_travel_as_canonical_uuid_strings() -> None:
    event = to_creation_requested_event(_request(), CORRELATION_ID)

    assert event.correlation_id == str(CORRELATION_ID)
    assert event.tenant_id == str(TENANT_ID)


def test_plain_fields_are_copied() -> None:
    event = to_creation_requested_event(_request(description="Before Friday."), CORRELATION_ID)

    assert event.title == "Send the Q3 revenue report"
    assert event.description == "Before Friday."
    assert event.source_ref == "C024BE91L:1728900000.000100"


def test_enums_gain_their_prefix() -> None:
    event = to_creation_requested_event(
        _request(tier=TaskTier.OPERATIONAL, role_context=RoleContext.HIGHER_MANAGER),
        CORRELATION_ID,
    )

    assert event.DESCRIPTOR.fields_by_name["tier"].enum_type.values_by_number[
        event.tier
    ].name == "TASK_TIER_OPERATIONAL"
    assert event.DESCRIPTOR.fields_by_name["role_context"].enum_type.values_by_number[
        event.role_context
    ].name == "TASK_ROLE_CONTEXT_HIGHER_MANAGER"
    assert event.DESCRIPTOR.fields_by_name["source_type"].enum_type.values_by_number[
        event.source_type
    ].name == "SOURCE_TYPE_SLACK"


@pytest.mark.parametrize("source_type", list(SourceType))
def test_every_task_side_source_type_exists_on_the_wire(source_type: SourceType) -> None:
    """Fails the day a value is added here before it reaches shared/proto."""

    event = to_creation_requested_event(_request(source_type=source_type), CORRELATION_ID)

    assert event.source_type != 0  # SOURCE_TYPE_UNSPECIFIED


def test_none_stays_unset_rather_than_becoming_zero() -> None:
    event = to_creation_requested_event(_request(), CORRELATION_ID)

    for field in ("description", "tier", "role_context", "urgency", "effort_estimate_minutes", "deadline"):
        assert not event.HasField(field), field


def test_a_real_zero_and_a_real_false_are_still_sent() -> None:
    """The other half of the rule: present-and-zero must not look absent."""

    event = to_creation_requested_event(
        _request(confidence=0.0, has_dependencies=False), CORRELATION_ID
    )

    assert event.HasField("confidence") and event.confidence == 0.0
    assert event.HasField("has_dependencies") and event.has_dependencies is False


def test_set_optionals_are_sent() -> None:
    deadline = datetime(2026, 10, 16, 15, 0, tzinfo=UTC)
    event = to_creation_requested_event(
        _request(urgency=4, effort_estimate_minutes=60, deadline=deadline), CORRELATION_ID
    )

    assert event.urgency == 4
    assert event.effort_estimate_minutes == 60
    assert event.deadline.ToDatetime(tzinfo=UTC) == deadline


def test_what_mind_has_no_source_for_is_left_to_inbox() -> None:
    """Inbox fills status itself; Mind has no project, creator or event id to give."""

    event = to_creation_requested_event(_request(), CORRELATION_ID)

    for field in ("event_id", "project_id", "status", "created_by_user_id"):
        assert not event.HasField(field), field


def test_no_assignees_are_sent_even_when_candidates_exist() -> None:
    """A suggestion is not an assignment (agreed with Inbox)."""

    request = _request(
        candidate_assignees=[
            {"assignee_type": "USER", "id": "22222222-2222-2222-2222-222222222222", "confidence": 0.7}
        ]
    )
    event = to_creation_requested_event(request, CORRELATION_ID)

    assert len(event.assignees) == 0


@pytest.mark.parametrize("blank", [None, "", "   "])
def test_a_blank_source_ref_fails_here_rather_than_at_inbox(blank: str | None) -> None:
    """Inbox rejects it (@NotBlank) into its error topic, the task is never
    created, and Mind would wait for a task.created that never comes.

    Assembly guarantees a source_ref, so a blank one here is a bug in Mind,
    and the place to find it is here, before anything is sent.
    """

    with pytest.raises(ValueError, match="source_ref"):
        to_creation_requested_event(_request(source_ref=blank), CORRELATION_ID)


@pytest.mark.parametrize("blank", ["", "   "])
def test_a_blank_title_fails_here_rather_than_at_inbox(blank: str) -> None:
    """Inbox requires it as it requires source_ref (@NotBlank). The contract
    only demands one character, so a title of spaces would pass here and be
    rejected there — in silence, found ten minutes later as an overdue request."""

    request = _request()
    blank_title = request.model_copy(update={"title": blank})

    with pytest.raises(ValueError, match="title"):
        to_creation_requested_event(blank_title, CORRELATION_ID)
