"""Tests for the Gemini client, driven over a mocked HTTP transport.

Same discipline as the DeepSeek tests: nothing here touches the network, which
is the only way to produce a quota error or a safety block on demand, and it
keeps CI free of keys.

Several of the cases below are not hypothetical. The 503, the 404 and the
`thinkingConfig` behaviour were all observed against the live API before this
client was written, and the bodies used here are the shapes it actually
returned.
"""

import json

import httpx
import pytest

from mind.contracts.base_enums import SourceType
from mind.extraction.extractor import BasicExtractor, ExtractionFailure, ExtractionSuccess
from mind.extraction.providers.base import (
    LLMProvider,
    LLMRateLimited,
    LLMRejected,
    LLMTimeout,
    LLMUnavailable,
)
from mind.extraction.providers.gemini import GeminiProvider
from mind.extraction.providers.settings import GeminiSettings

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
    *texts: str,
    model: str = "gemini-3.6-flash",
    finish_reason: str = "STOP",
    usage: dict[str, int] | None = None,
) -> dict[str, object]:
    """A generateContent response, shaped like the live one."""

    body: dict[str, object] = {
        "candidates": [
            {
                "content": {"role": "model", "parts": [{"text": t} for t in texts]},
                "finishReason": finish_reason,
            }
        ],
        "modelVersion": model,
    }
    if usage is not None:
        body["usageMetadata"] = usage
    return body


def _provider(handler: object, **overrides: object) -> GeminiProvider:
    settings = GeminiSettings(api_key="test-key", **overrides)  # type: ignore[arg-type]
    client = httpx.Client(transport=httpx.MockTransport(handler))  # type: ignore[arg-type]
    return GeminiProvider(settings=settings, client=client)


def _always(
    payload: object,
    status: int = 200,
) -> tuple[object, list[httpx.Request]]:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if isinstance(payload, str):
            return httpx.Response(status, text=payload)
        return httpx.Response(status, json=payload)

    return handler, seen


def _raising(error: Exception) -> object:
    def handler(request: httpx.Request) -> httpx.Response:
        raise error

    return handler


def _scripted(*responses: httpx.Response) -> tuple[object, list[httpx.Request]]:
    queued = list(responses)
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        assert queued, f"the client made {len(seen)} calls but only {len(responses)} were scripted"
        return queued.pop(0)

    return handler, seen


# --- the request we send -------------------------------------------------


def test_the_model_is_part_of_the_path_and_the_key_is_not_in_the_url() -> None:
    """A key in a query string is a key in every proxy log on the way."""

    handler, seen = _always(_envelope(VALID_ANSWER))
    provider = _provider(handler, model="gemini-3.6-flash")

    provider.complete("system", "user")

    url = str(seen[0].url)
    assert url == (
        "https://generativelanguage.googleapis.com/v1beta/models/"
        "gemini-3.6-flash:generateContent"
    )
    assert "key" not in url
    assert seen[0].headers["x-goog-api-key"] == "test-key"


def test_the_system_prompt_is_its_own_field_not_a_message() -> None:
    """The main shape difference from an OpenAI-style API."""

    handler, seen = _always(_envelope(VALID_ANSWER))

    _provider(handler).complete("the system prompt", "the user prompt")

    body = json.loads(seen[0].content)
    assert body["system_instruction"] == {"parts": [{"text": "the system prompt"}]}
    assert body["contents"] == [{"role": "user", "parts": [{"text": "the user prompt"}]}]


def test_json_mode_a_capped_output_and_thinking_off_are_requested() -> None:
    handler, seen = _always(_envelope(VALID_ANSWER))

    _provider(handler, max_output_tokens=256).complete("system", "user")

    generation = json.loads(seen[0].content)["generationConfig"]
    assert generation["responseMimeType"] == "application/json"
    assert generation["maxOutputTokens"] == 256
    assert generation["temperature"] == 0.0
    assert generation["thinkingConfig"] == {"thinkingBudget": 0}


def test_a_null_thinking_budget_leaves_the_field_out_entirely() -> None:
    """Not every model accepts it, and one answered 400 for a body with it."""

    handler, seen = _always(_envelope(VALID_ANSWER))

    _provider(handler, thinking_budget=None).complete("system", "user")

    assert "thinkingConfig" not in json.loads(seen[0].content)["generationConfig"]


# --- a good answer -------------------------------------------------------


