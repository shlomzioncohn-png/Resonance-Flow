"""Acceptance tests for basic task-field extraction (#350).

The path of this file is the one named in the issue, so the command in the
issue keeps working:

    pytest tests/test_basic_extraction.py -v
"""

import json
from uuid import UUID

from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.extraction.assembly import to_task_creation_requested
from mind.extraction.config import ExtractionConfig
from mind.extraction.extractor import (
    REASON_INVALID_OUTPUT,
    REASON_PROVIDER_ERROR,
    BasicExtractor,
    ExtractionFailure,
    ExtractionSuccess,
)
from mind.extraction.providers.base import LLMRejected, LLMTimeout
from tests.extraction.fakes import ScriptedProvider

TENANT_ID = UUID("11111111-1111-1111-1111-111111111111")

EMAIL_TEXT = (
    "Hi team, please send the Q3 revenue report to the board before Friday. "
    "It is blocked until finance closes the quarter. "
    "Thread: https://mail.example.com/thread/8821"
)

GOOD_ANSWER = json.dumps(
    {
        "title": "Send the Q3 revenue report to the board",
        "description": "Send the Q3 revenue report to the board before Friday.",
        "source_ref": "https://mail.example.com/thread/8821",
        "has_dependencies": True,
    }
)


def extractor(*script: str | Exception, **config: int) -> BasicExtractor:
    """A BasicExtractor over a scripted provider, with instant backoff."""

    return BasicExtractor(
        provider=ScriptedProvider(*script),
        config=ExtractionConfig(**config) if config else None,
        sleep=lambda _seconds: None,
    )


# --- clean extraction -------------------------------------------------------


def test_clean_extraction_produces_every_required_field() -> None:
    result = extractor(GOOD_ANSWER).extract(EMAIL_TEXT, SourceType.EMAIL)

    assert isinstance(result, ExtractionSuccess)
    assert result.fields.title == "Send the Q3 revenue report to the board"
    assert result.fields.description is not None
    assert result.fields.source_ref == "https://mail.example.com/thread/8821"
    assert result.fields.has_dependencies is True
    assert result.source_type is SourceType.EMAIL
    assert 0.0 <= result.confidence.value <= 1.0
    assert result.schema_attempts == 1


def test_source_type_is_carried_forward_not_asked_of_the_model() -> None:
    result = extractor(GOOD_ANSWER).extract(EMAIL_TEXT, SourceType.WHATSAPP)

    assert isinstance(result, ExtractionSuccess)
    assert result.source_type is SourceType.WHATSAPP


def test_extraction_output_validates_against_the_contract() -> None:
    result = extractor(GOOD_ANSWER).extract(EMAIL_TEXT, SourceType.EMAIL)
    assert isinstance(result, ExtractionSuccess)

    request = to_task_creation_requested(result, tenant_id=TENANT_ID)

    assert isinstance(request, TaskCreationRequested)
    assert request.tenant_id == TENANT_ID
    assert request.title == result.fields.title
    assert request.source_type is SourceType.EMAIL
    assert request.has_dependencies is True
    assert request.confidence == result.confidence.value
    # The fields owned by #351 and #352 stay unset here.
    assert request.tier is None
    assert request.urgency is None
    assert request.effort_estimate_minutes is None


# --- malformed output -------------------------------------------------------


def test_malformed_response_does_not_crash_the_pipeline() -> None:
    provider_said = ("I think the task is to send a report.", "still prose", "prose again")

    result = extractor(*provider_said).extract(EMAIL_TEXT, SourceType.EMAIL)

    assert isinstance(result, ExtractionFailure)
    assert result.reason == REASON_INVALID_OUTPUT


def test_a_repair_prompt_is_sent_after_invalid_output() -> None:
    provider = ScriptedProvider('{"title": 12, "has_dependencies": false}', GOOD_ANSWER)
    extraction = BasicExtractor(provider=provider, sleep=lambda _seconds: None)

    extraction.extract(EMAIL_TEXT, SourceType.EMAIL)

    assert provider.call_count == 2
    first_user_message, repair_user_message = (call[1] for call in provider.calls)
    assert "Your previous answer was rejected" not in first_user_message
    assert "Your previous answer was rejected" in repair_user_message
    assert '"title": 12' in repair_user_message
    assert "Input should be a valid string" in repair_user_message


