import pytest
from pydantic import ValidationError

from mind.contracts import Category, Classification, Sensitivity


def _valid_classification() -> dict[str, object]:
    # Bridge's scanned-doc example from #31.
    return {
        "decided_by": "MODEL",
        "category": {"value": "INVOICE", "confidence": 0.92},
        "quality": {"value": "POOR", "confidence": 0.75},
        "sensitivity": {"value": "LOW", "confidence": 0.85},
        "reason": "vendor invoice, partially blurred scan",
        "classifier": {"model": "gemini-3.8-flash", "prompt_version": "v2"},
    }


def test_accepts_valid_classification() -> None:
    classification = Classification.model_validate(_valid_classification())

    assert classification.category is not None
    assert classification.category.value is Category.INVOICE
    assert classification.category.confidence == 0.92
    assert classification.classifier.prompt_version == "v2"


def test_rejects_confidence_above_one() -> None:
    payload = _valid_classification() | {
        "category": {"value": "INVOICE", "confidence": 92}
    }

    with pytest.raises(ValidationError):
        Classification.model_validate(payload)


def test_unknown_category_becomes_other() -> None:
    payload = _valid_classification() | {
        "category": {"value": "RECEIPT", "confidence": 0.9}
    }

    classification = Classification.model_validate(payload)

    assert classification.category is not None
    assert classification.category.value is Category.OTHER


def test_unknown_sensitivity_becomes_high() -> None:
    # Fail safe: a level we don't know is treated as the most sensitive one.
    payload = _valid_classification() | {
        "sensitivity": {"value": "CRITICAL", "confidence": 0.9}
    }

    classification = Classification.model_validate(payload)

    assert classification.sensitivity.value is Sensitivity.HIGH


def test_unknown_quality_is_ignored() -> None:
    payload = _valid_classification() | {
        "quality": {"value": "BLURRY", "confidence": 0.9}
    }

    assert Classification.model_validate(payload).quality is None


def test_rejects_unknown_decided_by() -> None:
    # Only category, sensitivity and quality have an unknown-value fallback.
    payload = _valid_classification() | {"decided_by": "HUMAN"}

    with pytest.raises(ValidationError):
        Classification.model_validate(payload)
