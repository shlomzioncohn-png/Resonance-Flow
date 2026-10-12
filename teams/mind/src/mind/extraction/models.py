"""Internal models for the extraction stage: the basic task fields (#350),
the classification (#351) and the effort estimate (#352).

These are *not* contracts. `TaskCreationRequested` (mind.contracts) is the
outbound contract; the models here describe the narrower shape we ask an LLM
for, before the locally-known fields are merged in.
"""

import logging
import math
import re
from enum import StrEnum
from typing import Self

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationInfo,
    field_validator,
    model_validator,
)

from mind.contracts.task_enums import RoleContext, TaskTier

logger = logging.getLogger("mind.extraction")

# Titles longer than this are treated as malformed rather than truncated:
# a runaway title usually means the model echoed the input instead of
# summarising it, and that is worth a repair attempt.
MAX_TITLE_LENGTH = 200

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
    elif isinstance(value, float) and math.isfinite(value) and (
        round_fractions or value.is_integer()
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
    """A positive whole number of minutes, or None. No upper bound: the
    contract sets none, and a month of working time is some 9600 honest
    minutes."""

    number = _whole_number("effort_estimate_minutes", value, written, round_fractions=True)
    if number is None or number > 0:
        return number

    logger.warning(
        "extraction: effort_estimate_minutes came back as %r, which is not a positive "
        "number of minutes; recording it as null",
        written,
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
                "extraction: %s came back as %r, which is not a single value; "
                "recording it as null",
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

    # Whether the task looks like it depends on or blocks another task.
    # Resolving the actual targets belongs to R3, not to this stage.
    has_dependencies: bool

    @field_validator("description", "source_ref", mode="after")
    @classmethod
    def blank_to_none(cls, value: str | None) -> str | None:
        """Map the model's ways of writing "nothing" onto a real None."""

        if value is None:
            return None
        return None if value.strip().lower() in _NULL_PLACEHOLDERS else value

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
                f"and if the content truly describes no task, say so rather than filling "
                f"the field in"
            )
        return value

class ExtractedFieldsWithClassification(ExtractedFields, ClassificationFields):
    """Every field asked for when classification is switched on (#351, #352).

    Two parents and not one copied field definition: anything that changes in
    either model changes here with it, which is what stops the switched-off
    and switched-on shapes from drifting apart.
    """


def response_model(classify: bool) -> type[ExtractedFields]:
    """The shape to ask the LLM for, and so the shape to validate against.

    The return type is `type[ExtractedFields]` and that is exact rather than
    vague: the combined model is a subclass, so whichever way the switch is
    set, every caller downstream is guaranteed the four basic fields.
    """

    return ExtractedFieldsWithClassification if classify else ExtractedFields
