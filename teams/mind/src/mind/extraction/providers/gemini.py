"""The Gemini client: `generateContent`, translated into the extractor's terms.

Gemini is the comparison provider named in #350, and on a network where
`api.deepseek.com` is filtered it is the only one that can actually be run.
Its API is not OpenAI-shaped, so this is not a copy of `deepseek.py` with the
URL swapped — the system prompt is its own top-level field, the answer arrives
as a list of parts, and the retry hint lives in the error body rather than in a
header.

Three choices worth stating:

* `responseMimeType: "application/json"` is requested, for the same reason
  DeepSeek is asked for JSON mode: it removes most malformed answers without
  making the repair loop optional.
* `thinkingConfig.thinkingBudget` defaults to 0. This is not a micro
  optimisation. Measured against the live API, the same trivial prompt took
  101 seconds with thinking left on and ~1.4 seconds with it off; extraction
  asks for four short fields and has nothing to reason about. It is settable
  because not every model accepts the field — one returned 400 for a body the
  others took — so `None` leaves it out entirely.
* An answer that was *blocked* is a refusal, not a hiccup. Sending the same
  prompt again gets it blocked again, so it must not be retried.

Status mapping, and whether the extractor will try again:

    read/connect timeout                   LLMTimeout      retried
    connection refused, DNS, TLS           LLMUnavailable  retried
    429 RESOURCE_EXHAUSTED (quota)         LLMRateLimited  retried
    503 UNAVAILABLE ("high demand")        LLMUnavailable  retried
    5xx                                    LLMUnavailable  retried
    body is not the envelope we need       LLMUnavailable  retried
    401, 403 PERMISSION_DENIED             LLMAccessDenied not retried
    404 (model not available to this key)  LLMAccessDenied not retried
    400 INVALID_ARGUMENT, 422, other 4xx   LLMRejected     not retried
    the answer was blocked (safety)        LLMRejected     not retried

Every row but the last two is `environmental`: no other item would pass
either, so the pipeline stops rather than skipping the item (#919).

503 and 404 are not hypothetical: on the free tier the newest models answer
"currently experiencing high demand" routinely, and a key issued today cannot
call the previous model generation at all.
"""

import logging
import re
from typing import Any, Self

import httpx

from mind.extraction.providers.base import (
    LLMAccessDenied,
    LLMRateLimited,
    LLMRejected,
    LLMResponse,
    LLMTimeout,
    LLMUnavailable,
)
from mind.extraction.providers.http import as_int, clip, error_detail, error_payload
from mind.extraction.providers.settings import GeminiSettings, load_gemini_settings

logger = logging.getLogger("mind.extraction.providers")

# Gemini reports a quota wait as a duration string ("21s") inside the error
# details, not as a Retry-After header.
_RETRY_DELAY = re.compile(r"^(?P<seconds>\d+(?:\.\d+)?)s$")

# Finish reasons that mean the model was stopped on purpose. Repeating the
# same prompt reproduces them, so they are refusals rather than failures.
_BLOCKED_FINISH_REASONS = frozenset({"SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII"})

# The key, or a model this key may not call: the account's fault, not the item's.
_ACCESS_STATUSES = frozenset({401, 403, 404})
_REJECTING_STATUSES = frozenset({400, 422})


