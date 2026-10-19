"""One file Bridge landed, read and checked: what the rest of the pipeline sees."""

from typing import Any
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field

from mind.contracts.raw_object_event_enums import Modality, ObjectRole, RawSourceType


class NormalizedRecord(BaseModel):
    """One stored object, ready for whatever reads it next.

    A typed field is something code downstream decides on, or copies as-is
    into the task request for Inbox. `metadata` is for tracing only: nothing
    may read it to decide anything.
    """

    model_config = ConfigDict(extra="forbid")

    # The scheme says where the bytes came from; only MinIO today.
    object_ref: str = Field(pattern=r"^minio://.+$")
    content: bytes
    content_hash: str = Field(pattern=r"^sha256:[a-f0-9]{64}$")
    content_type: str | None
    modality: Modality
    role: ObjectRole
    source_type: RawSourceType
    tenant_id: UUID

    # None when Bridge sent no such value. No defaults, here or above: whoever
    # builds a record passes each one, even as None, so code that forgets to
    # copy source_ref fails instead of losing it.
    source_ref: str | None
    source_timestamp: AwareDatetime | None
    participants: list[str] | None
    correlation_id: UUID | None
    revision: int | None = Field(ge=0)

    metadata: dict[str, Any] = Field(default_factory=dict)