def test_a_good_answer_comes_back_with_its_model_and_token_counts() -> None:
    handler, _ = _always(
        _envelope(
            VALID_ANSWER,
            usage={"promptTokenCount": 496, "candidatesTokenCount": 59},
        )
    )

    response = _provider(handler).complete("system", "user")

    assert response.text == VALID_ANSWER
    assert response.model == "gemini-3.6-flash"
    assert response.prompt_tokens == 496
    assert response.completion_tokens == 59


def test_an_answer_split_across_parts_is_joined() -> None:
    """Taking parts[0] would truncate an object to its opening brace."""

    handler, _ = _always(_envelope('{"title": "Send the report",', ' "has_dependencies": false}'))

    text = _provider(handler).complete("system", "user").text

    assert json.loads(text) == {"title": "Send the report", "has_dependencies": False}


def test_an_unreported_usage_block_leaves_the_counts_empty() -> None:
    handler, _ = _always(_envelope(VALID_ANSWER, usage=None))

    response = _provider(handler).complete("system", "user")

    assert response.prompt_tokens is None
    assert response.completion_tokens is None


def test_a_truncated_answer_is_refused_not_returned() -> None:
    """Repeating the call cannot help: the same cap cuts it in the same place."""

    handler, _ = _always(_envelope('{"title": "half an ans', finish_reason="MAX_TOKENS"))

    with pytest.raises(LLMRejected) as raised:
        _provider(handler, max_output_tokens=16).complete("system", "user")

    assert raised.value.retryable is False
    assert "16" in str(raised.value)


# --- refusals that look like answers -------------------------------------


@pytest.mark.parametrize("reason", ["SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT"])
def test_a_blocked_answer_is_a_refusal_not_a_hiccup(reason: str) -> None:
    """Sending the same prompt again gets it blocked again."""

    handler, _ = _always(_envelope("", finish_reason=reason))

    with pytest.raises(LLMRejected) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retryable is False
    assert reason in str(raised.value)


def test_a_blocked_prompt_names_the_reason() -> None:
    handler, _ = _always({"promptFeedback": {"blockReason": "SAFETY"}})

    with pytest.raises(LLMRejected, match="blocked the prompt"):
        _provider(handler).complete("system", "user")


def test_no_candidates_and_no_reason_is_a_provider_failure() -> None:
    handler, _ = _always({"candidates": []})

    with pytest.raises(LLMUnavailable, match="no candidates"):
        _provider(handler).complete("system", "user")


def test_an_empty_completion_is_a_provider_failure() -> None:
    handler, _ = _always(_envelope("   "))

    with pytest.raises(LLMUnavailable, match="empty completion"):
        _provider(handler).complete("system", "user")


def test_a_body_that_is_not_the_envelope_is_a_provider_failure() -> None:
    handler, _ = _always("<!DOCTYPE html><html><body>blocked</body></html>")

    with pytest.raises(LLMUnavailable, match="not JSON"):
        _provider(handler).complete("system", "user")


# --- statuses ------------------------------------------------------------


def test_a_quota_error_carries_the_wait_gemini_asked_for() -> None:
    """The hint is a duration inside error.details, not a header."""

    handler, _ = _always(
        {
            "error": {
                "code": 429,
                "status": "RESOURCE_EXHAUSTED",
                "message": "You exceeded your current quota",
                "details": [
                    {"@type": "type.googleapis.com/google.rpc.QuotaFailure"},
                    {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "21s"},
                ],
            }
        },
        429,
    )

    with pytest.raises(LLMRateLimited) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retry_after_seconds == 21.0
    assert "quota" in str(raised.value)


def test_a_quota_error_without_a_hint_leaves_the_wait_to_us() -> None:
    handler, _ = _always({"error": {"code": 429, "message": "slow down"}}, 429)

    with pytest.raises(LLMRateLimited) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retry_after_seconds is None


def test_high_demand_is_retryable() -> None:
    """The free tier's everyday failure, verbatim from the live API."""

    handler, _ = _always(
        {
            "error": {
                "code": 503,
                "status": "UNAVAILABLE",
                "message": "This model is currently experiencing high demand. "
                "Spikes in demand are usually temporary. Please try again later.",
            }
        },
        503,
    )

    with pytest.raises(LLMUnavailable) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retryable is True
    assert "high demand" in str(raised.value)


def test_a_model_this_key_may_not_call_is_not_retried() -> None:
    """Also verbatim: a new key cannot call the previous generation."""

    handler, _ = _always(
        {
            "error": {
                "code": 404,
                "message": "This model models/gemini-2.5-flash is no longer available "
                "to new users.",
            }
        },
        404,
    )

    with pytest.raises(LLMRejected) as raised:
        _provider(handler).complete("system", "user")

    assert raised.value.retryable is False


