"""Knobs for the extraction loop.

Everything here is a value, not a constant buried in code, because two of
them — the provider and the model name — change often enough that a code
change per change would be absurd. DeepSeek retires model ids on a timescale
of months.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class ExtractionConfig:
    """How hard to try, and how long to wait between tries."""

    # Whether to ask for tier, urgency and role_context in the same call
    # (#351). A switch rather than a rewrite, so both shapes can be run
    # from one build: the day a provider is reachable for a measured run,
    # comparing them is a flag rather than a checkout of an old commit.
    classify: bool = True

    # Total rounds spent on one item: the first attempt plus repairs.
    # Past this the item is recorded as failed and the pipeline moves on.
    max_attempts: int = 3

    # Transient provider failures (timeout, rate limit, 5xx) are retried
    # with the same prompt, waiting longer each time. They are capped
    # separately from max_attempts: a flaky network is not the model
    # failing the schema, and it must not eat the repair budget.
    max_transient_retries: int = 2

    backoff_seconds: float = 1.0
    backoff_multiplier: float = 2.0

    # A provider that says how long to wait is believed, up to this cap.
    max_backoff_seconds: float = 30.0

    def __post_init__(self) -> None:
        if self.max_attempts < 1:
            raise ValueError("max_attempts must be at least 1")
        if self.max_transient_retries < 0:
            raise ValueError("max_transient_retries cannot be negative")
