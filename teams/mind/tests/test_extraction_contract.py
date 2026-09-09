"""Tests for mind.evaluation.extraction_contract.ExtractedItem.

Covers: the required/optional fields, the non-empty constraints on `title` and
`source_ref`, `to_comparison_text` (both branches), that a full
`TaskCreationRequested`-shaped payload validates as-is, and that an `ExtractedItem`
feeds `evaluate_extraction` end to end.
"""

import pytest
from pydantic import ValidationError

from mind.evaluation.extraction_contract import ExtractedItem


def test_minimal_valid_item_has_only_title_and_source_ref():
    item = ExtractedItem(title="PM to book a meeting room", source_ref="IS1008b")

    assert item.title == "PM to book a meeting room"
    assert item.source_ref == "IS1008b"
    assert item.description is None
    assert item.role_context is None


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


def test_empty_description_is_treated_as_absent():
    item = ExtractedItem(
        title="PM to book a meeting room", description="", source_ref="IS1008b"
    )

    assert item.to_comparison_text() == "PM to book a meeting room"


def test_a_full_task_payload_validates_and_extra_fields_are_dropped():
    # A TaskCreationRequested-shaped dict feeds this model directly: the eval
    # keeps its four fields, everything else is ignored (extra="ignore").
    payload = {
        "title": "PM to present the components concept",
        "description": None,
        "source_ref": "IS1008b",
        "role_context": "PROJECT_MANAGER",
        "source_type": "MEETING",
        "tier": "OPERATIONAL",
        "urgency": 3,
        "confidence": 0.9,
        "has_dependencies": False,
    }

    item = ExtractedItem.model_validate(payload)

    assert item.title == "PM to present the components concept"
    assert item.role_context == "PROJECT_MANAGER"
    assert not hasattr(item, "urgency")


def test_extracted_item_feeds_evaluate_extraction():
    from mind.evaluation.extraction_eval import default_similarity, evaluate_extraction

    items = [
        ExtractedItem(
            title="Industrial Designer to present the components concept",
            source_ref="IS1008b",
        ),
        ExtractedItem(title="Team to decide on the remote layout", source_ref="IS1008b"),
    ]
    annotated = [
        "The Industrial Designer will present the components concept.",
        "The Marketing Expert will present the marketing concept.",
    ]

    report = evaluate_extraction(
        [item.to_comparison_text() for item in items],
        annotated,
        similarity=default_similarity,
        threshold=0.5,
    )

    assert report.true_positives == 1
    assert report.false_positives == ["Team to decide on the remote layout"]
