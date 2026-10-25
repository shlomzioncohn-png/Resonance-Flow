"""Internal models for the extraction stage: the basic task fields (#350),
the classification (#351) and the effort estimate (#352).

These are *not* contracts. `TaskCreationRequested` (mind.contracts) is the
outbound contract; the models here describe the narrower shape we ask an LLM
for, before the locally-known fields are merged in.
"""

import logging
import math
import re
from datetime import time
from enum import StrEnum
from typing import Self

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    ValidationInfo,
    ValidatorFunctionWrapHandler,
    field_validator,
    model_validator,
)

from mind.contracts.task_enums import RoleContext, TaskTier
from mind.extraction.deadline import OffsetUnit, PeriodEnd, Weekday

logger = logging.getLogger("mind.extraction")

# Titles longer than this are treated as malformed rather than truncated:
# a runaway title usually means the model echoed the input instead of
# summarising it, and that is worth a repair attempt.
MAX_TITLE_LENGTH = 200

# Past this a description is the content quoted back, not a summary of it
# (#690). Dropped rather than repaired: the field is optional, and one long
# description must not cost a repair round of a whole meeting. The prompt asks
# for about half of this; that is what keeps the answer under the output cap,
# since this check only runs once the answer is written.
MAX_DESCRIPTION_LENGTH = 600

# Named dependencies kept per task (#690). A task waiting on more than this
# many named pieces of work is almost always a model listing every noun.
MAX_MENTIONS_PER_TASK = 10

# Strings an LLM emits when it means "nothing here". Normalised to None so
# that a missing value looks missing to the confidence heuristic instead of
# masquerading as evidence.
_NULL_PLACEHOLDERS = frozenset(
    {"", "-", "--", "n/a", "na", "none", "null", "nil", "unknown", "tbd", "not specified"}
)


class EffortBasis(StrEnum):
    """Where an effort estimate came from. Internal: it never reaches Inbox.

    The model reports which kind of evidence it had, and the confidence the
    contract carries is derived from that here, rather than being a number the
    model writes about itself.
    """

    # The content says how long it takes: "should take about an hour".
    STATED = "STATED"
    # The content says what the work is, and the duration follows from it:
    # "fix the typo on the login page".
    INFERRED = "INFERRED"


# What each categorical field may contain, written as the model must write it.
# Derived from the enums rather than listed again, so a value Inbox adds shows
# up here without anyone remembering to copy it.
_PERMITTED_VALUES: dict[str, frozenset[str]] = {
    "tier": frozenset(member.value for member in TaskTier),
    "role_context": frozenset(member.value for member in RoleContext),
    "effort_estimate_basis": frozenset(member.value for member in EffortBasis),
}

URGENCY_RANGE = range(1, 6)

# A month of working time. The product works in short free windows, so a longer
# estimate is a model error or a project, not a task. Far below the int32 that
# effort_estimate_minutes is on the wire.
MAX_EFFORT_MINUTES = 9600

# ASCII digits only, spelled out rather than \d: \d also matches "٣" and other
# scripts' digits, which float() would then happily read.
_PLAIN_NUMBER = re.compile(r"-?[0-9]+(\.[0-9]+)?")


def _whole_number(
    field: str, value: object, written: object, round_fractions: bool = False
) -> int | None:
    """Read a whole number however the model wrote it, or log and give up.

    Shared by urgency and the effort estimate, so that what counts as "a
    whole number" is decided in one place and cannot drift between them.
    """

    # A quoted number is read as the number it quotes, and only when it is
    # written the plain way: an optional minus, decimal digits, an optional
    # decimal point. Python's own float() is more lenient than that — it takes
    # "0_3", "1e0" and "nan" — and that leniency is not ours to inherit.
    if isinstance(value, str) and _PLAIN_NUMBER.fullmatch(value):
        value = float(value)

    # bool is an int in Python, and `True` would read as 1.
    if isinstance(value, bool):
        number = None
    elif isinstance(value, int):
        number = value
    # What decides is the value, not its spelling: 3.0 is three. A fraction
    # is the caller's call — 3.5 urgency sits between two answers, 45.5
    # minutes does not. Halves go up, as "rounded" reads: Python's round()
    # sends them to the even neighbour, so 0.5 would become 0 and 2.5 would
    # become 2. isfinite() comes first because floor() raises on inf and nan
    # rather than returning something to reject.
    elif (
        isinstance(value, float)
        and math.isfinite(value)
        and (round_fractions or value.is_integer())
    ):
        number = math.floor(value + 0.5)
    else:
        number = None

    if number is None:
        logger.warning(
            "extraction: %s came back as %r, which is not a whole number", field, written
        )
    return number


