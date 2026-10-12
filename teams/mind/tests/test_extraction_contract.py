"""Tests for mind.evaluation.extraction_contract.ExtractedItem.

Covers: the required/optional fields (and that there are exactly two), the
non-empty constraints on `title` and `description`, `to_comparison_text` (both
branches), that the extraction pipeline's real models — `ExtractedFields` and
`TaskCreationRequested` — validate as-is, including a meeting's output whose
`source_ref` is `None`, and that `ExtractedItem`s feed
the eval end to end through `evaluate_extracted_items`, a thin wrapper over
`evaluate_extraction`.
"""

from uuid import UUID

import pytest
from pydantic import ValidationError

from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.contracts.task_enums import RoleContext
from mind.evaluation.extraction_contract import ExtractedItem
from mind.evaluation.extraction_eval import (
    evaluate_extracted_items,
    evaluate_extraction,
    placeholder_similarity,
)
from mind.extraction.models import ExtractedFields


def test_minimal_valid_item_has_only_a_title():
    item = ExtractedItem(title="PM to book a meeting room")

    assert item.title == "PM to book a meeting room"
    assert item.description is None


def test_contract_has_exactly_the_fields_the_eval_reads():
    # Pins the shape Yonit approves: a field added or removed here is a contract
    # change and must fail a test, not slip in silently.
    assert set(ExtractedItem.model_fields) == {"title", "description"}


def test_title_is_required():
    with pytest.raises(ValidationError):
        ExtractedItem()  # type: ignore[call-arg]


def test_title_must_be_non_empty():
    with pytest.raises(ValidationError):
        ExtractedItem(title="")


def test_comparison_text_joins_title_and_description():
    item = ExtractedItem(
        title="PM to research voice-recognition vendors",
        description="find companies that can help the Industrial Designer",
    )

    assert item.to_comparison_text() == (
        "PM to research voice-recognition vendors. "
        "find companies that can help the Industrial Designer"
    )


def test_comparison_text_is_title_only_without_description():
    item = ExtractedItem(title="PM to book a meeting room")

    assert item.to_comparison_text() == "PM to book a meeting room"


def test_empty_description_is_rejected():
    # "No description" has exactly one spelling: None. An empty string would be a
    # second way to say the same thing.
    with pytest.raises(ValidationError, match="description"):
        ExtractedItem(title="PM to book a meeting room", description="")


# The two tests below build the extraction pipeline's *real* models, not
# hand-written dicts that only look like them: a renamed or retyped field on
# either side fails here instead of surfacing when the eval first runs on real
# output.


def test_the_pipelines_extracted_fields_for_a_meeting_validate():
    # ExtractedFields is what the eval reads (ExtractionSuccess.fields, before
    # assembly drops item_key). For a meeting, source_ref is None — a transcript
    # carries no pointer — and that must not be rejected: the eval holds the
    # meeting id itself.
    fields = ExtractedFields(
        title="PM to present the components concept",
        description="at the next meeting",
        source_ref=None,
        has_dependencies=False,
    )

    item = ExtractedItem.model_validate(fields.model_dump())

    assert item.to_comparison_text() == (
        "PM to present the components concept. at the next meeting"
    )
    assert not hasattr(item, "source_ref")
    assert not hasattr(item, "has_dependencies")


def test_the_pipelines_blank_description_arrives_as_none():
    # The pipeline maps the model's ways of writing "nothing" ("N/A", "", …) to
    # None, so description's min_length=1 never rejects its output.
    fields = ExtractedFields(
        title="PM to book a meeting room", description="N/A", has_dependencies=False
    )

    item = ExtractedItem.model_validate(fields.model_dump())

    assert item.description is None


def test_a_task_creation_requested_validates_and_extra_fields_are_dropped():
    # The pipeline's outbound contract feeds this model directly: the eval keeps
    # its two fields, everything else is ignored (extra="ignore") — including
    # role_context (classification fills it, not extraction) and source_ref
    # (not the meeting id).
    task = TaskCreationRequested(
        tenant_id=UUID(int=1),
        title="PM to present the components concept",
        source_type=SourceType.MEETING,
        source_ref=None,
        role_context=RoleContext.MANAGER,
        urgency=3,
        has_dependencies=False,
        confidence=0.9,
    )

    item = ExtractedItem.model_validate(task.model_dump())

    assert item.title == "PM to present the components concept"
    assert item.description is None
    assert not hasattr(item, "source_ref")
    assert not hasattr(item, "role_context")
    assert not hasattr(item, "urgency")


_ITEMS = [
    ExtractedItem(title="Industrial Designer to present the components concept"),
    ExtractedItem(
        title="Team to decide on the remote layout",
        description="before the next meeting",
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
