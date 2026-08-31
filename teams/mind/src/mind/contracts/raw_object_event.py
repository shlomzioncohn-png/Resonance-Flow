"""Contract for raw-object events received from Bridge."""

from datetime import timedelta
from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
)

from .base_enums import SourceType

from .raw_object_event_enums import (
    Modality,
    ScopeDecision,
)


class RawObjectEvent(BaseModel):
    """Event published by Bridge when raw content is landed in MinIO."""

    model_config = ConfigDict(extra="forbid")

    # Required fields
    object_key: str = Field(
        pattern = r"^[A-Z_]+/[^/]+/\d{4}-\d{2}-\d{2}/[^/]+$", description="MinIO object key, e.g. EMAIL/tenant-123/2026-08-20/message.eml" 
    )

    # Business system/origin that produced the object.
    source_type: SourceType
    tenant_id: str = Field(min_length=1)
    ingested_at: AwareDatetime
    scope_decision: ScopeDecision 
    content_hash: str = Field( # TODO: revisit.
        pattern=r"^sha256:[a-f0-9]{64}$"
    )
    schema_version: str
    attachment_index: int = Field(ge=0)

    
    # Optional fields
    participants: list[str] | None = None
    content_type: str | None = None #example: audio/mpeg.
    size_bytes: int | None = Field(default=None, ge=0)
    transcript_object_key: str | None = Field(
    default=None,
    pattern=r"^[A-Z_]+/[^/]+/\d{4}-\d{2}-\d{2}/[^/]+$",
    description="MinIO object key for transcript, e.g. EMAIL/tenant-123/2026-08-20/message.txt"
    )
    source_timestamp: AwareDatetime | None = None
    modality: Modality = Modality.TEXT
    # Optional correlation identifier shared by the source body and its attachments.
    correlation_id: UUID | None = None

    @field_validator("ingested_at", "source_timestamp")
    @classmethod
    def timestamps_must_be_utc(cls, value: AwareDatetime | None) -> AwareDatetime | None:
        if value is not None and value.utcoffset() != timedelta(0):
            raise ValueError("timestamp must be normalized to UTC")
        return value
   