def _usable_minutes(value: object, written: object) -> int | None:
    """A whole number of minutes from 1 to MAX_EFFORT_MINUTES, or None."""

    number = _whole_number("effort_estimate_minutes", value, written, round_fractions=True)
    if number is None or 0 < number <= MAX_EFFORT_MINUTES:
        return number

    logger.warning(
        "extraction: effort_estimate_minutes came back as %r, which is not a number of "
        "minutes from 1 to %d; recording it as null",
        written,
        MAX_EFFORT_MINUTES,
    )
    return None


def _usable_urgency(value: object, written: object = None) -> int | None:
    """Read urgency as a whole number inside the scale, or give up on it.

    Deliberately not clamped. A 7 might mean "past the top of your scale", in
    which case 5 is right — but it might equally mean the model scored on 1 to
    10, where 7 is middling. The two are indistinguishable from here, and the
    cost of guessing wrong is not symmetric: a missing urgency is visibly
    missing, while a clamped one is a plausible-looking number that quietly
    inflates every downstream ranking.
    """

    number = _whole_number("urgency", value, written)
    if number is None:
        return None

    if number in URGENCY_RANGE:
        return number

    logger.warning(
        "extraction: urgency came back as %r, outside %d-%d; recording it as null rather "
        "than clamping, because a different scale and an off-the-top score look the same here",
        written,
        URGENCY_RANGE.start,
        URGENCY_RANGE.stop - 1,
    )
    return None


class DeadlineKind(StrEnum):
    """The four shapes a deadline is written in, one resolver each (deadline.py)."""

    # "by October 20", "on the 20th of October 2026 at 14:00"
    DATE = "DATE"
    # "within 72 hours", "in 3 working days", "tomorrow" (one day)
    OFFSET = "OFFSET"
    # "by Friday", "next Tuesday at 10:00"
    WEEKDAY = "WEEKDAY"
    # "today", "by the end of the week / next week / the month"
    PERIOD_END = "PERIOD_END"


# What each kind cannot do without. Anything else is optional: a year or a
# time that was never written is simply absent.
_DEADLINE_NEEDS: dict[DeadlineKind, tuple[str, ...]] = {
    DeadlineKind.DATE: ("month", "day"),
    DeadlineKind.OFFSET: ("amount", "unit"),
    DeadlineKind.WEEKDAY: ("weekday",),
    DeadlineKind.PERIOD_END: ("period",),
}

# Long enough for "by the end of the working day on Thursday the 29th"; a
# longer "phrase" is a sentence, not the words the deadline was read from.
MAX_DEADLINE_PHRASE_LENGTH = 100


class StatedDeadline(BaseModel):
    """What the content says about when the task is due (#960), as written.

    The model reports it and does no date arithmetic; it is resolved to a
    moment later, against when the text was written (deadline.py). Internal:
    only that moment reaches Inbox. `phrase` is the words it was read from,
    kept so it can be checked against the content.
    """

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    phrase: str = Field(min_length=1, max_length=MAX_DEADLINE_PHRASE_LENGTH)
    kind: DeadlineKind

    # DATE. The year only when the content writes one.
    month: int | None = Field(default=None, ge=1, le=12)
    day: int | None = Field(default=None, ge=1, le=31)
    year: int | None = None
    # DATE and WEEKDAY, only when the content names a time: "17:00".
    time_of_day: time | None = None
    # OFFSET
    amount: int | None = Field(default=None, gt=0)
    unit: OffsetUnit | None = None
    # WEEKDAY
    weekday: Weekday | None = None
    next_week: bool = False
    # PERIOD_END
    period: PeriodEnd | None = None

    @field_validator("kind", "unit", "weekday", "period", mode="before")
    @classmethod
    def words_in_any_case(cls, value: object) -> object:
        return value.strip().upper() if isinstance(value, str) else value

    @model_validator(mode="after")
    def the_kind_has_what_it_needs(self) -> Self:
        missing = [name for name in _DEADLINE_NEEDS[self.kind] if getattr(self, name) is None]
        if missing:
            raise ValueError(f"a {self.kind.value} deadline needs {', '.join(missing)}")
        return self


