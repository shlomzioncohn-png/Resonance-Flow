"""Contract for raw-object events received from Bridge."""

from datetime import timedelta
from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
    model_validator,
)

from mind.contracts.base_enums import SourceType
from mind.contracts.raw_object_event_enums import (
    ChangeType,
    Modality,
    ObjectRole,
    ScopeDecision,
)


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


class RawObjectEvent(BaseModel):
    """Event published by Bridge for one logical item landed in MinIO.

    Every MinIO object of the item (body, attachments, recording, transcript...)
    arrives together in `objects`.
    """

    model_config = ConfigDict(extra="forbid")

    # Required fields
    # Business system/origin that produced the object.
    source_type: SourceType
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


    @field_validator("ingested_at", "source_timestamp")
    @classmethod
    def timestamps_must_be_utc(cls, value: AwareDatetime | None) -> AwareDatetime | None:
        if value is not None and value.utcoffset() != timedelta(0):
            raise ValueError("timestamp must be normalized to UTC")
        return value

    @field_validator("source_type")
    @classmethod
    def meeting_is_not_a_wire_value(cls, value: SourceType) -> SourceType:
        # MEETING stays in SourceType only for the task output.
        if value is SourceType.MEETING:
            raise ValueError("Bridge sends ZOOM or BIGBLUEBUTTON, not MEETING")
        return value

    @model_validator(mode="after")
    def objects_match_change_type(self) -> "RawObjectEvent":
        if self.change_type is ChangeType.DELETE_REVIEW and self.objects:
            raise ValueError("a DELETE_REVIEW event carries no objects")
        if self.change_type is ChangeType.UPSERT and not self.objects:
            raise ValueError("an UPSERT event needs at least one object")
        return self
