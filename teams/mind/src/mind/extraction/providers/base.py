"""The surface every LLM provider must present to the extractor.

The extractor is written against this and nothing else, so swapping DeepSeek
for Gemini — or for a scripted fake in tests — is a configuration change, not
a code change. Adding a *third* provider needs Shmuel's sign-off first.
"""

from dataclasses import dataclass
from typing import Protocol


class LLMError(Exception):
    """Anything that went wrong while talking to a provider.

    `retryable` says whether the same call is worth making again. Timeouts
    and rate limits are; a rejected key or a malformed request is not, and
    retrying it only burns budget.
    """

    retryable = False


class LLMTimeout(LLMError):
    """The provider did not answer within the configured deadline."""

    retryable = True


class LLMRateLimited(LLMError):
    """The provider refused the call because we are sending too fast."""

    retryable = True

    def __init__(self, message: str, retry_after_seconds: float | None = None) -> None:
        super().__init__(message)
        self.retry_after_seconds = retry_after_seconds


class LLMUnavailable(LLMError):
    """The provider is reachable but failing (5xx, overloaded, maintenance)."""

    retryable = True


class LLMRejected(LLMError):
    """The provider refused the request itself (bad key, bad model, bad body)."""

    retryable = False


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

    def complete(self, system: str, user: str) -> LLMResponse:
        """Send one prompt pair and return the raw text of the answer.

        Implementations raise an `LLMError` subclass on failure and never
        return a partial or placeholder response — deciding what to do about
        a failure belongs to the extractor, not to the provider.
        """
        ...
