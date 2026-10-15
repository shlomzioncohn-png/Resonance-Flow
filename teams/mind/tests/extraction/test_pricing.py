"""Tests for pricing a call and sizing a run.

The prices themselves are not asserted here - they are a copy of a published
list and a test that repeats the copy proves nothing. What is asserted is the
arithmetic and, more importantly, that an unpriced call is reported as unknown
rather than as free.
"""

from datetime import date, timedelta

import pytest

from mind.extraction.providers import pricing
from mind.extraction.providers.pricing import (
    PRICES,
    TokenPrice,
    estimate_cost,
    estimate_run_cost,
    is_priced,
    price_on,
)

MODEL = "deepseek-chat"


def test_a_known_model_is_priced_from_its_token_counts() -> None:
    day = date(2026, 10, 13)
    price = price_on(MODEL, day)
    assert price is not None
    expected = (1_000_000 * price.input_per_million + 1_000_000 * price.output_per_million) / 1e6

    assert estimate_cost(MODEL, 1_000_000, 1_000_000, on=day) == pytest.approx(expected)


def test_output_tokens_cost_more_than_input_tokens() -> None:
    """True of every price we hold; a table where it flips is a typo."""

    for model, history in PRICES.items():
        for price in history:
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


CHANGE_DAY = date(2027, 1, 1)


@pytest.fixture
def scheduled_change(monkeypatch: pytest.MonkeyPatch) -> str:
    """A made-up model whose price doubles on CHANGE_DAY, like a real announcement."""

    # Listed newest first on purpose: the order of a history must not matter.
    monkeypatch.setitem(
        PRICES,
        "fake-model",
        (
            TokenPrice(input_per_million=2.0, output_per_million=4.0, effective_from=CHANGE_DAY),
            TokenPrice(input_per_million=1.0, output_per_million=2.0),
        ),
    )
    return "fake-model"


def test_a_price_change_takes_effect_on_its_day_not_before(scheduled_change: str) -> None:
    """Both rates switch: one million in and one million out is 1 + 2, then 2 + 4."""

    day_before = CHANGE_DAY - timedelta(days=1)

    assert estimate_cost(scheduled_change, 1_000_000, 1_000_000, on=day_before) == pytest.approx(
        3.0
    )
    assert estimate_cost(scheduled_change, 1_000_000, 1_000_000, on=CHANGE_DAY) == pytest.approx(
        6.0
    )


def test_a_call_made_now_is_priced_on_today(
    scheduled_change: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The extractor passes no date; that must mean today, not the oldest price."""

    monkeypatch.setattr(pricing, "_today", lambda: CHANGE_DAY)

    assert estimate_cost(scheduled_change, 1_000_000, 0) == pytest.approx(2.0)


def test_a_model_whose_only_price_has_not_started_is_not_priced_yet(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """is_priced must agree with estimate_cost, which has nothing to price it with."""

    future = TokenPrice(input_per_million=1.0, output_per_million=2.0, effective_from=CHANGE_DAY)
    monkeypatch.setitem(PRICES, "announced-model", (future,))

    assert is_priced("announced-model", on=CHANGE_DAY - timedelta(days=1)) is False
    assert is_priced("announced-model", on=CHANGE_DAY) is True


def test_a_run_is_priced_on_the_day_it_will_happen(scheduled_change: str) -> None:
    """Sizing a January run today must use January's price."""

    cost = estimate_run_cost(scheduled_change, 10, 1_000_000, 0, on=CHANGE_DAY)

    assert cost == pytest.approx(20.0)


@pytest.mark.parametrize(("items", "attempts"), [(-1, 1.0), (10, -0.5)])
def test_nonsense_run_sizes_are_refused(items: int, attempts: float) -> None:
    with pytest.raises(ValueError):
        estimate_run_cost(MODEL, items, 400, 40, average_attempts=attempts)
