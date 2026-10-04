"""The extraction loop: call, parse, validate, repair, or give up cleanly.

This is where the awkward ten percent lives. An LLM returns usable JSON most
of the time; the engineering is in what happens the rest of the time. Two
promises hold here:

* `extract` never raises for a bad item. A failure is a value it returns,
  because one unusable message must not stall a pipeline.
* A repair round is a normal path, not an error path. The model gets its own
  rejected answer back together with the reason, which is the only way it can
  know what to change.
"""

import logging
import time
from collections.abc import Callable, Iterable, Iterator
from dataclasses import dataclass

from mind.contracts.base_enums import SourceType
from mind.extraction.confidence import ConfidenceBreakdown, score_confidence
from mind.extraction.config import ExtractionConfig
from mind.extraction.models import ExtractedFields
from mind.extraction.parsing import MalformedOutput, parse_response
from mind.extraction.prompts import extraction_prompt, repair_prompt, system_prompt
from mind.extraction.providers.base import (
    LLMError,
    LLMProvider,
    LLMRateLimited,
    LLMResponse,
)
from mind.observability.tracking import record_call

logger = logging.getLogger("mind.extraction")

# Why an item was given up on. `invalid_output` means the model answered but
# never produced the schema; `provider_error` means we never got an answer.
REASON_INVALID_OUTPUT = "invalid_output"
REASON_PROVIDER_ERROR = "provider_error"


@dataclass(frozen=True)
class ExtractionSuccess:
    """A validated proposal, ready for classification (#351) and effort (#352)."""

    fields: ExtractedFields
    source_type: SourceType
    confidence: ConfidenceBreakdown
    model: str

    # Rounds spent getting the schema right, and total calls made. They
    # differ when the provider itself misbehaved; only the first feeds the
    # confidence score, because a timeout says nothing about quality.
    schema_attempts: int
    provider_calls: int


@dataclass(frozen=True)
class ExtractionFailure:
    """An item that was given up on, recorded so the run can be audited."""

    source_type: SourceType
    reason: str
    detail: str
    schema_attempts: int
    provider_calls: int

    # The last thing the model said, kept for whoever debugs this later.
    last_output: str | None = None


ExtractionOutcome = ExtractionSuccess | ExtractionFailure


class BasicExtractor:
    """Turns plain text plus a source type into proposed task fields."""

    def __init__(
        self,
        provider: LLMProvider,
        config: ExtractionConfig | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._provider = provider
        self._config = config or ExtractionConfig()
        # Injected so tests exercise the backoff without waiting for it.
        self._sleep = sleep

    def extract(self, text: str, source_type: SourceType) -> ExtractionOutcome:
        """Extract one item. Returns a failure rather than raising one."""

        system = system_prompt()
        user = extraction_prompt(source_type, text)

        schema_attempts = 0
        provider_calls = 0
        transient_retries = 0
        last_output: str | None = None

        while True:
            provider_calls += 1
            try:
                response = self._call(system, user)
            except LLMError as exc:
                detail = f"{type(exc).__name__}: {exc}"
                exhausted = transient_retries >= self._config.max_transient_retries

                if not exc.retryable or exhausted:
                    return self._give_up(
                        source_type=source_type,
                        reason=REASON_PROVIDER_ERROR,
                        detail=detail,
                        schema_attempts=schema_attempts,
                        provider_calls=provider_calls,
                        last_output=last_output,
                    )

                logger.warning("extraction: transient provider failure, retrying: %s", detail)
                self._sleep(self._backoff(transient_retries, exc))
                transient_retries += 1
                continue

            schema_attempts += 1
            last_output = response.text

            try:
                fields = parse_response(response.text)
            except MalformedOutput as exc:
                detail = str(exc)

                if schema_attempts >= self._config.max_attempts:
                    return self._give_up(
                        source_type=source_type,
                        reason=REASON_INVALID_OUTPUT,
                        detail=detail,
                        schema_attempts=schema_attempts,
                        provider_calls=provider_calls,
                        last_output=last_output,
                    )

                logger.info("extraction: invalid output, repairing (%s)", detail)
                user = repair_prompt(source_type, text, response.text, detail)
                continue

            return ExtractionSuccess(
                fields=fields,
                source_type=source_type,
                confidence=score_confidence(fields, source_type, text, attempts=schema_attempts),
                model=response.model,
                schema_attempts=schema_attempts,
                provider_calls=provider_calls,
            )

    def extract_many(
        self,
        items: Iterable[tuple[str, SourceType]],
    ) -> Iterator[ExtractionOutcome]:
        """Extract a batch, yielding one outcome per item, failures included.

        The point of this method is what it does *not* do: nothing in here can
        end the run early.
        """

        for text, source_type in items:
            yield self.extract(text, source_type)

    def _call(self, system: str, user: str) -> LLMResponse:
        """Call the provider, timing it for the shared tracking point."""

        started = time.perf_counter()
        try:
            response = self._provider.complete(system, user)
        finally:
            duration_ms = (time.perf_counter() - started) * 1000
        # Cost stays 0.0 until provider pricing is verified against the
        # official price list; token counts are on the response meanwhile.
        record_call(model_name=self._provider.name, duration_ms=duration_ms, cost=0.0)
        return response

    def _backoff(self, transient_retries: int, error: LLMError) -> float:
        """How long to wait before repeating a call the provider refused."""

        if isinstance(error, LLMRateLimited) and error.retry_after_seconds is not None:
            wait = error.retry_after_seconds
        else:
            wait = self._config.backoff_seconds * (
                self._config.backoff_multiplier**transient_retries
            )
        return min(wait, self._config.max_backoff_seconds)

    def _give_up(
        self,
        source_type: SourceType,
        reason: str,
        detail: str,
        schema_attempts: int,
        provider_calls: int,
        last_output: str | None,
    ) -> ExtractionFailure:
        """Record a failed item and hand it back so the caller can move on."""

        logger.error(
            "extraction: giving up on a %s item after %d attempt(s): %s - %s",
            source_type.value,
            schema_attempts,
            reason,
            detail,
        )
        return ExtractionFailure(
            source_type=source_type,
            reason=reason,
            detail=detail,
            schema_attempts=schema_attempts,
            provider_calls=provider_calls,
            last_output=last_output,
        )