@pytest.mark.parametrize("status", [400, 401, 403, 422])
def test_a_refusal_is_not_retried(status: int) -> None:
    handler, _ = _always({"error": {"code": status, "message": "nope"}}, status)

    with pytest.raises(LLMRejected):
        _provider(handler).complete("system", "user")


@pytest.mark.parametrize("status", [500, 502, 504])
def test_a_failing_provider_is_worth_another_try(status: int) -> None:
    handler, _ = _always({"error": {"message": "oops"}}, status)

    with pytest.raises(LLMUnavailable):
        _provider(handler).complete("system", "user")


# --- transport -----------------------------------------------------------


def test_a_timeout_is_reported_as_a_timeout() -> None:
    with pytest.raises(LLMTimeout):
        _provider(_raising(httpx.ReadTimeout("too slow"))).complete("system", "user")


def test_an_unreachable_api_is_retryable() -> None:
    with pytest.raises(LLMUnavailable) as raised:
        _provider(_raising(httpx.ConnectError("refused"))).complete("system", "user")

    assert raised.value.retryable is True


# --- construction --------------------------------------------------------


def test_construction_without_a_key_fails_immediately() -> None:
    with pytest.raises(ValueError, match="api_key is not configured"):
        GeminiProvider(settings=GeminiSettings())


def test_it_satisfies_the_provider_protocol() -> None:
    handler, _ = _always(_envelope(VALID_ANSWER))
    provider: LLMProvider = _provider(handler)

    assert provider.name == "gemini"


def test_a_client_we_were_handed_is_left_alone() -> None:
    """How a TLS-intercepting network injects its own verified client."""

    client = httpx.Client(transport=httpx.MockTransport(_always(_envelope(VALID_ANSWER))[0]))
    GeminiProvider(settings=GeminiSettings(api_key="test-key"), client=client).close()

    assert client.is_closed is False


# --- the extractor on top of it ------------------------------------------


def test_the_extractor_drives_the_real_client_end_to_end() -> None:
    handler, _ = _always(
        _envelope(VALID_ANSWER, usage={"promptTokenCount": 496, "candidatesTokenCount": 59})
    )
    extractor = BasicExtractor(_provider(handler), sleep=lambda _: None)

    outcome = extractor.extract(SOURCE_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionSuccess)
    assert outcome.fields.title == "Send the Q3 report to finance"
    assert outcome.fields.source_ref == "MSG-4471"
    assert outcome.model == "gemini-3.6-flash"
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
    repair = json.loads(seen[1].content)["contents"][0]["parts"][0]["text"]
    assert "no deps field" in repair
    assert "has_dependencies" in repair


def test_high_demand_is_waited_out_without_spending_a_repair() -> None:
    """The run that matters on the free tier: 503, wait, succeed."""

    handler, _ = _scripted(
        httpx.Response(503, json={"error": {"message": "high demand"}}),
        httpx.Response(200, json=_envelope(VALID_ANSWER)),
    )
    waits: list[float] = []
    extractor = BasicExtractor(_provider(handler), sleep=waits.append)

    outcome = extractor.extract(SOURCE_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionSuccess)
    assert outcome.schema_attempts == 1
    assert outcome.provider_calls == 2
    assert waits == [1.0]


def test_a_quota_wall_fails_the_item_rather_than_hanging_the_run() -> None:
    handler, seen = _always({"error": {"code": 429, "message": "quota exceeded"}}, 429)
    extractor = BasicExtractor(_provider(handler), sleep=lambda _: None)

    outcome = extractor.extract(SOURCE_TEXT, SourceType.EMAIL)

    assert isinstance(outcome, ExtractionFailure)
    assert outcome.reason == "provider_error"
    assert len(seen) == 3  # the first call plus two transient retries


def test_a_cap_tight_enough_to_cut_everything_is_still_a_refusal() -> None:
    """Order matters: truncation is checked before emptiness.

    A cap small enough to stop the model before its first character leaves no
    text at all. Read as an empty completion that would be retryable — and
    retrying under the same cap is precisely what cannot work.
    """

    handler, _ = _always(_envelope("", finish_reason="MAX_TOKENS"))

    with pytest.raises(LLMRejected):
        _provider(handler, max_output_tokens=1).complete("system", "user")
