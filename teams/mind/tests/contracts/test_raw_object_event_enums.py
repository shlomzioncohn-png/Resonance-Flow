import pytest

from mind.contracts import ObjectRole, SourceType


def test_meeting_platforms_are_distinct_source_types() -> None:
    assert SourceType("ZOOM") is SourceType.ZOOM
    assert SourceType("BIGBLUEBUTTON") is SourceType.BIGBLUEBUTTON


def test_meeting_is_kept_until_inbox_drops_it() -> None:
    # Bridge no longer sends MEETING, but the task output still uses it.
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
