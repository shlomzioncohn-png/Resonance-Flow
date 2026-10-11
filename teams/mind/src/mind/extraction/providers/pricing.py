"""Pricing one call, and pricing a run before launching it.

#350 says to estimate the cost of a large run beforehand. That needs two
things: a price per token and a token count. The provider reports the count on
every response, so this module only has to hold the prices.

Two deliberate choices:

* An unknown model is *unpriced*, not free: every function here returns None
  rather than 0.0 when it cannot price something. A silent zero in a cost
  report is worse than a visible gap.
* The numbers below are a copy of a published price list, and a copy goes
  stale. They also ignore DeepSeek's cheaper cache-hit input tokens and its
  off-peak discount, so every estimate is an upper bound. Re-check the list
  before quoting a number anyone will act on.
"""

from dataclasses import dataclass

_PER_MILLION = 1_000_000


@dataclass(frozen=True)
class TokenPrice:
    """USD per million tokens, input and output priced separately."""

    input_per_million: float
    output_per_million: float


# Copied from DeepSeek's published price list - verify before relying on it.
PRICES: dict[str, TokenPrice] = {
    "deepseek-chat": TokenPrice(input_per_million=0.27, output_per_million=1.10),
    "deepseek-reasoner": TokenPrice(input_per_million=0.55, output_per_million=2.19),
}


def is_priced(model: str) -> bool:
    """Whether this model has a price on file."""

    return model in PRICES


def estimate_cost(
    model: str,
    prompt_tokens: int | None,
    completion_tokens: int | None,
) -> float | None:
    """Cost of one call in USD, or None if it cannot be priced.

    None comes back for an unknown model and for a provider that did not
    report its token usage. Both are gaps in the input, not zero-cost calls.
    """

    price = PRICES.get(model)
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

    per_item = estimate_cost(model, avg_prompt_tokens, avg_completion_tokens)
    if per_item is None:
        return None

    return per_item * item_count * average_attempts
