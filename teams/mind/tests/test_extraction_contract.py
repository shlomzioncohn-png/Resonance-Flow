"""Tests for mind.evaluation.extraction_contract.ExtractedItem.

Covers: the required/optional fields (and that there are exactly three), the
non-empty constraints on `title`, `description` and `source_ref`,
`to_comparison_text` (both branches), that a full
`TaskCreationRequested`-shaped payload validates as-is while a Bridge-path one
(`source_ref=None`) is rejected, and that `ExtractedItem`s feed the eval end to end
through `evaluate_extracted_items`, a thin wrapper over `evaluate_extraction`.
"""

import pytest
from pydantic import ValidationError

from mind.evaluation.extraction_contract import ExtractedItem
from mind.evaluation.extraction_eval import (
    evaluate_extracted_items,
    evaluate_extraction,
    placeholder_similarity,
)


def test_minimal_valid_item_has_only_title_and_source_ref():
    item = ExtractedItem(title="PM to book a meeting room", source_ref="IS1008b")

    assert item.title == "PM to book a meeting room"
    assert item.source_ref == "IS1008b"
    assert item.description is None


def test_contract_has_exactly_the_fields_the_eval_reads():
    # Pins the shape Yonit approves: a field added or removed here is a contract
    # change and must fail a test, not slip in silently.
    assert set(ExtractedItem.model_fields) == {"title", "description", "source_ref"}


def test_title_is_required():
    with pytest.raises(ValidationError):
        ExtractedItem(source_ref="IS1008b")  # type: ignore[call-arg]


def test_title_must_be_non_empty():
    with pytest.raises(ValidationError):
        ExtractedItem(title="", source_ref="IS1008b")


def test_source_ref_is_required():
    with pytest.raises(ValidationError):
        ExtractedItem(title="PM to book a meeting room")  # type: ignore[call-arg]


def test_source_ref_must_be_non_empty():
    # The eval cannot join an item with no meeting to any annotation set.
    with pytest.raises(ValidationError):
        ExtractedItem(title="PM to book a meeting room", source_ref="")


def test_comparison_text_joins_title_and_description():
    item = ExtractedItem(
        title="PM to research voice-recognition vendors",
        description="find companies that can help the Industrial Designer",
        source_ref="IS1008b",
    )

    assert item.to_comparison_text() == (
        "PM to research voice-recognition vendors. "
        "find companies that can help the Industrial Designer"
    )


def test_comparison_text_is_title_only_without_description():
    item = ExtractedItem(title="PM to book a meeting room", source_ref="IS1008b")

    assert item.to_comparison_text() == "PM to book a meeting room"


def test_empty_description_is_rejected():
    # "No description" has exactly one spelling: None. An empty string would be a
    # second way to say the same thing.
    with pytest.raises(ValidationError, match="description"):
        ExtractedItem(
            title="PM to book a meeting room", description="", source_ref="IS1008b"
        )


def test_a_full_task_payload_validates_and_extra_fields_are_dropped():
    # A TaskCreationRequested-shaped dict feeds this model directly: the eval
    # keeps its three fields, everything else is ignored (extra="ignore") —
    # including role_context, which classification fills, not extraction.
    payload = {
        "title": "PM to present the components concept",
        "description": None,
        "source_ref": "IS1008b",
        "role_context": "MANAGER",
        "source_type": "MEETING",
        "tier": "OPERATIONAL",
        "urgency": 3,
        "confidence": 0.9,
        "has_dependencies": False,
    }

    item = ExtractedItem.model_validate(payload)

    assert item.title == "PM to present the components concept"
    assert not hasattr(item, "role_context")
    assert not hasattr(item, "urgency")


def test_bridge_path_payload_without_source_ref_is_rejected():
    # "Validates as-is" holds for corpus-loader payloads only. The Bridge path
    # carries no meeting id (TaskCreationRequested.source_ref is None there), and
    # the eval cannot join such an item to any annotation set — so it is rejected.
    payload = {
        "title": "PM to present the components concept",
        "source_ref": None,
        "source_type": "MEETING",
        "tier": "OPERATIONAL",
    }

    with pytest.raises(ValidationError, match="source_ref"):
        ExtractedItem.model_validate(payload)


_ITEMS = [
    ExtractedItem(
        title="Industrial Designer to present the components concept",
        source_ref="IS1008b",
    ),
    ExtractedItem(
        title="Team to decide on the remote layout",
        description="before the next meeting",
        source_ref="IS1008b",
    ),
]
_ANNOTATED = [
    "The Industrial Designer will present the components concept.",
    "The Marketing Expert will present the marketing concept.",
]


def test_extracted_items_feed_the_eval_end_to_end():
    report = evaluate_extracted_items(
        _ITEMS, _ANNOTATED, similarity=placeholder_similarity, threshold=0.5
    )

    assert report.true_positives == 1
    # The false positive is reported as its comparison text, description included.
    assert report.false_positives == [
        "Team to decide on the remote layout. before the next meeting"
    ]


def test_evaluate_extracted_items_is_a_thin_wrapper():
    # No matching logic of its own: the same report as evaluate_extraction on the
    # items' comparison texts.
    via_items = evaluate_extracted_items(
        _ITEMS, _ANNOTATED, similarity=placeholder_similarity, threshold=0.5
    )
    via_strings = evaluate_extraction(
        [item.to_comparison_text() for item in _ITEMS],
        _ANNOTATED,
        similarity=placeholder_similarity,
        threshold=0.5,
    )

    assert via_items == via_strings
