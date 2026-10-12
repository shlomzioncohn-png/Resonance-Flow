"""Internal models for the basic task-field extraction stage (#350).

These are *not* contracts. `TaskCreationRequested` (mind.contracts) is the
outbound contract; the models here describe the narrower shape we ask an LLM
for, before the locally-known fields are merged in.
"""

from pydantic import BaseModel, ConfigDict, Field, field_validator

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
