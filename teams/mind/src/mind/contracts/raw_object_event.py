"""Contract for raw-object events received from Bridge."""

import logging
from datetime import timedelta
from enum import Enum
from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    ValidatorFunctionWrapHandler,
    field_validator,
    model_validator,
)

from mind.contracts.raw_object_event_enums import (
    Category,
    ChangeType,
    DecidedBy,
    Modality,
    ObjectRole,
    Quality,
    RawSourceType,
    ScopeDecision,
    Sensitivity,
)

logger = logging.getLogger(__name__)


class RawObjectRef(BaseModel):
    """One MinIO object belonging to the logical item of a RawObjectEvent."""

    model_config = ConfigDict(extra="forbid")

    role: ObjectRole
    object_key: str = Field(
        pattern=r"^[A-Z_]+/[^/]+/\d{4}-\d{2}-\d{2}/[^/]+$",
        description="MinIO object key, e.g. EMAIL/tenant-123/2026-08-20/message.eml",
    )
    content_hash: str = Field(pattern=r"^sha256:[a-f0-9]{64}$")
    content_type: str | None = None  # example: audio/mpeg.
    size_bytes: int | None = Field(default=None, ge=0)
    # Required: a TEXT default would silently mislabel a RECORDING.
    modality: Modality


def _is_unknown(value: object, enum: type[Enum]) -> bool:
    """A string Bridge sent that this version of Mind doesn't know yet."""
    return isinstance(value, str) and value not in {member.value for member in enum}


class CategoryLabel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    value: Category
    confidence: float = Field(ge=0, le=1)

    @field_validator("value", mode="before")
    @classmethod
    def unknown_category_is_other(cls, value: object) -> object:
        return Category.OTHER if _is_unknown(value, Category) else value


class QualityLabel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    value: Quality
    confidence: float = Field(ge=0, le=1)


class SensitivityLabel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    value: Sensitivity
    confidence: float = Field(ge=0, le=1)

    @field_validator("value", mode="before")
    @classmethod
    def unknown_sensitivity_is_high(cls, value: object) -> object:
        # Fail safe: a level we don't know is treated as the most sensitive one.
        return Sensitivity.HIGH if _is_unknown(value, Sensitivity) else value


class ClassifierInfo(BaseModel):
    model_config = ConfigDict(extra="forbid")

    model: str
    prompt_version: str


class Classification(BaseModel):
    """Bridge's triage of the item as a whole. `reason` is for humans only."""

    model_config = ConfigDict(extra="forbid")

    decided_by: DecidedBy
    category: CategoryLabel | None = None
    quality: QualityLabel | None = None  # None also when Bridge's value is unknown
    sensitivity: SensitivityLabel
    reason: str
    classifier: ClassifierInfo

    @field_validator("quality", mode="before")
    @classmethod
    def unknown_quality_is_ignored(cls, value: object) -> object:
        if isinstance(value, dict) and _is_unknown(value.get("value"), Quality):
            return None
        return value


class RawObjectEvent(BaseModel):
    """Event published by Bridge for one logical item landed in MinIO.

    Every MinIO object of the item (body, attachments, recording, transcript...)
    arrives together in `objects`.
    """

    model_config = ConfigDict(extra="forbid")

    # Required fields
    # Business system/origin that produced the object.
    source_type: RawSourceType
    correlation_id: UUID = Field(
        description=(
            "Identity of the logical item, stable across its revisions. "
            "Dedup key: correlation_id + ':' + revision."
        ),
    )
    tenant_id: str = Field(min_length=1)
    ingested_at: AwareDatetime
    scope_decision: ScopeDecision 
    schema_version: str
    revision: int = Field(ge=0 , 
         description=(  "Revision of the logical object. "
             "Starts at 0 and increases when its content changes."
        ),)
    change_type: ChangeType = Field(
        description=(
            "Type of change represented by this event."
        ),
    )
    change_significance_score: int = Field(
        ge=0,
        le=100,
        description=(
            "Bridge-provided score indicating how significant "
            "the change is for reprocessing."
        ),
    )
    objects: list[RawObjectRef]

    
    # Optional fields
    participants: list[str] | None = None
    source_timestamp: AwareDatetime | None = None
    source_ref: str | None = Field(
        default=None,
        description=(
            "The source system's own identifier for the item (Zoom uuid, "
            "Slack channel_id:ts, Message-ID...). An identifier, not a link."
        ),
    )
    # Absent: nothing classified the item, and scope_decision is the INCLUDED default.
    classification: Classification | None = None


    @field_validator("ingested_at", "source_timestamp")
    @classmethod
    def timestamps_must_be_utc(cls, value: AwareDatetime | None) -> AwareDatetime | None:
        if value is not None and value.utcoffset() != timedelta(0):
            raise ValueError("timestamp must be normalized to UTC")
        return value

    @field_validator("classification", mode="wrap")
    @classmethod
    def unusable_classification_is_dropped(
        cls, value: object, handler: ValidatorFunctionWrapHandler
    ) -> Classification | None:
        # Mind doesn't read classification, so it never costs us the item: the
        # same "fails open" Bridge applies when its own classifier fails.
        try:
            return handler(value)
        except ValidationError as error:
            # Locations and error types only: the input can carry `reason`, which
            # is derived from the content and may be sensitive.
            problems = [
                (".".join(str(part) for part in detail["loc"]), detail["type"])
                for detail in error.errors(include_input=False, include_url=False)
            ]
            logger.warning("raw_object_event: dropping an unusable classification: %s", problems)
            return None

    @model_validator(mode="after")
    def objects_match_change_type(self) -> "RawObjectEvent":
        if self.change_type is ChangeType.DELETE_REVIEW and self.objects:
            raise ValueError("a DELETE_REVIEW event carries no objects")
        if self.change_type is ChangeType.UPSERT and not self.objects:
            raise ValueError("an UPSERT event needs at least one object")
        return self