class ClassificationFields(BaseModel):
    """What kind of task this is and how long it takes (#351, #352).

    tier, urgency and role_context (#351), and the effort estimate with its
    basis (#352).

    Its own model rather than three more fields on `ExtractedFields`. They are
    asked for in the same call today, because the classification rests on the
    same evidence the extraction already read and one call is cheaper than
    two. If that ever measures worse than two separate calls, this is exactly
    the shape the second call would ask for — unchanged.

    Every field is nullable on purpose, and that is the heart of this stage:
    an empty field is the correct answer when the content does not make the
    value clear. A wrong tier is worse than a missing one, because Chain's
    selection and the segmented ranking both act on it.
    """

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    # Set from the enums Inbox owns. Adding a value is their call, not ours.
    tier: TaskTier | None = None
    role_context: RoleContext | None = None

    # The contract's own range. Enforced here too, so a bad value is caught
    # at the edge and repaired rather than travelling any further.
    urgency: int | None = Field(default=None, ge=1, le=5)

    # How long the work takes (#352), and what that rests on. Asked for in
    # the same call and under the same switch as the three above. The basis
    # is ours, not the contract's: assembly turns it into the separate
    # effort_estimate_confidence, and the overall confidence is not touched.
    effort_estimate_minutes: int | None = Field(default=None, gt=0)
    effort_estimate_basis: EffortBasis | None = None

    # When the task is due, as the content says it (#960). Under the same
    # switch as the estimate: one call, and the same rule for optional fields.
    deadline: StatedDeadline | None = None

    @field_validator("deadline", mode="wrap")
    @classmethod
    def an_unusable_deadline_is_null(
        cls, value: object, handler: ValidatorFunctionWrapHandler
    ) -> StatedDeadline | None:
        """Null and logged rather than repaired, like the fields below: a
        repair round risks the whole task over an optional field."""

        if value is None or (
            isinstance(value, str) and value.strip().lower() in _NULL_PLACEHOLDERS
        ):
            return None
        try:
            result: StatedDeadline | None = handler(value)
        except ValidationError as error:
            # Locations and error types only: the input is the content's own
            # words, which the logs should not carry.
            problems = [
                (".".join(str(part) for part in detail["loc"]) or "deadline", detail["type"])
                for detail in error.errors(include_input=False, include_url=False)
            ]
            logger.warning(
                "extraction: deadline cannot be used (%s); recording it as null", problems
            )
            return None
        return result

    @field_validator(
        "tier",
        "role_context",
        "urgency",
        "effort_estimate_minutes",
        "effort_estimate_basis",
        mode="before",
    )
    @classmethod
    def only_a_usable_answer_survives(cls, value: object, info: ValidationInfo) -> object:
        """Clean up how the answer was written, and drop what cannot be used.

        Four steps, in this order, and the order is the whole point:

        1. strip the spacing;
        2. map the model's ways of writing "I don't know" onto a real None;
        3. upper-case what is left, because `strategic` is the right answer
           written wrong;
        4. anything still standing that is not a permitted value becomes None
           — and is logged, because silence here would be indistinguishable
           from a model that honestly declined.

        Step 4 is the one that departs from `parsing.py`'s line of never
        salvaging content, and it does so for a specific reason. These
        fields are optional by design: the prompt tells the model that `null`
        is the correct answer when the content is unclear, and a model that
        wrote `critical` has demonstrated exactly that. Sending it back for
        repair costs up to three calls and, when they all fail, discards a
        perfectly good title and description along with the bad tier. A
        required field like `title` is a different matter and still fails.

        `mode="before"` means all of this runs while the value is still the
        raw JSON value, before pydantic tries to read it as an enum or an int.
        """

        field = info.field_name or ""
        # Kept for the log: what the model actually wrote is what someone
        # auditing a run needs to see, not our tidied-up version of it.
        written = value

        if isinstance(value, str):
            cleaned = value.strip()
            if cleaned.lower() in _NULL_PLACEHOLDERS:
                return None
            value = cleaned.upper()

        if value is None:
            return None

        if field == "urgency":
            return _usable_urgency(value, written)
        if field == "effort_estimate_minutes":
            return _usable_minutes(value, written)

        if not isinstance(value, str):
            # A list, an object, a number. The guard above skips the cleanup
            # for a non-string; it does not reject one, and everything below
            # assumes a word. Letting this reach the membership test raised
            # `TypeError: unhashable type`, which pydantic does not turn into
            # a ValidationError — so it escaped the parser, escaped `extract`,
            # and ended the run instead of the item. A model answering `tier`
            # with two labels is an ordinary mistake, not a freak one.
            logger.warning(
                "extraction: %s came back as %r, which is not a single value; recording it as null",
                field,
                written,
            )
            return None

        permitted = _PERMITTED_VALUES[field]
        if value in permitted:
            return value

        logger.warning(
            "extraction: %s came back as %r, which is not one of %s; recording it as null",
            field,
            written,
            sorted(permitted),
        )
        return None

    @model_validator(mode="after")
    def the_estimate_and_its_basis_travel_together(self) -> Self:
        """Minutes and basis are one answer, so half of one is settled here.

        Runs after the field validators, so "usable" has already been decided
        for each on its own; this only looks at the pair.

        - A basis with no minutes is dropped: nothing was estimated.
        - Minutes with no basis are kept as INFERRED, the weaker reading. Not
          a repair round, which would risk the whole item over an optional
          field, and not a null, which would discard an answer the model did
          give. INFERRED reaches Inbox as low confidence.
        """

        minutes, basis = self.effort_estimate_minutes, self.effort_estimate_basis

        if basis is not None and minutes is None:
            logger.warning(
                "extraction: effort_estimate_basis came back as %s with no usable minutes; "
                "recording it as null",
                basis.value,
            )
            self.effort_estimate_basis = None
        elif minutes is not None and basis is None:
            logger.warning(
                "extraction: effort_estimate_minutes came back as %d with no usable basis; "
                "recording the basis as INFERRED, the weaker reading",
                minutes,
            )
            self.effort_estimate_basis = EffortBasis.INFERRED

        return self


