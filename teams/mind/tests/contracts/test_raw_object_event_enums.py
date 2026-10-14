import pytest

from mind.contracts import ObjectRole, RawSourceType, SourceType


def test_raw_source_type_is_exactly_the_wire_list() -> None:
    # The list agreed with Bridge in #31.
    assert {source.value for source in RawSourceType} == {
        "EMAIL",
        "SLACK",
        "WHATSAPP",
        "DRIVE",
        "CALENDAR",
        "ZOOM",
        "BIGBLUEBUTTON",
        "SCANNED_DOC",
    }


def test_task_source_type_does_not_take_the_meeting_platforms_yet() -> None:
    # The task output follows Inbox, which still has MEETING only.
    assert "ZOOM" not in {source.value for source in SourceType}
    assert "BIGBLUEBUTTON" not in {source.value for source in SourceType}
    assert SourceType("MEETING") is SourceType.MEETING


def test_object_role_matches_the_wire_values() -> None:
    assert {role.value for role in ObjectRole} == {
        "BODY",
        "ATTACHMENT",
        "TRANSCRIPT",
        "RECORDING",
        "SOURCE_METADATA",
    }


def test_rejects_unknown_object_role() -> None:
    with pytest.raises(ValueError):
        ObjectRole("THUMBNAIL")
