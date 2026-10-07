"""Internal models for the basic task-field extraction stage (#350).

These are *not* contracts. `TaskCreationRequested` (mind.contracts) is the
outbound contract; the models here describe the narrower shape we ask an LLM
for, before the locally-known fields are merged in.
"""

import logging

from pydantic import BaseModel, ConfigDict, Field, ValidationInfo, field_validator

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

# What each categorical field may contain, written as the model must write it.
# Derived from the enums rather than listed again, so a value Inbox adds shows
# up here without anyone remembering to copy it.
_PERMITTED_VALUES: dict[str, frozenset[str]] = {
    "tier": frozenset(member.value for member in TaskTier),
    "role_context": frozenset(member.value for member in RoleContext),
}

URGENCY_RANGE = range(1, 6)


def _usable_urgency(value: object, written: object = None) -> int | None:
    """Read urgency as a whole number inside the scale, or give up on it.

    Deliberately not clamped. A 7 might mean "past the top of your scale", in
    which case 5 is right — but it might equally mean the model scored on 1 to
    10, where 7 is middling. The two are indistinguishable from here, and the
    cost of guessing wrong is not symmetric: a missing urgency is visibly
    missing, while a clamped one is a plausible-looking number that quietly
    inflates every downstream ranking.
    """

    # bool is an int in Python, and `True` as an urgency would read as 1.
    if isinstance(value, bool):
        number = None
    elif isinstance(value, int):
        number = value
    elif isinstance(value, str) and value.lstrip("-").isdigit():
        number = int(value)
    else:
        number = None

    if number is None:
        logger.warning(
            "extraction: urgency came back as %r, which is not a whole number", written
        )
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
    """What kind of task this is: tier, urgency and role_context (#351).

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

    @field_validator("tier", "role_context", "urgency", mode="before")
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
        salvaging content, and it does so for a specific reason. These three
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


class ExtractedFieldsWithClassification(ExtractedFields, ClassificationFields):
    """The seven fields asked for when classification is switched on (#351).

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
