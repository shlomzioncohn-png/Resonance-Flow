"""Pricing one call, and pricing a run before launching it.

#350 says to estimate the cost of a large run beforehand. That needs two
things: a price per token and a token count. The provider reports the count on
every response, so this module only has to hold the prices.

Two deliberate choices:

* An unknown model is *unpriced*, not free: every function here returns None
  rather than 0.0 when it cannot price something. A silent zero in a cost
  report is worse than a visible gap.
* The numbers below are copies of DeepSeek's and Google's published price
  lists, and a copy goes stale. They ignore cheaper cached input tokens and
  every discount (DeepSeek's off-peak hours, Gemini's Batch and Flex tiers),
  so each estimate is an upper bound for the standard tier. Re-check the list
  before quoting a number anyone will act on.
"""

from dataclasses import dataclass
from datetime import UTC, date, datetime

_PER_MILLION = 1_000_000


@dataclass(frozen=True)
class TokenPrice:
    """USD per million tokens, input and output priced separately.

    `effective_from` is the first day the price applies. A model's first price
    leaves it at `date.min`: we know what it costs, not since when.
    """

    input_per_million: float
    output_per_million: float
    effective_from: date = date.min


# Each model maps to its price history, so an announced change can be entered
# ahead of time instead of remembered on the day. Order does not matter.
PRICES: dict[str, tuple[TokenPrice, ...]] = {
    # Copied from DeepSeek's published price list - verify before relying on it.
    "deepseek-chat": (TokenPrice(input_per_million=0.27, output_per_million=1.10),),
    "deepseek-reasoner": (TokenPrice(input_per_million=0.55, output_per_million=2.19),),
    # Gemini paid tier, text input, Standard. Checked against
    # ai.google.dev/gemini-api/docs/pricing on 2026-10-13. gemini-3.5-flash
    # and gemini-3.7-flash answer calls but have no price on that page, so
    # they stay unpriced rather than borrowing a neighbour's.
    "gemini-3.6-flash": (
        TokenPrice(input_per_million=0.75, output_per_million=3.75),
        TokenPrice(
            input_per_million=1.50, output_per_million=7.50, effective_from=date(2027, 1, 1)
        ),
    ),
    "gemini-3.8-flash": (
        TokenPrice(input_per_million=0.75, output_per_million=3.75),
        TokenPrice(
            input_per_million=1.50, output_per_million=7.50, effective_from=date(2027, 1, 1)
        ),
    ),
    "gemini-3-flash-preview": (TokenPrice(input_per_million=0.50, output_per_million=3.00),),
    "gemini-3.1-flash-lite": (TokenPrice(input_per_million=0.25, output_per_million=1.50),),
}


def _today() -> date:
    """The day a call made now is priced on, in UTC.

    Google's page names no timezone for its change dates, so a call within
    hours of a change may be priced a day off.
    """

    return datetime.now(UTC).date()


def is_priced(model: str, on: date | None = None) -> bool:
    """Whether this model has a price in force on `on` (default: today)."""

    return price_on(model, on or _today()) is not None


def price_on(model: str, day: date) -> TokenPrice | None:
    """The price in force on `day`: the latest one whose day has come."""

    in_force = [price for price in PRICES.get(model, ()) if price.effective_from <= day]
    return max(in_force, key=lambda price: price.effective_from, default=None)


def estimate_cost(
    model: str,
    prompt_tokens: int | None,
    completion_tokens: int | None,
    on: date | None = None,
) -> float | None:
    """Cost of one call in USD, or None if it cannot be priced.

    None comes back for an unknown model and for a provider that did not
    report its token usage. Both are gaps in the input, not zero-cost calls.
    `on` defaults to today, see `_today`.
    """

    price = price_on(model, on or _today())
    if price is None or prompt_tokens is None or completion_tokens is None:
        return None

    return (
        prompt_tokens * price.input_per_million + completion_tokens * price.output_per_million
    ) / _PER_MILLION


def estimate_run_cost(
    model: str,
    item_count: int,
    avg_prompt_tokens: int,
    avg_completion_tokens: int,
    average_attempts: float = 1.0,
    on: date | None = None,
) -> float | None:
    """Rough cost of processing `item_count` items, for a go/no-go decision.

    `average_attempts` accounts for repair rounds. It is a proxy and it errs
    low: a repair prompt carries the original content *plus* the rejected
    answer, so a second attempt costs more than the first rather than the
    same. Measure it on a small sample and scale, rather than trusting this
    for a run large enough to matter.
    """

    if item_count < 0 or average_attempts < 0:
        raise ValueError("item_count and average_attempts cannot be negative")

    per_item = estimate_cost(model, avg_prompt_tokens, avg_completion_tokens, on)
    if per_item is None:
        return None

    return per_item * item_count * average_attempts
