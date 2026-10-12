"""Tests for the extraction confidence heuristic."""

from mind.contracts.base_enums import SourceType
from mind.extraction.confidence import (
    BASE_CONFIDENCE,
    CONFIDENCE_CEILING,
    CONFIDENCE_FLOOR,
    PENALTY_MISSING_DESCRIPTION,
    PENALTY_PER_REPAIR,
    ConfidenceBreakdown,
    compute_confidence,
    score_confidence,
    title_grounding,
)
from mind.extraction.models import ExtractedFields

RAW_TEXT = (
    "Hi team, please send the Q3 revenue report to the board before Friday. "
    "Thread: https://mail.example.com/thread/8821"
)


def complete_fields(**overrides: object) -> ExtractedFields:
    """Build a well-formed extraction, overriding one field at a time."""

    payload: dict[str, object] = {
        "title": "Send the Q3 revenue report",
        "description": "Send the Q3 revenue report to the board before Friday.",
        "source_ref": "https://mail.example.com/thread/8821",
        "has_dependencies": False,
    }
    payload.update(overrides)
    return ExtractedFields.model_validate(payload)


def reasons(fields: ExtractedFields, attempts: int = 1) -> set[str]:
    """Names of the deductions applied to an EMAIL extraction of RAW_TEXT."""

    breakdown = score_confidence(fields, SourceType.EMAIL, RAW_TEXT, attempts=attempts)
    return {penalty.reason for penalty in breakdown.penalties}


def test_clean_first_attempt_scores_at_base() -> None:
    breakdown = score_confidence(complete_fields(), SourceType.EMAIL, RAW_TEXT)

    assert breakdown.penalties == ()
    assert breakdown.value == BASE_CONFIDENCE


def test_repair_attempt_takes_its_share_of_the_score() -> None:
    clean = compute_confidence(complete_fields(), SourceType.EMAIL, RAW_TEXT, attempts=1)
    repaired = compute_confidence(complete_fields(), SourceType.EMAIL, RAW_TEXT, attempts=2)

    assert repaired == round(clean * (1.0 - PENALTY_PER_REPAIR), 4)
    assert "repair_attempts" in reasons(complete_fields(), attempts=2)


def test_repairs_compound_without_ever_wiping_the_score() -> None:
    scores = [
        compute_confidence(complete_fields(), SourceType.EMAIL, RAW_TEXT, attempts=n)
        for n in range(1, 8)
    ]

    assert scores == sorted(scores, reverse=True)
    assert all(score > CONFIDENCE_FLOOR for score in scores)


def test_ungrounded_title_lowers_confidence() -> None:
    invented = complete_fields(title="Renegotiate the warehouse lease")

    assert compute_confidence(invented, SourceType.EMAIL, RAW_TEXT) < compute_confidence(
        complete_fields(), SourceType.EMAIL, RAW_TEXT
    )
    assert "title_not_grounded" in reasons(invented)


def test_missing_description_lowers_confidence() -> None:
    assert "missing_description" in reasons(complete_fields(description=None))


def test_missing_source_ref_penalised_only_for_reference_bearing_sources() -> None:
    fields = complete_fields(source_ref=None)

    from_email = score_confidence(fields, SourceType.EMAIL, RAW_TEXT)
    from_manual = score_confidence(fields, SourceType.MANUAL, RAW_TEXT)

    assert "missing_source_ref" in {p.reason for p in from_email.penalties}
    assert "missing_source_ref" not in {p.reason for p in from_manual.penalties}
    assert from_manual.value > from_email.value


def test_short_title_is_penalised() -> None:
    assert "short_title" in reasons(complete_fields(title="Report"))


def test_correlated_signals_cost_less_as_they_pile_up() -> None:
    """The same weight costs fewer points once other signals have fired."""

    alone = score_confidence(
        complete_fields(description=None), SourceType.EMAIL, RAW_TEXT
    )
    piled_on = score_confidence(
        complete_fields(description=None, source_ref=None), SourceType.EMAIL, RAW_TEXT, attempts=2
    )

    def cost(breakdown: ConfidenceBreakdown, reason: str) -> float:
        return next(p.effective for p in breakdown.penalties if p.reason == reason)

    assert cost(alone, "missing_description") < PENALTY_MISSING_DESCRIPTION
    assert cost(piled_on, "missing_description") < cost(alone, "missing_description")


def test_effective_costs_account_for_the_whole_drop() -> None:
    breakdown = score_confidence(
        complete_fields(title="Renegotiate warehouse lease", description=None, source_ref=None),
        SourceType.EMAIL,
        RAW_TEXT,
        attempts=2,
    )

    spent = sum(penalty.effective for penalty in breakdown.penalties)

    assert round(breakdown.base - spent, 3) == round(breakdown.value, 3)


def test_score_always_stays_inside_the_contract_range() -> None:
    worst = complete_fields(title="Renegotiate warehouse lease", description=None, source_ref=None)

    value = compute_confidence(worst, SourceType.EMAIL, "unrelated text", attempts=20)

    assert CONFIDENCE_FLOOR <= value <= CONFIDENCE_CEILING


def test_confidence_is_not_a_constant() -> None:
    scores = {
        compute_confidence(complete_fields(), SourceType.EMAIL, RAW_TEXT),
        compute_confidence(complete_fields(description=None), SourceType.EMAIL, RAW_TEXT),
        compute_confidence(complete_fields(), SourceType.EMAIL, RAW_TEXT, attempts=3),
    }

    assert len(scores) == 3


def test_title_grounding_measures_overlap() -> None:
    assert title_grounding("Send the Q3 revenue report", RAW_TEXT) == 1.0
    assert title_grounding("Renegotiate warehouse lease", RAW_TEXT) == 0.0
    assert 0.0 < title_grounding("Send the warehouse report", RAW_TEXT) < 1.0
