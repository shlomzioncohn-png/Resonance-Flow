"""The DeepSeek client: one HTTP call, translated into the extractor's terms.

DeepSeek's API is OpenAI-shaped, so what happens here is a POST to
`/chat/completions`. Three choices are worth stating out loud:

* `response_format: {"type": "json_object"}` is requested, because it removes
  most of the malformed-output cases the repair loop exists to catch. It is
  belt and braces, not a replacement for the loop: the tests still drive the
  loop with broken answers, since a provider can drop JSON mode under load
  and a syntactically valid object can still fail our schema.
* Every failure is translated into an `LLMError` subclass *here*, at the edge.
  Nothing above this module ever sees an `httpx` exception or an HTTP status
  code, which is what keeps the decision of what to retry in one place.
* An unreadable body is treated as a retryable provider failure rather than a
  bad answer. A proxy or a content filter standing between us and the API
  returns HTML with a 2xx more often than you would hope, and handing that to
  the repair loop would spend the whole attempt budget asking a model to fix
  something it never said.

Status mapping, and whether the extractor will try again:

    read/connect timeout                   LLMTimeout      retried
    connection refused, DNS, TLS           LLMUnavailable  retried
    429                                    LLMRateLimited  retried
    5xx                                    LLMUnavailable  retried
    body is not the envelope we need       LLMUnavailable  retried
    401, 403 (key), 402 (out of credit)    LLMRejected     not retried
    400, 404, 422, any other 4xx           LLMRejected     not retried

DeepSeek's JSON mode requires the word "json" to appear in the prompt. The
system prompt says "Return a single JSON object", so that holds today - check
it again if the prompt is ever rewritten.
"""

import logging
from typing import Any, Self

import httpx

from mind.extraction.providers.base import (
    LLMRateLimited,
    LLMRejected,
    LLMResponse,
    LLMTimeout,
    LLMUnavailable,
)
from mind.extraction.providers.http import as_int, clip, error_detail
from mind.extraction.providers.settings import DeepSeekSettings, load_deepseek_settings

logger = logging.getLogger("mind.extraction.providers")

# 402 is DeepSeek's "insufficient balance". It belongs with the refusals on
# purpose: the call will keep failing until somebody tops the account up, so
# retrying it only burns the attempt budget and hides the real problem.
_REJECTING_STATUSES = frozenset({400, 401, 402, 403, 404, 422})


class DeepSeekProvider:
    """A synchronous DeepSeek chat-completions client.

    Synchronous because `LLMProvider.complete` is, and that is not an
    oversight: extraction runs one item at a time behind a Kafka consumer, so
    concurrency belongs to whoever owns the consumer loop, not here.
    """

    name = "deepseek"

    def __init__(
        self,
        settings: DeepSeekSettings | None = None,
        client: httpx.Client | None = None,
    ) -> None:
        """Build a client. Pass `client` in tests to supply a mock transport.

        Refusing to construct without a key is deliberate: a provider that
        exists but 401s on every call looks like a flaky API from upstream,
        and the real problem - an unmounted secret - would take an hour to
        find.
        """

        self._settings = settings if settings is not None else load_deepseek_settings()

        if not self._settings.configured:
            raise ValueError(
                "DeepSeek api_key is not configured: expected llm.deepseek.api_key in the "
                "mounted secrets.yaml (see mind.extraction.providers.settings)"
            )

        # Only a client we created is a client we may close.
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
                self._settings.completions_url,
                headers={
                    "Authorization": f"Bearer {self._settings.api_key}",
                    "Content-Type": "application/json",
                },
                json=self._body(system, user),
            )
        except httpx.TimeoutException as exc:
            raise LLMTimeout(
                f"DeepSeek did not answer within {self._settings.timeout_seconds}s"
            ) from exc
        except httpx.HTTPError as exc:
            # Connection refused, DNS failure, TLS rejection, a socket
            # dropped mid-read: all worth repeating, none worth an httpx
            # exception escaping into the extractor.
            raise LLMUnavailable(f"could not reach DeepSeek: {type(exc).__name__}: {exc}") from exc

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

        return {
            "model": self._settings.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "response_format": {"type": "json_object"},
            "temperature": self._settings.temperature,
            "max_tokens": self._settings.max_output_tokens,
            # Streaming would buy nothing here: the answer is four small
            # fields and nobody is watching it arrive.
            "stream": False,
        }

    def _raise_for_status(self, response: httpx.Response) -> None:
        """Turn a non-200 into the right `LLMError`. Never returns."""

        status = response.status_code
        detail = error_detail(response)

        if status == httpx.codes.TOO_MANY_REQUESTS:
            retry_after = _retry_after_seconds(response)
            raise LLMRateLimited(f"DeepSeek rate limit ({status}): {detail}", retry_after)

        if status >= httpx.codes.INTERNAL_SERVER_ERROR:
            raise LLMUnavailable(f"DeepSeek is failing ({status}): {detail}")

        if status in _REJECTING_STATUSES:
            raise LLMRejected(f"DeepSeek refused the request ({status}): {detail}")

        # An unmapped 4xx. Treated as non-retryable: an unrecognised refusal
        # is far more likely to be our mistake than a passing glitch.
        raise LLMRejected(f"unexpected DeepSeek status {status}: {detail}")

    def _read_completion(self, response: httpx.Response) -> LLMResponse:
        """Pull the answer text and the token usage out of the envelope."""

        try:
            payload = response.json()
        except ValueError as exc:
            raise LLMUnavailable(
                f"DeepSeek returned a body that is not JSON: {clip(response.text)}"
            ) from exc

        if not isinstance(payload, dict):
            raise LLMUnavailable(f"DeepSeek returned {type(payload).__name__}, not an object")

        choices = payload.get("choices")
        if not isinstance(choices, list) or not choices:
            raise LLMUnavailable("DeepSeek returned no completion")

        first = choices[0] if isinstance(choices[0], dict) else {}
        message = first.get("message")
        text = message.get("content") if isinstance(message, dict) else None
        if not isinstance(text, str) or not text.strip():
            # An empty completion is the provider failing to answer, not the
            # model answering badly, so it is retried rather than repaired.
            raise LLMUnavailable("DeepSeek returned an empty completion")

        if first.get("finish_reason") == "length":
            # Refused rather than returned. The JSON is cut mid-object and
            # will not parse, and handing it to the repair loop would ask the
            # model to fix it under the same cap that cut it -- three paid
            # calls, three identical fragments, and the item dropped at the
            # end. The fix is a setting, so the message names the setting.
            raise LLMRejected(
                f"DeepSeek stopped at max_tokens={self._settings.max_output_tokens}, "
                "leaving the answer truncated; raise max_output_tokens or shorten "
                "the prompt -- repeating this call will cut it in the same place"
            )

        usage = payload.get("usage")
        usage = usage if isinstance(usage, dict) else {}

        return LLMResponse(
            text=text,
            model=str(payload.get("model") or self._settings.model),
            prompt_tokens=as_int(usage.get("prompt_tokens")),
            completion_tokens=as_int(usage.get("completion_tokens")),
        )


def _retry_after_seconds(response: httpx.Response) -> float | None:
    """Read `Retry-After`, believing it only when it is a plain number.

    The header may also hold an HTTP date. That form is not parsed here: the
    extractor's own backoff is a fine fallback, and a half-right wait is
    worse than a known default.
    """

    raw = response.headers.get("Retry-After")
    if raw is None:
        return None
    try:
        seconds = float(raw)
    except ValueError:
        logger.info("DeepSeek sent an unparseable Retry-After (%r); using our own backoff", raw)
        return None
    return seconds if seconds >= 0 else None
