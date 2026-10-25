"""Acceptance tests for basic task-field extraction (#350).

The path of this file is the one named in the issue, so the command in the
issue keeps working:

    pytest tests/test_basic_extraction.py -v
"""

import logging
from uuid import UUID

import pytest

from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.extraction.assembly import to_task_creation_requested
from mind.extraction.config import ExtractionConfig
from mind.extraction.extractor import (
    REASON_INVALID_OUTPUT,
    REASON_PROVIDER_ERROR,
    BasicExtractor,
    ExtractionFailure,
    ExtractionItem,
    ExtractionSuccess,
)
from mind.extraction.providers.base import LLMRejected, LLMTimeout
from mind.extraction.providers.pricing import estimate_cost
from mind.observability.tracking import (
    CallKind,
    CallStatus,
    _reset_for_tests,
    get_recent_calls,
)
from tests.extraction.fakes import (
    DEADLINE_SETTINGS,
    WRITTEN_AT,
    ScriptedProvider,
    answer_of,
    only,
)

TENANT_ID = UUID("11111111-1111-1111-1111-111111111111")
BRIDGE_ITEM = UUID("6f1c2e9a-3b4d-4c8e-9a1f-2d7e5b0c4a83")

EMAIL_TEXT = (
    "Hi team, please send the Q3 revenue report to the board before Friday. "
    "It is blocked until finance closes the quarter. "
    "Thread: https://mail.example.com/thread/8821"
)

