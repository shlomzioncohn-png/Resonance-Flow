"""Tests for the DeepSeek client, driven over a mocked HTTP transport.

Nothing here touches the network. `httpx.MockTransport` answers every request
in-process, which is the only way this mapping can be tested at all: you
cannot ask a live API for a 402 on demand, and a rate limit arrives when the
provider feels like it rather than when the test needs one. It also means CI
needs no API key and sends no content to a provider.

The live counterpart is a manual smoke test, not a pytest run - see the
provider notes in `src/mind/extraction/README.md`.
"""

import json

import httpx
import pytest
from tests.extraction.fakes import ScriptedProvider

from mind.contracts.base_enums import SourceType
from mind.extraction.config import ExtractionConfig
from mind.extraction.extractor import BasicExtractor, ExtractionFailure, ExtractionSuccess
from mind.extraction.providers.base import (
    LLMProvider,
    LLMRateLimited,
    LLMRejected,
    LLMTimeout,
    LLMUnavailable,
)
from mind.extraction.providers.deepseek import DeepSeekProvider
from mind.extraction.providers.http import MAX_ERROR_DETAIL_CHARS
from mind.extraction.providers.settings import DeepSeekSettings

VALID_ANSWER = json.dumps(
    {
        "title": "Send the Q3 report to finance",
        "description": "Finance asked for the Q3 report before the close.",
        "source_ref": "MSG-4471",
        "has_dependencies": False,
    }
)

SOURCE_TEXT = "Please send the Q3 report to finance before the close. (MSG-4471)"


def _envelope(
    content: str,
    model: str = "deepseek-chat",
    usage: dict[str, int] | None = None,
    finish_reason: str = "stop",
) -> dict[str, object]:
    """A DeepSeek chat-completions response, shaped like the real one."""

    body: dict[str, object] = {
        "id": "chat-1",
        "model": model,
        "choices": [{"index": 0, "finish_reason": finish_reason, "message": {"content": content}}],
    }
    if usage is not None:
        body["usage"] = usage
    return body


def _provider(
    handler: object,
    **overrides: object,
) -> DeepSeekProvider:
    """A provider wired to `handler`, with a test key and no real socket."""

    settings = DeepSeekSettings(api_key="sk-test", **overrides)  # type: ignore[arg-type]
    client = httpx.Client(transport=httpx.MockTransport(handler))  # type: ignore[arg-type]
    return DeepSeekProvider(settings=settings, client=client)


def _always(
    payload: object,
    status: int = 200,
    headers: dict[str, str] | None = None,
) -> tuple[object, list[httpx.Request]]:
    """A handler that answers every call the same way, keeping the requests."""

    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if isinstance(payload, str):
            return httpx.Response(status, text=payload, headers=headers)
        return httpx.Response(status, json=payload, headers=headers)

    return handler, seen


def _raising(error: Exception) -> object:
    """A handler that fails the way a broken connection fails."""

    def handler(request: httpx.Request) -> httpx.Response:
        raise error

    return handler


def _scripted(*responses: httpx.Response) -> tuple[object, list[httpx.Request]]:
    """A handler that answers with each response in turn."""

    queued = list(responses)
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        assert queued, f"the client made {len(seen)} calls but only {len(responses)} were scripted"
        return queued.pop(0)

    return handler, seen


# --- the request we send -------------------------------------------------


def test_the_request_goes_to_the_configured_endpoint_with_the_key() -> None:
    handler, seen = _always(_envelope(VALID_ANSWER))
    provider = _provider(handler, base_url="https://api.deepseek.com/")

    provider.complete("system", "user")

    assert str(seen[0].url) == "https://api.deepseek.com/chat/completions"
    assert seen[0].headers["Authorization"] == "Bearer sk-test"


def test_json_mode_a_capped_output_and_both_messages_are_sent() -> None:
    handler, seen = _always(_envelope(VALID_ANSWER))
    provider = _provider(handler, model="deepseek-chat", max_output_tokens=256)

    provider.complete("the system prompt", "the user prompt")

    body = json.loads(seen[0].content)
    assert body["model"] == "deepseek-chat"
    assert body["response_format"] == {"type": "json_object"}
    assert body["max_tokens"] == 256
    assert body["temperature"] == 0.0
    assert body["stream"] is False
    assert body["messages"] == [
        {"role": "system", "content": "the system prompt"},
        {"role": "user", "content": "the user prompt"},
    ]


# --- a good answer -------------------------------------------------------


def test_a_good_answer_comes_back_with_its_model_and_token_counts() -> None:
    handler, _ = _always(
        _envelope(VALID_ANSWER, usage={"prompt_tokens": 412, "completion_tokens": 37})
    )

    response = _provider(handler).complete("system", "user")

    assert response.text == VALID_ANSWER
    assert response.model == "deepseek-chat"
    assert response.prompt_tokens == 412
    assert response.completion_tokens == 37


