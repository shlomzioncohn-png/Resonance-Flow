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
from mind.extraction.grounding import ground_dependency_mentions, ground_source_ref
from mind.extraction.models import MAX_TASKS_PER_TEXT, ExtractedFields, response_model
from mind.extraction.parsing import (
    MalformedOutput,
    RejectedAnswer,
    parse_response,
    read_rejected_answer,
)
from mind.extraction.prompts import (
    content_cap,
    extraction_prompt,
    repair_prompt,
    system_prompt,
)
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
class ExtractedTask:
    """One task from the answer: its fields and how far to trust them.

    The basic fields, plus the classification and the effort estimate
    (#351, #352) when the switch asked for them. Grounded and scored on its
    own, so a pointer one task invented costs that task alone.
    """

    fields: ExtractedFields
    confidence: ConfidenceBreakdown


@dataclass(frozen=True)
class ExtractionSuccess:
    """A validated answer: every task the text describes (#690), possibly none.

    What belongs to the answer rather than to one task stays out here: the
    item it came from, the model, and the rounds spent. Which tasks paid for
    a repair round is decided per task (`_rounds_needed`), so the rounds here
    and the penalty on a task can differ.
    """

    tasks: tuple[ExtractedTask, ...]
    source_type: SourceType
    item_key: str | None
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


def _rounds_needed(
    fields: ExtractedFields,
    earlier_valid: list[tuple[ExtractedFields, int]],
    schema_attempts: int,
) -> int:
    """The rounds this task took: the first in which it was valid unchanged.

    A repair round re-asks for the whole list, so a task that was already
    valid comes back again. When it comes back equal in every field, the
    repair was not for it and it is not charged. A task the model rewrote is
    charged in full: it cannot be told apart from a new one. Partial repair
    — sending back only the failed tasks — would close that gap; see the
    README.
    """

    for task, round_number in earlier_valid:
        if task == fields:
            return round_number
    return schema_attempts


def _log_repair_outcome(rejected: RejectedAnswer | None, answer: list[ExtractedFields]) -> None:
    """Compare the repaired answer with the last rejected one, and say how.

    Only the last round: a task valid in two rejected rounds is one task, not
    two. How often valid tasks come back reworded decides whether partial
    repair is worth building, so it is logged rather than guessed.

    A warning when a valid task may have gone missing: #690's third decision
    is that a bad task is never dropped beside a good one, and a model that
    answers the repair with fewer tasks can break it with nothing raising.
    Dropping the *invalid* task is expected, since the title message tells
    the model to leave a non-task out, so that alone stays quiet.
    """

    if rejected is None:
        return
    if not rejected.readable:
        logger.info(
            "extraction: the rejected answer could not be read; the repaired answer has %d",
            len(answer),
        )
        return
    if rejected.task_count > MAX_TASKS_PER_TEXT:
        logger.info(
            "extraction: the rejected answer had %d task(s), over the cap of %d; "
            "the repaired answer has %d",
            rejected.task_count,
            MAX_TASKS_PER_TEXT,
            len(answer),
        )
        return

    unchanged = sum(1 for task in rejected.valid if task in answer)
    logger.info(
        "extraction: the rejected answer had %d task(s), %d of them valid; the repaired "
        "answer has %d, and %d of the valid ones came back unchanged",
        rejected.task_count,
        len(rejected.valid),
        len(answer),
        unchanged,
    )

    fewer = rejected.task_count - len(answer)
    not_back = len(rejected.valid) - unchanged
    if fewer > 0 and not_back > 0:
        logger.warning(
            "extraction: the repaired answer has %d task(s) fewer, and %d valid task(s) did "
            "not come back unchanged; a valid task may have been dropped",
            fewer,
            not_back,
        )


def _ground_and_score(
    fields: ExtractedFields,
    source_type: SourceType,
    text: str,
    attempts: int,
    sibling_titles: list[str],
) -> ExtractedTask:
    """Check one task's pointer and mentions against the content, then score it.

    The content is untrusted, so a pointer or a mention it did not contain
    does not travel. Done here because this is the one place holding both
    the answer and the text it was supposed to come from.

    `text` is what the model was sent, not the whole item: past the cut it
    could not have read anything, so a match there is luck, not evidence.
    """

    fields, invented_ref = ground_source_ref(fields, text)
    fields = ground_dependency_mentions(fields, text, sibling_titles)
    return ExtractedTask(
        fields=fields,
        confidence=score_confidence(
            fields,
            source_type,
            text,
            attempts=attempts,
            invented_source_ref=invented_ref,
        ),
    )


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
        # What the model can see, and so all that grounding may accept.
        seen = text[: content_cap(source_type)]
        schema = response_model(self._config.classify)
        user = extraction_prompt(source_type, text)

        schema_attempts = 0
        provider_calls = 0
        transient_retries = 0
        last_output: str | None = None
        # Each task that passed on its own in a rejected round, with that
        # round's number: what lets a repair round be charged per task.
        earlier_valid: list[tuple[ExtractedFields, int]] = []
        last_rejected: RejectedAnswer | None = None

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
                answer = parse_response(response.text, schema)
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

                last_rejected = read_rejected_answer(response.text, schema)
                # Over the cap, the model was listing non-tasks, so none of
                # what it listed counts as right the first time.
                if last_rejected.task_count <= MAX_TASKS_PER_TEXT:
                    earlier_valid += [(task, schema_attempts) for task in last_rejected.valid]
                logger.info("extraction: invalid output, repairing (%s)", detail)
                user = repair_prompt(source_type, text, response.text, detail)
                continue

            _log_repair_outcome(last_rejected, answer)
            return ExtractionSuccess(
                tasks=tuple(
                    _ground_and_score(
                        fields,
                        source_type,
                        seen,
                        _rounds_needed(fields, earlier_valid, schema_attempts),
                        [other.title for other in answer if other is not fields],
                    )
                    for fields in answer
                ),
                source_type=source_type,
                item_key=item_key,
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
            # Recorded under the model id the provider sends, like a success
            # below, so a model's failures and successes are one row of the
            # summary -- and its p95 includes the slow failures. The cost is
            # None: no usage was reported, so it is unknown, not free.
            record_call(
                model_name=self._provider.model,
                duration_ms=(time.perf_counter() - started) * 1000,
                cost=None,
                kind=CallKind.LLM,
                status=CallStatus.ERROR,
            )
            raise

        duration_ms = (time.perf_counter() - started) * 1000
        # Recorded under the model id the provider sends -- the key stays
        # stable even if the API answers an alias with a versioned id -- but
        # priced by the id the answer reports, the model actually billed.
        # Two configured models (a benchmark comparing two DeepSeek models)
        # are still two rows: each provider sends its own id.
        record_call(
            model_name=self._provider.model,
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
