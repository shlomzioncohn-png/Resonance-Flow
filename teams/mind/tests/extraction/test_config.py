"""Tests for the extraction loop's configuration."""

import pytest

from mind.extraction.config import ExtractionConfig


def test_defaults_allow_one_attempt_plus_repairs() -> None:
    config = ExtractionConfig()

    assert config.max_attempts >= 2
    assert config.max_transient_retries >= 1


def test_zero_attempts_is_refused() -> None:
    with pytest.raises(ValueError, match="max_attempts"):
        ExtractionConfig(max_attempts=0)


def test_negative_transient_retries_is_refused() -> None:
    with pytest.raises(ValueError, match="max_transient_retries"):
        ExtractionConfig(max_transient_retries=-1)


def test_no_transient_retries_is_allowed() -> None:
    assert ExtractionConfig(max_transient_retries=0).max_transient_retries == 0