def test_an_unreported_usage_block_leaves_the_counts_empty() -> None:
    """Absent counts stay absent: a zero would be priced as a free call."""

    handler, _ = _always(_envelope(VALID_ANSWER, usage=None))

    response = _provider(handler).complete("system", "user")

    assert response.prompt_tokens is None
    assert response.completion_tokens is None


def test_a_truncated_answer_is_refused_not_returned() -> None:
    """Repeating the call cannot help: the same cap cuts it in the same place."""

    handler, _ = _always(_envelope('{"title": "half an ans', finish_reason="length"))

    with pytest.raises(LLMRejected) as raised:
        _provider(handler, max_output_tokens=16).complete("system", "user")

    assert raised.value.retryable is False
    # The fix is a setting, so the message has to name the setting.
    assert "max_tokens=16" in str(raised.value)


def test_a_truncated_answer_costs_one_call_not_three() -> None:
    """What the old behaviour cost: three paid calls, all cut off alike.

    Returning the cut-off text sent it to the repair loop, which asked again
    with the same cap and got the same fragment, until the attempt budget ran
    out and the item was dropped.
    """

    handler, seen = _always(_envelope('{"title": "half an ans', finish_reason="length"))
    extractor = BasicExtractor(_provider(handler), sleep=lambda _: None)

    outcome = extractor.extract(SOURCE_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionFailure)
    assert outcome.reason == "provider_error"
    assert len(seen) == 1


# --- transport failures --------------------------------------------------


def test_a_timeout_is_reported_as_a_timeout() -> None:
    provider = _provider(_raising(httpx.ReadTimeout("too slow")))

    with pytest.raises(LLMTimeout) as raised:
        provider.complete("system", "user")

    assert raised.value.retryable is True


def test_an_unreachable_api_is_retryable() -> None:
    provider = _provider(_raising(httpx.ConnectError("connection refused")))

    with pytest.raises(LLMUnavailable) as raised:
        provider.complete("system", "user")

    assert raised.value.retryable is True
    assert "ConnectError" in str(raised.value)


# --- statuses ------------------------------------------------------------


def test_a_rate_limit_carries_the_providers_own_wait() -> None:
    handler, _ = _always({"error": {"message": "slow down"}}, 429, {"Retry-After": "7"})

    with pytest.raises(LLMRateLimited) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retry_after_seconds == 7.0
    assert "slow down" in str(raised.value)


def test_a_rate_limit_without_a_hint_leaves_the_wait_to_us() -> None:
    handler, _ = _always({"error": {"message": "slow down"}}, 429)

    with pytest.raises(LLMRateLimited) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retry_after_seconds is None


def test_an_http_date_retry_after_is_ignored_rather_than_half_parsed() -> None:
    headers = {"Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT"}
    handler, _ = _always({"error": {"message": "slow down"}}, 429, headers)

    with pytest.raises(LLMRateLimited) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retry_after_seconds is None


@pytest.mark.parametrize("status", [500, 502, 503, 504])
def test_a_failing_provider_is_worth_another_try(status: int) -> None:
    handler, _ = _always({"error": {"message": "overloaded"}}, status)

    with pytest.raises(LLMUnavailable) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retryable is True


@pytest.mark.parametrize(
    ("status", "message"),
    [
        (400, "invalid request body"),
        (401, "authentication fails"),
        (402, "insufficient balance"),
        (403, "forbidden"),
        (404, "model not found"),
        (422, "invalid parameters"),
    ],
)
def test_a_refusal_is_not_retried(status: int, message: str) -> None:
    """None of these get better by being repeated, including 402."""

    handler, _ = _always({"error": {"message": message}}, status)

    with pytest.raises(LLMRejected) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retryable is False
    assert message in str(raised.value)


def test_an_unmapped_4xx_is_treated_as_our_mistake() -> None:
    handler, _ = _always({"error": {"message": "conflict"}}, 409)

    with pytest.raises(LLMRejected) as raised:
        _provider(handler).complete("system", "user")

    assert "409" in str(raised.value)


def test_a_huge_error_body_is_clipped_before_it_reaches_the_logs() -> None:
    handler, _ = _always("x" * 5000, 500)

    with pytest.raises(LLMUnavailable) as raised:
        _provider(handler).complete("system", "user")

    assert "[...]" in str(raised.value)
    assert len(str(raised.value)) < MAX_ERROR_DETAIL_CHARS + 100


# --- bodies that are not what we asked for -------------------------------


def test_an_intercepting_proxys_page_is_a_provider_failure_not_a_bad_answer() -> None:
    """A filter or gateway answering 200 with HTML must not burn repairs.

    This is not hypothetical: a content filter in front of the API returns
    its own block page, and asking a model to "repair" a page it never wrote
    would spend every attempt before failing.
    """

    handler, _ = _always("<!DOCTYPE html><html><body>blocked</body></html>")

    with pytest.raises(LLMUnavailable) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retryable is True
    assert "not JSON" in str(raised.value)


