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
from mind.extraction.grounding import ground_source_ref
from mind.extraction.models import ExtractedFields, response_model
from mind.extraction.parsing import MalformedOutput, parse_response
from mind.extraction.prompts import extraction_prompt, repair_prompt, system_prompt
from mind.extraction.providers.base import (
    LLMError,
    LLMProvider,
    LLMRateLimited,
    LLMResponse,
)
from mind.extraction.providers.pricing import estimate_cost
from mind.observability.tracking import CallKind, CallStatus, record_call

logger = logging.getLogger("mind.extraction")

# Why an item was given up on. `invalid_output` means the model answered but
# never produced the schema; `provider_error` means we never got an answer.
REASON_INVALID_OUTPUT = "invalid_output"
REASON_PROVIDER_ERROR = "provider_error"


@dataclass(frozen=True)
class ExtractionItem:
    """One thing to extract, and enough to find it again afterwards.

    A named shape rather than a tuple because `item_key` is the whole point
    of this issue's "record the failure" requirement: a three-tuple of
    `(str, SourceType, str | None)` reads as a puzzle at every call site.
    """

    text: str
    source_type: SourceType

    # The intake event's `object_key`: the one stored object this text came
    # from. Deliberately not `correlation_id`, which a source body shares
    # with its attachments and so cannot say *which* of them failed.
    item_key: str | None = None


@dataclass(frozen=True)
class ExtractionSuccess:
    """A validated proposal: the basic fields, plus the classification and the
    effort estimate (#351, #352) when the switch asked for them."""

    fields: ExtractedFields
    source_type: SourceType
    item_key: str | None
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
    item_key: str | None
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

    def extract(
        self,
        text: str,
        source_type: SourceType,
        item_key: str | None = None,
    ) -> ExtractionOutcome:
        """Extract one item. Returns a failure rather than raising one.

        `item_key` is what makes a recorded failure actionable: without it
        an audit of a run can say three items failed but not which three.
        It is optional because not every caller has an intake event behind
        it -- the smoke script and the tests do not.
        """

        # What we ask for and what we accept are the same decision, read
        # from one place: a prompt asking for the classification and a
        # schema without it would fail every answer it received.
        system = system_prompt(self._config.classify)
        schema = response_model(self._config.classify)
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
                        item_key=item_key,
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
                fields = parse_response(response.text, schema)
            except MalformedOutput as exc:
                detail = str(exc)

                if schema_attempts >= self._config.max_attempts:
                    return self._give_up(
                        source_type=source_type,
                        item_key=item_key,
                        reason=REASON_INVALID_OUTPUT,
                        detail=detail,
                        schema_attempts=schema_attempts,
                        provider_calls=provider_calls,
                        last_output=last_output,
                    )

                logger.info("extraction: invalid output, repairing (%s)", detail)
                user = repair_prompt(source_type, text, response.text, detail)
                continue

            # The content is untrusted, so a pointer it did not contain does
            # not travel. Done here because this is the one place holding
            # both the answer and the text it was supposed to come from.
            fields, invented_ref = ground_source_ref(fields, text)

            return ExtractionSuccess(
                fields=fields,
                source_type=source_type,
                item_key=item_key,
                confidence=score_confidence(
                    fields,
                    source_type,
                    text,
                    attempts=schema_attempts,
                    invented_source_ref=invented_ref,
                ),
                model=response.model,
                schema_attempts=schema_attempts,
                provider_calls=provider_calls,
            )

    def extract_many(
        self,
        items: Iterable[ExtractionItem],
    ) -> Iterator[ExtractionOutcome]:
        """Extract a batch, yielding one outcome per item, failures included.

        The point of this method is what it does *not* do: nothing in here can
        end the run early.
        """

        for item in items:
            yield self.extract(item.text, item.source_type, item.item_key)

    def _call(self, system: str, user: str) -> LLMResponse:
        """Call the provider, timing it for the shared tracking point."""

        started = time.perf_counter()
        try:
            response = self._provider.complete(system, user)
        except LLMError:
            # A call that failed is still a call, and the failures are the
            # slowest ones we make: a timeout waits out the whole deadline
            # before raising. Recording only the successes would leave the
            # latency figures describing a better service than this one.
            #
            # There is no answer to read a model id off, so the provider
            # names itself, and the cost is None: no usage was reported, so
            # it is unknown -- not known to be free.
            record_call(
                model_name=self._provider.name,
                duration_ms=(time.perf_counter() - started) * 1000,
                cost=None,
                kind=CallKind.LLM,
                status=CallStatus.ERROR,
            )
            raise

        duration_ms = (time.perf_counter() - started) * 1000
        # The model id comes off the response rather than off the provider:
        # a benchmark comparing two DeepSeek models needs to tell them apart,
        # and only the answer knows which one produced it.
        record_call(
            model_name=response.model or self._provider.name,
            duration_ms=duration_ms,
            # None for an unpriced model or a provider that reported no
            # usage: unknown, which the cost report keeps apart from free.
            cost=estimate_cost(
                response.model,
                response.prompt_tokens,
                response.completion_tokens,
            ),
            kind=CallKind.LLM,
            prompt_tokens=response.prompt_tokens,
            completion_tokens=response.completion_tokens,
        )
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
        item_key: str | None,
        reason: str,
        detail: str,
        schema_attempts: int,
        provider_calls: int,
        last_output: str | None,
    ) -> ExtractionFailure:
        """Record a failed item and hand it back so the caller can move on."""

        logger.error(
            "extraction: giving up on %s (%s) after %d attempt(s): %s - %s",
            item_key or "an item with no key",
            source_type.value,
            schema_attempts,
            reason,
            detail,
        )
        return ExtractionFailure(
            source_type=source_type,
            item_key=item_key,
            reason=reason,
            detail=detail,
            schema_attempts=schema_attempts,
            provider_calls=provider_calls,
            last_output=last_output,
        )