def test_hallucinated_extra_field_is_rejected_and_repaired() -> None:
    hallucinated = json.dumps(
        {
            "title": "Send the Q3 revenue report",
            "has_dependencies": False,
            "urgency": 5,
            "effort_estimate_minutes": 30,
        }
    )

    result = extractor(hallucinated, GOOD_ANSWER).extract(EMAIL_TEXT, SourceType.EMAIL)

    assert isinstance(result, ExtractionSuccess)
    assert result.schema_attempts == 2


# --- retry then success -----------------------------------------------------


def test_retry_then_success_lowers_confidence_but_still_succeeds() -> None:
    clean = extractor(GOOD_ANSWER).extract(EMAIL_TEXT, SourceType.EMAIL)
    repaired = extractor("not JSON at all", GOOD_ANSWER).extract(EMAIL_TEXT, SourceType.EMAIL)

    assert isinstance(clean, ExtractionSuccess)
    assert isinstance(repaired, ExtractionSuccess)
    assert repaired.fields == clean.fields
    assert repaired.schema_attempts == 2
    assert repaired.confidence.value < clean.confidence.value
    assert "repair_attempts" in {p.reason for p in repaired.confidence.penalties}


def test_transient_provider_failure_is_retried_without_spending_a_repair() -> None:
    result = extractor(LLMTimeout("too slow"), GOOD_ANSWER).extract(EMAIL_TEXT, SourceType.EMAIL)

    assert isinstance(result, ExtractionSuccess)
    assert result.provider_calls == 2
    # The timeout was not the model failing the schema, so confidence is intact.
    assert result.schema_attempts == 1
    assert result.confidence.penalties == ()


def test_a_rejected_request_is_not_retried() -> None:
    provider = ScriptedProvider(LLMRejected("invalid api key"), GOOD_ANSWER)
    result = BasicExtractor(provider=provider, sleep=lambda _seconds: None).extract(
        EMAIL_TEXT, SourceType.EMAIL
    )

    assert isinstance(result, ExtractionFailure)
    assert result.reason == REASON_PROVIDER_ERROR
    assert provider.call_count == 1


# --- giving up and moving on ------------------------------------------------


def test_the_item_is_abandoned_after_n_attempts() -> None:
    result = extractor("nope", "still nope", "nope again", max_attempts=3).extract(
        EMAIL_TEXT, SourceType.EMAIL
    )

    assert isinstance(result, ExtractionFailure)
    assert result.schema_attempts == 3
    assert result.last_output == "nope again"
    assert result.detail


def test_one_bad_item_does_not_stall_the_batch() -> None:
    provider = ScriptedProvider(
        GOOD_ANSWER,
        "not JSON",
        "still not JSON",
        "never JSON",
        GOOD_ANSWER,
    )
    batch = BasicExtractor(provider=provider, sleep=lambda _seconds: None)

    outcomes = list(
        batch.extract_many(
            [
                (EMAIL_TEXT, SourceType.EMAIL),
                ("something unusable", SourceType.SLACK),
                (EMAIL_TEXT, SourceType.EMAIL),
            ]
        )
    )

    assert [type(outcome) for outcome in outcomes] == [
        ExtractionSuccess,
        ExtractionFailure,
        ExtractionSuccess,
    ]


# --- sources without a reference --------------------------------------------


def test_missing_source_ref_is_a_normal_outcome() -> None:
    answer = json.dumps(
        {
            "title": "Reconcile the warehouse stock count",
            "description": "Recount shelf B and reconcile against the ledger.",
            "source_ref": None,
            "has_dependencies": False,
        }
    )

    result = extractor(answer).extract(
        "Recount shelf B and reconcile against the ledger.", SourceType.SCANNED_DOC
    )

    assert isinstance(result, ExtractionSuccess)
    assert result.fields.source_ref is None
    # A scanned document has no pointer to carry, so nothing is deducted.
    assert "missing_source_ref" not in {p.reason for p in result.confidence.penalties}

    request = to_task_creation_requested(result, tenant_id=TENANT_ID)
    assert request.source_ref is None


def test_placeholder_source_ref_is_normalised_to_none() -> None:
    answer = json.dumps(
        {
            "title": "Reconcile the warehouse stock count",
            "description": "Recount shelf B.",
            "source_ref": "N/A",
            "has_dependencies": False,
        }
    )

    result = extractor(answer).extract("Recount shelf B.", SourceType.MANUAL)

    assert isinstance(result, ExtractionSuccess)
    assert result.fields.source_ref is None