def test_an_envelope_without_choices_is_a_provider_failure() -> None:
    handler, _ = _always({"id": "chat-1", "model": "deepseek-chat", "choices": []})

    with pytest.raises(LLMUnavailable, match="no completion"):
        _provider(handler).complete("system", "user")


def test_an_empty_completion_is_a_provider_failure() -> None:
    handler, _ = _always(_envelope("   "))

    with pytest.raises(LLMUnavailable, match="empty completion"):
        _provider(handler).complete("system", "user")


def test_a_json_body_that_is_not_an_object_is_a_provider_failure() -> None:
    handler, _ = _always([1, 2, 3])

    with pytest.raises(LLMUnavailable, match="not an object"):
        _provider(handler).complete("system", "user")


# --- construction --------------------------------------------------------


def test_construction_without_a_key_fails_immediately() -> None:
    """Better than a provider that 401s on every call and looks flaky."""

    with pytest.raises(ValueError, match="api_key is not configured"):
        DeepSeekProvider(settings=DeepSeekSettings())


def test_it_satisfies_the_provider_protocol() -> None:
    handler, _ = _always(_envelope(VALID_ANSWER))
    provider: LLMProvider = _provider(handler)

    assert provider.name == "deepseek"


def test_a_client_we_created_is_a_client_we_close() -> None:
    provider = DeepSeekProvider(settings=DeepSeekSettings(api_key="sk-test"))

    with provider:
        pass

    assert provider._client.is_closed is True


def test_a_client_we_were_handed_is_left_alone() -> None:
    client = httpx.Client(transport=httpx.MockTransport(_always(_envelope(VALID_ANSWER))[0]))
    provider = DeepSeekProvider(settings=DeepSeekSettings(api_key="sk-test"), client=client)

    provider.close()

    assert client.is_closed is False


# --- the extractor on top of it ------------------------------------------


def test_the_extractor_drives_the_real_client_end_to_end() -> None:
    handler, _ = _always(
        _envelope(VALID_ANSWER, usage={"prompt_tokens": 400, "completion_tokens": 40})
    )
    extractor = BasicExtractor(_provider(handler), sleep=lambda _: None)

    outcome = extractor.extract(SOURCE_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionSuccess)
    assert outcome.fields.title == "Send the Q3 report to finance"
    assert outcome.fields.source_ref == "MSG-4471"
    assert outcome.model == "deepseek-chat"
    assert outcome.provider_calls == 1


def test_the_repair_round_works_over_http() -> None:
    handler, seen = _scripted(
        httpx.Response(200, json=_envelope('{"title": "no deps field"}')),
        httpx.Response(200, json=_envelope(VALID_ANSWER)),
    )
    extractor = BasicExtractor(_provider(handler), sleep=lambda _: None)

    outcome = extractor.extract(SOURCE_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionSuccess)
    assert outcome.schema_attempts == 2
    # The second request is the repair prompt: it quotes the rejected answer
    # back at the model together with the reason.
    repair = json.loads(seen[1].content)["messages"][1]["content"]
    assert "no deps field" in repair
    assert "has_dependencies" in repair


def test_a_rate_limited_call_is_waited_out_without_spending_a_repair() -> None:
    handler, _ = _scripted(
        httpx.Response(429, json={"error": {"message": "slow down"}}, headers={"Retry-After": "2"}),
        httpx.Response(200, json=_envelope(VALID_ANSWER)),
    )
    waits: list[float] = []
    extractor = BasicExtractor(_provider(handler), sleep=waits.append)

    outcome = extractor.extract(SOURCE_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionSuccess)
    assert outcome.schema_attempts == 1
    assert outcome.provider_calls == 2
    assert waits == [2.0]


def test_an_out_of_credit_account_fails_the_item_on_the_first_call() -> None:
    handler, seen = _always({"error": {"message": "insufficient balance"}}, 402)
    extractor = BasicExtractor(_provider(handler), sleep=lambda _: None)

    outcome = extractor.extract(SOURCE_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionFailure)
    assert outcome.reason == "provider_error"
    assert len(seen) == 1


def test_a_blocked_endpoint_is_retried_then_recorded_as_a_provider_failure() -> None:
    """What a filtered network looks like from inside the pipeline."""

    handler, seen = _always("<html>blocked</html>")
    config = ExtractionConfig(max_transient_retries=2)
    extractor = BasicExtractor(_provider(handler), config=config, sleep=lambda _: None)

    outcome = extractor.extract(SOURCE_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionFailure)
    assert outcome.reason == "provider_error"
    assert len(seen) == 3  # the first call plus two transient retries
    assert "LLMUnavailable" in outcome.detail


def test_the_scripted_fake_and_the_real_client_agree_on_the_protocol() -> None:
    """The fake earns its keep only if it is substitutable for the client."""

    handler, _ = _always(_envelope(VALID_ANSWER))
    providers: list[LLMProvider] = [_provider(handler), ScriptedProvider(VALID_ANSWER)]

    for provider in providers:
        assert provider.complete("system", "user").text == VALID_ANSWER