class ExtractedFields(BaseModel):
    """Exactly what the LLM is asked to return — nothing more.

    `source_type` is carried forward from the intake event and `confidence`
    is computed locally, so neither is accepted from the model: letting it
    emit values we already know invites hallucination over facts.
    """

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    title: str = Field(min_length=1, max_length=MAX_TITLE_LENGTH)
    description: str | None = None

    # A pointer found inside the content (message id, URL, ticket key).
    # Not every source carries one, so None is a normal outcome.
    source_ref: str | None = None

    # Whether the task looks like it depends on, or is blocked by, other work.
    # One direction since #690: a task others wait for is not dependent.
    # Resolving the actual targets belongs to R3, not to this stage.
    has_dependencies: bool

    # Short phrases naming the work this one depends on or is blocked by —
    # never what waits for it: #834 sends each to Chain as this task's
    # `depends_on_task_id`, so the other direction would arrive reversed. In the
    # content's own words: "the DB upgrade" (#690, was #842). Never in the
    # contract. They travel beside it to #834's resolver, which turns them into
    # task ids, so a phrase that cannot be resolved there must not leave here.
    # Checked against the content, and the rule that a mention means a
    # dependency applied, by `grounding.ground_dependency_mentions`: the
    # check needs the content, which this model never sees. So the rule
    # holds on what `extract` returns (`ExtractionSuccess`), not on a bare
    # ExtractedFields parsed or built anywhere else.
    dependency_mentions: list[str] = []

    @field_validator("dependency_mentions", mode="before")
    @classmethod
    def only_usable_mentions_survive(cls, value: object) -> list[str]:
        """Keep each phrase once, and drop what is not a phrase.

        Optional, so nothing here raises: an unusable mention is dropped and
        logged rather than sending the whole answer, every task of a meeting,
        back for repair. One empty mention used to stall #834's listener.
        """

        if value is None:
            return []
        if isinstance(value, str):
            value = [value]
        if not isinstance(value, list):
            logger.warning(
                "extraction: dependency_mentions came back as %r, which is not a list; "
                "recording it as empty",
                value,
            )
            return []

        kept: list[str] = []
        # Case-blind: "The DB upgrade" is the same mention, and the resolver
        # would only look it up twice.
        seen: set[str] = set()
        for item in value:
            if not isinstance(item, str):
                logger.warning(
                    "extraction: dependency mention %r is not a phrase; dropping it", item
                )
                continue
            phrase = item.strip()
            if phrase.lower() in _NULL_PLACEHOLDERS or phrase.lower() in seen:
                continue
            # A mention names another task, so it is held to a title's length:
            # past that it is the content echoed back, and the resolver would
            # match it against everything.
            if len(phrase) > MAX_TITLE_LENGTH:
                logger.warning(
                    "extraction: dependency mention %r is longer than %d characters; dropping it",
                    phrase[:60] + "...",
                    MAX_TITLE_LENGTH,
                )
                continue
            seen.add(phrase.lower())
            kept.append(phrase)

        # Each mention costs #834 a vector search. Kept rather than repaired
        # past the cap, as everything optional here is.
        if len(kept) > MAX_MENTIONS_PER_TASK:
            logger.warning(
                "extraction: %d more dependency mention(s) than the %d kept; dropping %s",
                len(kept) - MAX_MENTIONS_PER_TASK,
                MAX_MENTIONS_PER_TASK,
                kept[MAX_MENTIONS_PER_TASK:],
            )
            kept = kept[:MAX_MENTIONS_PER_TASK]
        return kept

    @field_validator("description", "source_ref", mode="after")
    @classmethod
    def blank_to_none(cls, value: str | None) -> str | None:
        """Map the model's ways of writing "nothing" onto a real None."""

        if value is None:
            return None
        return None if value.strip().lower() in _NULL_PLACEHOLDERS else value

    @field_validator("description", mode="after")
    @classmethod
    def a_runaway_description_is_dropped(cls, value: str | None) -> str | None:
        """Too long to be a summary: logged and dropped, never repaired."""

        if value is not None and len(value) > MAX_DESCRIPTION_LENGTH:
            logger.warning(
                "extraction: description of %d characters is longer than %d; dropping it",
                len(value),
                MAX_DESCRIPTION_LENGTH,
            )
            return None
        return value

    @field_validator("title", mode="before")
    @classmethod
    def a_placeholder_is_not_a_title(cls, value: object) -> object:
        """The same reading as `blank_to_none`, with the opposite consequence.

        `description` and `source_ref` are allowed to be nothing, so a
        placeholder there becomes None and the answer stands. `title` is
        required by the contract, so there is nowhere for it to fall back to:
        a model that wrote "N/A" has not answered, and the answer has to go
        back for repair rather than reach Inbox as a task called "N/A".

        Raising rather than returning None, even though None would also fail:
        the message is the whole point of failing. Pydantic's own complaint
        about a None title reads "Input should be a valid string", and a model
        that wrote "N/A" — which *is* a valid string — has no way to act on
        that. The repair round only earns its cost if the model is told what
        was wrong with the answer it gave.

        `mode="before"` matters too: an `after` validator runs once the type
        has already been accepted, so this check has to happen while the raw
        value is still in hand.
        """

        if isinstance(value, str) and value.strip().lower() in _NULL_PLACEHOLDERS:
            raise ValueError(
                f"{value!r} is a placeholder, not a title; summarise the task as an action, "
                f"and if this entry describes no task, leave it out of the list rather "
                f"than filling the field in"
            )
        return value


