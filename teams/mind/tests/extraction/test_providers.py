"""Tests for the provider surface and the scripted test double."""

import pytest
from tests.extraction.fakes import ScriptedProvider

from mind.extraction.providers.base import (
    LLMAccessDenied,
    LLMError,
    LLMProvider,
    LLMRateLimited,
    LLMRejected,
    LLMTimeout,
    LLMUnavailable,
)


def test_scripted_provider_satisfies_the_protocol() -> None:
    provider: LLMProvider = ScriptedProvider("{}")

    assert provider.name == "scripted"


def test_scripted_provider_answers_in_order() -> None:
    provider = ScriptedProvider("first", "second")

    assert provider.complete("sys", "one").text == "first"
    assert provider.complete("sys", "two").text == "second"
    assert provider.call_count == 2


def test_scripted_provider_raises_queued_errors() -> None:
    provider = ScriptedProvider(LLMTimeout("too slow"), "recovered")

    with pytest.raises(LLMTimeout):
        provider.complete("sys", "one")
    assert provider.complete("sys", "two").text == "recovered"


def test_scripted_provider_complains_when_over_called() -> None:
    provider = ScriptedProvider("only one")
    provider.complete("sys", "one")

    with pytest.raises(AssertionError, match="only 1 answers were scripted"):
        provider.complete("sys", "two")


@pytest.mark.parametrize(
    ("error", "retryable"),
    [
        (LLMTimeout("t"), True),
        (LLMRateLimited("r"), True),
        (LLMUnavailable("u"), True),
        (LLMRejected("bad key"), False),
        (LLMError("unknown"), False),
    ],
)
def test_only_transient_failures_are_marked_retryable(error: LLMError, retryable: bool) -> None:
    assert error.retryable is retryable


def test_rate_limit_carries_the_providers_retry_hint() -> None:
    assert LLMRateLimited("slow down", retry_after_seconds=2.5).retry_after_seconds == 2.5


@pytest.mark.parametrize(
    ("error", "environmental"),
    [
        (LLMTimeout("t"), True),
        (LLMRateLimited("r"), True),
        (LLMUnavailable("u"), True),
        (LLMAccessDenied("bad key"), True),
        (LLMRejected("bad body"), False),
        (LLMError("unknown"), False),
    ],
)
def test_only_failures_no_item_could_pass_are_environmental(
    error: LLMError, environmental: bool
) -> None:
    # #919: environmental stops the pipeline; anything else skips the item.
    assert error.environmental is environmental


def test_a_refused_account_is_still_a_refusal_never_retried() -> None:
    assert isinstance(LLMAccessDenied("402"), LLMRejected)
    assert LLMAccessDenied("402").retryable is False
