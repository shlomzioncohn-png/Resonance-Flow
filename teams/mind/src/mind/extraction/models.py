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
