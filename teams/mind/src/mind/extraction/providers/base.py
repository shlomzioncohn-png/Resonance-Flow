"""The surface every LLM provider must present to the extractor.

The extractor is written against this and nothing else, so swapping DeepSeek
for Gemini — or for a scripted fake in tests — is a configuration change
rather than a code change: `llm.provider` names one and `factory.build_provider`
returns it. Adding a *third* provider needs Shmuel's sign-off first.
"""

from dataclasses import dataclass
from typing import Protocol, Self


class LLMError(Exception):
    """Anything that went wrong while talking to a provider.

    `retryable` says whether the same call is worth making again. Timeouts
    and rate limits are; a rejected key or a malformed request is not, and
    retrying it only burns budget.

    `environmental` says whether the cause is ours or the provider's rather
    than the item's: every other item would fail the same way. The pipeline
    stops on it and processes the item again later (#919), where a refusal of
    the item's own content is skipped. A different question from `retryable`:
    an exhausted key is not retried here, but it is not the item's fault.
    """

    retryable = False
    environmental = False


class LLMTimeout(LLMError):
    """The provider did not answer within the configured deadline."""

    retryable = True
    environmental = True


class LLMRateLimited(LLMError):
    """The provider refused the call because we are sending too fast."""

    retryable = True
    environmental = True

    def __init__(self, message: str, retry_after_seconds: float | None = None) -> None:
        super().__init__(message)
        self.retry_after_seconds = retry_after_seconds


class LLMUnavailable(LLMError):
    """The provider is reachable but failing (5xx, overloaded, maintenance)."""

    retryable = True
    environmental = True


class LLMRejected(LLMError):
    """The provider refused this request: its body, or the content it carries
    (400, 422, a blocked answer, a truncated one)."""

    retryable = False


class LLMAccessDenied(LLMRejected):
    """The provider refused the account, not the request: the key (401, 403),
    the credit (402), or a model this key may not call (404).

    Not retried, like any refusal -- the next call fails the same way until
    somebody fixes the account -- but environmental: no item could pass.
    """

    environmental = True


@dataclass(frozen=True)
class LLMResponse:
    """One completion, plus what it cost.

    Token counts are optional because not every provider reports them, but
    when they are there the pipeline can price a run before launching it.
    """

    text: str
    model: str
    prompt_tokens: int | None = None
    completion_tokens: int | None = None


class LLMProvider(Protocol):
    """Structural interface — implementations need not inherit from this.

    A Protocol rather than a base class on purpose: the test fake stays in
    the test tree and does not have to import production code to satisfy it.
    """

    name: str

    @property
    def model(self) -> str:
        """The model id this provider is configured to send.

        The key every call is recorded under (observability.record_call), the
        failures included: a model's successes and failures have to land on one
        row of the cost and latency summary (#479). Part of the Protocol rather
        than read with a getattr fallback, so mypy holds every provider to it.
        """
        ...

    def complete(self, system: str, user: str) -> LLMResponse:
        """Send one prompt pair and return the raw text of the answer.

        Implementations raise an `LLMError` subclass on failure and never
        return a partial or placeholder response — deciding what to do about
        a failure belongs to the extractor, not to the provider.
        """
        ...


class ManagedLLMProvider(LLMProvider, Protocol):
    """A provider that also owns a resource somebody has to release.

    Separate from `LLMProvider` on purpose. The extractor needs `complete` and
    nothing else, and keeping it that way is what lets a scripted fake — which
    has no socket to close — satisfy the protocol without growing an empty
    method. But a real client holds an HTTP connection, and whoever built it
    has to close it. That is the caller's job, never the extractor's: the
    extractor is handed a provider, it does not own its lifetime.

    This is what `factory.build_provider` returns, so the obligation is visible
    in the type rather than remembered.
    """

    def close(self) -> None:
        """Release whatever the provider holds open."""
        ...

    def __enter__(self) -> Self: ...

    def __exit__(self, *exc_info: object) -> None: ...
