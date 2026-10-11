"""Tests for pricing a call and sizing a run.

The prices themselves are not asserted here - they are a copy of a published
list and a test that repeats the copy proves nothing. What is asserted is the
arithmetic and, more importantly, that an unpriced call is reported as unknown
rather than as free.
"""

import pytest

from mind.extraction.providers.pricing import (
    PRICES,
    estimate_cost,
    estimate_run_cost,
    is_priced,
)

MODEL = "deepseek-chat"


def test_a_known_model_is_priced_from_its_token_counts() -> None:
    price = PRICES[MODEL]
    expected = (1_000_000 * price.input_per_million + 1_000_000 * price.output_per_million) / 1e6

    assert estimate_cost(MODEL, 1_000_000, 1_000_000) == pytest.approx(expected)


def test_output_tokens_cost_more_than_input_tokens() -> None:
    """True of every model we price; a table where it flips is a typo."""

    for model, price in PRICES.items():
        assert price.output_per_million > price.input_per_million, model


def test_an_unknown_model_is_unpriced_not_free() -> None:
    assert is_priced("some-local-llama") is False
    assert estimate_cost("some-local-llama", 1000, 100) is None


def test_a_provider_that_reported_no_usage_is_unpriced() -> None:
    assert estimate_cost(MODEL, None, 100) is None
    assert estimate_cost(MODEL, 1000, None) is None


def test_a_call_that_used_nothing_costs_nothing() -> None:
    """Zero is a real answer here, and distinct from None."""

    assert estimate_cost(MODEL, 0, 0) == 0.0


def test_a_run_scales_with_the_number_of_items() -> None:
    one = estimate_run_cost(MODEL, 1, 400, 40)
    thousand = estimate_run_cost(MODEL, 1000, 400, 40)

    assert one is not None and thousand is not None
    assert thousand == pytest.approx(one * 1000)


def test_repair_rounds_make_a_run_more_expensive() -> None:
    clean = estimate_run_cost(MODEL, 1000, 400, 40, average_attempts=1.0)
    repairing = estimate_run_cost(MODEL, 1000, 400, 40, average_attempts=1.3)

    assert clean is not None and repairing is not None
    assert repairing > clean


def test_an_empty_run_costs_nothing() -> None:
    assert estimate_run_cost(MODEL, 0, 400, 40) == 0.0


def test_a_run_on_an_unpriced_model_is_unknown() -> None:
    assert estimate_run_cost("some-local-llama", 1000, 400, 40) is None


@pytest.mark.parametrize(("items", "attempts"), [(-1, 1.0), (10, -0.5)])
def test_nonsense_run_sizes_are_refused(items: int, attempts: float) -> None:
    with pytest.raises(ValueError):
        estimate_run_cost(MODEL, items, 400, 40, average_attempts=attempts)
