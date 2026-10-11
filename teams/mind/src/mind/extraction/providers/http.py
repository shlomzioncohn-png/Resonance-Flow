"""The bits of HTTP handling every provider client repeats.

Factored out when the second provider arrived, not before: DeepSeek and Gemini
both answer an error as `{"error": {"message": ...}}`, and two copies of that
reading would drift the first time one of them changed.

What is deliberately *not* here: anything about which failures are worth
repeating. That judgement differs per provider — DeepSeek puts its wait in a
`Retry-After` header, Gemini puts it in the error body — and it belongs next to
the status mapping it serves, not in a shared bag of helpers.
"""

from typing import Any

import httpx

# Provider error bodies are quoted into logs and into failure records. A block
# page or a stack trace can be enormous, and the first part is always the
# informative part.
MAX_ERROR_DETAIL_CHARS = 300


def clip(text: str) -> str:
    """Shorten a body for a log line, marking that it was shortened."""

    collapsed = " ".join(text.split())
    if len(collapsed) <= MAX_ERROR_DETAIL_CHARS:
        return collapsed
    return collapsed[:MAX_ERROR_DETAIL_CHARS] + " [...]"


def error_payload(response: httpx.Response) -> dict[str, Any]:
    """The `error` object from a provider's error body, or an empty dict.

    Returned as well as read because a caller may want more than the message:
    Gemini hangs its retry hint off `error.details`.
    """

    try:
        payload = response.json()
    except ValueError:
        return {}

    if not isinstance(payload, dict):
        return {}

    error = payload.get("error")
    return error if isinstance(error, dict) else {}


def error_detail(response: httpx.Response) -> str:
    """The provider's own explanation, if it gave one.

    Anything that is not the documented error shape — an HTML page from a
    gateway, most often — falls back to the clipped raw body, which is what
    makes an interception visible in the logs instead of looking like an API
    bug.
    """

    message = error_payload(response).get("message")
    if message:
        return clip(str(message))
    return clip(response.text)


def as_int(value: object) -> int | None:
    """Token counts are optional, and a non-numeric one is simply absent.

    The `bool` check is not redundant: in Python `isinstance(True, int)` is
    true, and `True` as a token count would silently become 1.
    """

    return value if isinstance(value, int) and not isinstance(value, bool) else None
