"""Enums used by the RawObjectEvent contract."""

from enum import Enum


class ScopeDecision(str, Enum):
    """Bridge work-relevance decision."""

    INCLUDED = "INCLUDED"
    EXCLUDED = "EXCLUDED"


class Modality(str, Enum):
    """Technical routing hint supplied by Bridge."""

    AUDIO = "AUDIO"
    SCANNED_DOC = "SCANNED_DOC"
    TEXT = "TEXT"
    
class ObjectRole(str, Enum):
    """What one MinIO object is within its logical item."""

    BODY = "BODY"
    ATTACHMENT = "ATTACHMENT"
    TRANSCRIPT = "TRANSCRIPT"
    RECORDING = "RECORDING"
    SOURCE_METADATA = "SOURCE_METADATA"


class ChangeType(str, Enum):
    """Type of source-object change reported by Bridge."""

    UPSERT = "UPSERT"
    DELETE_REVIEW = "DELETE_REVIEW"