class ExtractedFieldsWithClassification(ExtractedFields, ClassificationFields):
    """Every field asked for when classification is switched on (#351, #352).

    Two parents and not one copied field definition: anything that changes in
    either model changes here with it, which is what stops the switched-off
    and switched-on shapes from drifting apart.
    """


# More than this from one text is a model listing every sentence, not a meeting
# with that many commitments. Rejected and repaired rather than cut short:
# keeping the first fifteen would drop the rest without anyone seeing it.
MAX_TASKS_PER_TEXT = 15


class ExtractedTasks[TaskT: ExtractedFields](BaseModel):
    """The whole answer: every task the text describes (#690).

    `TaskT` is the shape `response_model` chose, so each task in the list is
    validated by exactly the rules a single task always was, switch on or off.

    One invalid task fails the whole answer. Keeping the valid ones would
    quietly drop the bad one, and the repair round can name it by its place in
    the list (`tasks.1.title`). An empty list is a valid answer: a text with no
    task in it is the normal case, not a failure.
    """

    model_config = ConfigDict(extra="forbid")

    tasks: list[TaskT] = Field(max_length=MAX_TASKS_PER_TEXT)


def response_model(classify: bool) -> type[ExtractedFields]:
    """The shape to ask the LLM for, and so the shape to validate against.

    The return type is `type[ExtractedFields]` and that is exact rather than
    vague: the combined model is a subclass, so whichever way the switch is
    set, every caller downstream is guaranteed the four basic fields.
    """

    return ExtractedFieldsWithClassification if classify else ExtractedFields