GOOD_ANSWER = answer_of(
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
    assert only(result).fields.title == "Send the Q3 revenue report to the board"
    assert only(result).fields.description is not None
    assert only(result).fields.source_ref == "https://mail.example.com/thread/8821"
    assert only(result).fields.has_dependencies is True
    assert result.source_type is SourceType.EMAIL
    assert 0.0 <= only(result).confidence.value <= 1.0
    assert result.schema_attempts == 1


def test_source_type_is_carried_forward_not_asked_of_the_model() -> None:
    result = extractor(GOOD_ANSWER).extract(EMAIL_TEXT, SourceType.WHATSAPP)

    assert isinstance(result, ExtractionSuccess)
    assert result.source_type is SourceType.WHATSAPP


def test_extraction_output_validates_against_the_contract() -> None:
    result = extractor(GOOD_ANSWER).extract(EMAIL_TEXT, SourceType.EMAIL)
    assert isinstance(result, ExtractionSuccess)

    [request] = to_task_creation_requested(
        result,
        tenant_id=TENANT_ID,
        bridge_source_ref=None,
        bridge_correlation_id=BRIDGE_ITEM,
        written_at=WRITTEN_AT,
        deadline_settings=DEADLINE_SETTINGS,
    )

    assert isinstance(request, TaskCreationRequested)
    assert request.tenant_id == TENANT_ID
    assert request.title == only(result).fields.title
    assert request.source_type is SourceType.EMAIL
    assert request.has_dependencies is True
    assert request.confidence == only(result).confidence.value
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
    provider = ScriptedProvider(answer_of({"title": 12, "has_dependencies": False}), GOOD_ANSWER)
    extraction = BasicExtractor(provider=provider, sleep=lambda _seconds: None)

    extraction.extract(EMAIL_TEXT, SourceType.EMAIL)

    assert provider.call_count == 2
    first_user_message, repair_user_message = (call[1] for call in provider.calls)
    assert "Your previous answer was rejected" not in first_user_message
    assert "Your previous answer was rejected" in repair_user_message
    assert '"title": 12' in repair_user_message
    assert "Input should be a valid string" in repair_user_message


def test_hallucinated_extra_field_is_rejected_and_repaired() -> None:
    hallucinated = answer_of(
        {
            "title": "Send the Q3 revenue report",
            "has_dependencies": False,
            # A key no stage asks for. urgency and the effort estimate used to
            # stand in here, until #351 and #352 made them legitimate answers.
            "priority": "high",
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
    assert only(repaired).fields == only(clean).fields
    assert repaired.schema_attempts == 2
    assert only(repaired).confidence.value < only(clean).confidence.value
    assert "repair_attempts" in {p.reason for p in only(repaired).confidence.penalties}


def test_transient_provider_failure_is_retried_without_spending_a_repair() -> None:
    result = extractor(LLMTimeout("too slow"), GOOD_ANSWER).extract(EMAIL_TEXT, SourceType.EMAIL)

    assert isinstance(result, ExtractionSuccess)
    assert result.provider_calls == 2
    # The timeout was not the model failing the schema, so confidence is intact.
    assert result.schema_attempts == 1
    assert only(result).confidence.penalties == ()


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
                ExtractionItem(EMAIL_TEXT, SourceType.EMAIL),
                ExtractionItem("something unusable", SourceType.SLACK),
                ExtractionItem(EMAIL_TEXT, SourceType.EMAIL),
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
    answer = answer_of(
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
    assert only(result).fields.source_ref is None
    # A scanned document has no pointer to carry, so nothing is deducted.
    assert "missing_source_ref" not in {p.reason for p in only(result).confidence.penalties}

    # Inbox requires a source_ref, so the task carries Bridge's id for the
    # item instead (#353); nothing from the content is passed off as one.
    [request] = to_task_creation_requested(
        result,
        tenant_id=TENANT_ID,
        bridge_source_ref=None,
        bridge_correlation_id=BRIDGE_ITEM,
        written_at=WRITTEN_AT,
        deadline_settings=DEADLINE_SETTINGS,
    )
    assert request.source_ref == f"bridge:{BRIDGE_ITEM}"


def test_placeholder_source_ref_is_normalised_to_none() -> None:
    answer = answer_of(
        {
            "title": "Reconcile the warehouse stock count",
            "description": "Recount shelf B.",
            "source_ref": "N/A",
            "has_dependencies": False,
        }
    )

    result = extractor(answer).extract("Recount shelf B.", SourceType.MANUAL)

    assert isinstance(result, ExtractionSuccess)
    assert only(result).fields.source_ref is None


# --- every call is recorded, including the ones that failed ---------------


def test_a_failed_call_is_recorded_too() -> None:
    """`tracking.py` says every model call goes through record_call.

    The ones that disappear are the slowest we make: a timeout waits the full
    deadline before raising. Leaving them out makes the latency record look
    better than the service actually is.
    """

    _reset_for_tests()
    provider = ScriptedProvider(LLMTimeout("too slow"), LLMTimeout("again"), LLMTimeout("again"))

    outcome = BasicExtractor(provider, sleep=lambda _: None).extract(EMAIL_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionFailure)
    assert len(get_recent_calls()) == outcome.provider_calls


def test_a_failed_call_is_recorded_under_the_model_it_was_sent_to() -> None:
    """The same key as a success, so a model's failures land on its own row (#479)."""

    _reset_for_tests()
    provider = ScriptedProvider(LLMTimeout("too slow"), LLMTimeout("again"), LLMTimeout("again"))

    BasicExtractor(provider, sleep=lambda _: None).extract(EMAIL_TEXT, SourceType.EMAIL)

    record = get_recent_calls()[0]
    assert record.model_name == provider.model
    assert record.duration_ms >= 0.0
    assert (record.kind, record.status) == (CallKind.LLM, CallStatus.ERROR)
    assert record.cost is None  # no usage reported: unknown, not free


def test_a_run_that_mixes_failure_and_success_records_both() -> None:
    _reset_for_tests()
    provider = ScriptedProvider(LLMTimeout("too slow"), GOOD_ANSWER)

    outcome = BasicExtractor(provider, sleep=lambda _: None).extract(EMAIL_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionSuccess)
    assert len(get_recent_calls()) == 2


def test_a_successful_call_is_recorded_with_its_tokens_and_dollar_cost() -> None:
    """#479: the cost report totals tokens and dollars per model."""

    _reset_for_tests()
    provider = ScriptedProvider(GOOD_ANSWER, model="deepseek-chat")

    BasicExtractor(provider, sleep=lambda _: None).extract(EMAIL_TEXT, SourceType.EMAIL)

    [record] = get_recent_calls()
    assert (record.kind, record.status) == (CallKind.LLM, CallStatus.SUCCESS)
    assert record.completion_tokens == len(GOOD_ANSWER)  # what the scripted provider reports
    assert record.prompt_tokens is not None and record.prompt_tokens > 0
    assert record.cost == pytest.approx(
        estimate_cost("deepseek-chat", record.prompt_tokens, record.completion_tokens)
    )


def test_a_call_to_an_unpriced_model_is_recorded_as_unpriced_not_free() -> None:
    """The usage is still recorded; only the price is unknown."""

    _reset_for_tests()
    provider = ScriptedProvider(GOOD_ANSWER, model="a-model-with-no-price")

    BasicExtractor(provider, sleep=lambda _: None).extract(EMAIL_TEXT, SourceType.EMAIL)

    [record] = get_recent_calls()
    assert record.cost is None
    assert record.completion_tokens == len(GOOD_ANSWER)


# --- a failed item has to be findable -------------------------------------

OBJECT_KEY = "tenant-7/email/2026-10-11/4471-body.eml"


def test_a_successful_outcome_carries_the_key_it_came_from() -> None:
    provider = ScriptedProvider(GOOD_ANSWER)

    outcome = BasicExtractor(provider, sleep=lambda _: None).extract(
        EMAIL_TEXT, SourceType.EMAIL, item_key=OBJECT_KEY
    )

    assert outcome.item_key == OBJECT_KEY


def test_a_failed_outcome_carries_it_too() -> None:
    """Without this, "recorded as failed" records nothing anyone can act on."""

    provider = ScriptedProvider("not json", "still not json", "nope")

    outcome = BasicExtractor(provider, sleep=lambda _: None).extract(
        EMAIL_TEXT, SourceType.EMAIL, item_key=OBJECT_KEY
    )

    assert isinstance(outcome, ExtractionFailure)
    assert outcome.item_key == OBJECT_KEY


def test_the_log_line_names_the_item(caplog: pytest.LogCaptureFixture) -> None:
    """A run is audited from the log, not from the returned objects."""

    provider = ScriptedProvider("not json", "still not json", "nope")

    with caplog.at_level(logging.ERROR, logger="mind.extraction"):
        BasicExtractor(provider, sleep=lambda _: None).extract(
            EMAIL_TEXT, SourceType.EMAIL, item_key=OBJECT_KEY
        )

    assert OBJECT_KEY in caplog.text


def test_the_key_is_optional_for_callers_without_an_intake_event() -> None:
    """The smoke script and the tests run text that never came from Bridge."""

    provider = ScriptedProvider(GOOD_ANSWER)

    outcome = BasicExtractor(provider, sleep=lambda _: None).extract(EMAIL_TEXT, SourceType.EMAIL)

    assert outcome.item_key is None


def test_a_batch_keeps_each_outcome_attached_to_its_own_item() -> None:
    provider = ScriptedProvider("not json", "still not json", "nope", GOOD_ANSWER)
    batch = BasicExtractor(provider, sleep=lambda _: None)

    outcomes = list(
        batch.extract_many(
            [
                ExtractionItem(EMAIL_TEXT, SourceType.EMAIL, "first-key"),
                ExtractionItem(EMAIL_TEXT, SourceType.SLACK, "second-key"),
            ]
        )
    )

    assert [o.item_key for o in outcomes] == ["first-key", "second-key"]
    assert isinstance(outcomes[0], ExtractionFailure)
    assert isinstance(outcomes[1], ExtractionSuccess)