class GeminiProvider:
    """A synchronous Gemini `generateContent` client."""

    name = "gemini"

    def __init__(
        self,
        settings: GeminiSettings | None = None,
        client: httpx.Client | None = None,
    ) -> None:
        """Build a client. Pass `client` in tests to supply a mock transport.

        A caller on a TLS-intercepting network passes its own client too: the
        workaround belongs to that machine, not to this module.
        """

        self._settings = settings if settings is not None else load_gemini_settings()

        if not self._settings.configured:
            raise ValueError(
                "Gemini api_key is not configured: expected llm.gemini.api_key in the "
                "mounted secrets.yaml (see mind.extraction.providers.settings)"
            )

        self._owns_client = client is None
        self._client = client or httpx.Client(
            timeout=httpx.Timeout(self._settings.timeout_seconds)
        )

    @property
    def model(self) -> str:
        """The model id this instance sends, for logs and cost lookups."""

        return self._settings.model

    def complete(self, system: str, user: str) -> LLMResponse:
        """Send one prompt pair and return the raw text of the answer."""

        try:
            response = self._client.post(
                self._settings.generate_url,
                # The key goes in a header, never in the query string: a URL
                # is logged by every proxy it passes through.
                headers={
                    "x-goog-api-key": self._settings.api_key,
                    "Content-Type": "application/json",
                },
                json=self._body(system, user),
            )
        except httpx.TimeoutException as exc:
            raise LLMTimeout(
                f"Gemini did not answer within {self._settings.timeout_seconds}s"
            ) from exc
        except httpx.HTTPError as exc:
            raise LLMUnavailable(f"could not reach Gemini: {type(exc).__name__}: {exc}") from exc

        if response.status_code != httpx.codes.OK:
            self._raise_for_status(response)

        return self._read_completion(response)

    def close(self) -> None:
        """Close the HTTP client, if this provider owns it."""

        if self._owns_client:
            self._client.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    def _body(self, system: str, user: str) -> dict[str, Any]:
        """The request body. Kept in one place so tests can assert on it."""

        generation: dict[str, Any] = {
            "temperature": self._settings.temperature,
            "maxOutputTokens": self._settings.max_output_tokens,
            "responseMimeType": "application/json",
        }
        if self._settings.thinking_budget is not None:
            generation["thinkingConfig"] = {"thinkingBudget": self._settings.thinking_budget}

        return {
            # Gemini takes the system prompt as its own field rather than as a
            # message with a role, which is the main shape difference from an
            # OpenAI-style API.
            "system_instruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "generationConfig": generation,
        }

    def _raise_for_status(self, response: httpx.Response) -> None:
        """Turn a non-200 into the right `LLMError`. Never returns."""

        status = response.status_code
        detail = error_detail(response)

        if status == httpx.codes.TOO_MANY_REQUESTS:
            raise LLMRateLimited(f"Gemini quota exhausted ({status}): {detail}", _retry_delay(response))

        if status >= httpx.codes.INTERNAL_SERVER_ERROR:
            # 503 "experiencing high demand" lands here, which is the common
            # case on the free tier rather than the rare one.
            raise LLMUnavailable(f"Gemini is unavailable ({status}): {detail}")

        if status in _ACCESS_STATUSES:
            # 404 usually means this key may not call this model at all, not
            # that the URL is wrong -- retrying cannot fix either one.
            raise LLMAccessDenied(f"Gemini refused the account ({status}): {detail}")

        if status in _REJECTING_STATUSES:
            raise LLMRejected(f"Gemini refused the request ({status}): {detail}")

        raise LLMRejected(f"unexpected Gemini status {status}: {detail}")

    def _read_completion(self, response: httpx.Response) -> LLMResponse:
        """Pull the answer text and the token usage out of the envelope."""

        try:
            payload = response.json()
        except ValueError as exc:
            raise LLMUnavailable(
                f"Gemini returned a body that is not JSON: {clip(response.text)}"
            ) from exc

        if not isinstance(payload, dict):
            raise LLMUnavailable(f"Gemini returned {type(payload).__name__}, not an object")

        candidates = payload.get("candidates")
        if not isinstance(candidates, list) or not candidates:
            # No candidates at all means the *prompt* was rejected before the
            # model ever answered; the reason is reported separately.
            feedback = payload.get("promptFeedback")
            reason = feedback.get("blockReason") if isinstance(feedback, dict) else None
            if reason:
                raise LLMRejected(f"Gemini blocked the prompt ({reason})")
            raise LLMUnavailable("Gemini returned no candidates")

        first = candidates[0] if isinstance(candidates[0], dict) else {}
        finish_reason = first.get("finishReason")

        if finish_reason in _BLOCKED_FINISH_REASONS:
            raise LLMRejected(f"Gemini blocked the answer ({finish_reason})")

        if finish_reason == "MAX_TOKENS":
            # Refused for the same reason as in deepseek.py: the answer is cut
            # mid-object, and asking again under the same cap reproduces it.
            raise LLMRejected(
                f"Gemini stopped at maxOutputTokens={self._settings.max_output_tokens}, "
                "leaving the answer truncated; raise max_output_tokens or shorten "
                "the prompt -- repeating this call will cut it in the same place"
            )

        text = _candidate_text(first)
        if not text.strip():
            raise LLMUnavailable("Gemini returned an empty completion")

        usage = payload.get("usageMetadata")
        usage = usage if isinstance(usage, dict) else {}

        # Thinking is billed at the output price, so it belongs in the
        # completion count. Not every model honours thinkingBudget 0:
        # gemini-3.8-flash thought 237 tokens for a one-word answer. With no
        # answer count the total is unknown, not just the thinking: None.
        answer_tokens = as_int(usage.get("candidatesTokenCount"))
        thought_tokens = as_int(usage.get("thoughtsTokenCount")) or 0

        return LLMResponse(
            text=text,
            model=str(payload.get("modelVersion") or self._settings.model),
            prompt_tokens=as_int(usage.get("promptTokenCount")),
            completion_tokens=None if answer_tokens is None else answer_tokens + thought_tokens,
        )


def _candidate_text(candidate: dict[str, Any]) -> str:
    """Join the answer's parts.

    Gemini may split one answer across several parts, so taking `parts[0]`
    would silently truncate a long object to its opening brace.
    """

    content = candidate.get("content")
    if not isinstance(content, dict):
        return ""

    parts = content.get("parts")
    if not isinstance(parts, list):
        return ""

    return "".join(
        part["text"] for part in parts if isinstance(part, dict) and isinstance(part.get("text"), str)
    )


def _retry_delay(response: httpx.Response) -> float | None:
    """Read the quota wait Gemini attaches to a 429, if it sent one.

    It arrives as a `RetryInfo` entry in `error.details` carrying a duration
    string such as `"21s"`. Anything that is not that exact shape is ignored
    rather than half-parsed: the extractor's own backoff is a fine fallback.
    """

    details = error_payload(response).get("details")
    if not isinstance(details, list):
        return None

    for entry in details:
        if not isinstance(entry, dict):
            continue
        match = _RETRY_DELAY.match(str(entry.get("retryDelay", "")))
        if match:
            return float(match.group("seconds"))

    return None
