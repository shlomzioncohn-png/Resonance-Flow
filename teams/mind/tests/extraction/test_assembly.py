"""Which source_ref reaches Inbox (#353).

Inbox requires it (`@NotBlank` on its creation request), and its UI builds the
link back to the source from source_type + source_ref. So it is never empty,
and it is only ever an identifier something can be found by:

1. Bridge's own source_ref — the source system's id for the item, the only
   value a link can be built from;
2. otherwise `bridge:<Bridge's correlation_id>`, always present, and the prefix
   tells the UI there is no external link to build.

The source_ref extraction found in the content is not in the chain: it is a
pointer the text mentions, not the item's own id, and as a link it would lead
somewhere else.
"""

from uuid import UUID

import pytest
from tests.extraction.fakes import DEADLINE_SETTINGS, WRITTEN_AT, ScriptedProvider, answer_of

from mind.contracts.base_enums import SourceType
from mind.extraction.assembly import to_task_creation_requested
from mind.extraction.extractor import BasicExtractor, ExtractionSuccess

TENANT_ID = UUID("11111111-1111-1111-1111-111111111111")
BRIDGE_ITEM = UUID("6f1c2e9a-3b4d-4c8e-9a1f-2d7e5b0c4a83")


def _extracted(source_ref: str | None) -> ExtractionSuccess:
    answer = answer_of(
        {
            "title": "Send the Q3 revenue report",
            "description": None,
            "source_ref": source_ref,
            "has_dependencies": False,
        }
    )
    text = "Please send the Q3 revenue report. See JIRA-123 for the numbers."
    outcome = BasicExtractor(ScriptedProvider(answer), sleep=lambda _: None).extract(
        text, SourceType.SLACK
    )
    assert isinstance(outcome, ExtractionSuccess)
    return outcome


def _source_ref(extracted: str | None, bridge: str | None) -> str | None:
    [proposal] = to_task_creation_requested(
        _extracted(extracted),
        tenant_id=TENANT_ID,
        bridge_source_ref=bridge,
        bridge_correlation_id=BRIDGE_ITEM,
        written_at=WRITTEN_AT,
        deadline_settings=DEADLINE_SETTINGS,
    )
    return proposal.source_ref


def test_bridges_source_ref_is_sent_as_it_is() -> None:
    assert _source_ref(None, "C024BE91L:1728900000.000100") == "C024BE91L:1728900000.000100"


def test_without_one_the_bridge_item_id_is_sent_with_its_prefix() -> None:
    assert _source_ref(None, None) == f"bridge:{BRIDGE_ITEM}"


@pytest.mark.parametrize("blank", ["", "   "])
def test_a_blank_bridge_source_ref_counts_as_none(blank: str) -> None:
    """Inbox's @NotBlank rejects these too, and the task would never be created."""

    assert _source_ref(None, blank) == f"bridge:{BRIDGE_ITEM}"


def test_spacing_around_bridges_source_ref_is_dropped() -> None:
    assert _source_ref(None, "  C024BE91L:1728900000.000100 ") == "C024BE91L:1728900000.000100"


def test_what_extraction_found_never_becomes_the_source_ref() -> None:
    """JIRA-123 is mentioned in a Slack message; a Slack link to it is broken."""

    assert _source_ref("JIRA-123", None) == f"bridge:{BRIDGE_ITEM}"


def test_bridges_source_ref_wins_over_what_extraction_found() -> None:
    assert _source_ref("JIRA-123", "C024BE91L:1728900000.000100") == "C024BE91L:1728900000.000100"


def test_the_fallback_is_the_prefix_and_a_canonical_uuid() -> None:
    prefix, _, rest = _source_ref(None, None).partition(":")  # type: ignore[union-attr]

    assert prefix == "bridge"
    assert rest == str(UUID(rest))  # parses, and is already in canonical form
    assert UUID(rest) == BRIDGE_ITEM


@pytest.mark.parametrize("extracted", [None, "JIRA-123"])
@pytest.mark.parametrize("bridge", [None, "", "  ", "C024BE91L:1728900000.000100"])
def test_the_source_ref_is_never_blank(extracted: str | None, bridge: str | None) -> None:
    source_ref = _source_ref(extracted, bridge)

    assert source_ref is not None
    assert source_ref.strip()
