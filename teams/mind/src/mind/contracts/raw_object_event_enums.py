"""Enums used by the RawObjectEvent contract."""

from enum import Enum


class SourceType(str, Enum):
    """Business origin of the object."""

    EMAIL = "EMAIL"
    SLACK = "SLACK"
    WHATSAPP = "WHATSAPP"
    DRIVE = "DRIVE"
    CALENDAR = "CALENDAR"
    MEETING = "MEETING"


class ScopeDecision(str, Enum):
    """Bridge work-relevance decision."""

    INCLUDED = "INCLUDED"
    EXCLUDED = "EXCLUDED"


class Modality(str, Enum):
    """Technical routing hint supplied by Bridge."""

    AUDIO = "audio"
    SCANNED_DOC = "scanned_doc"
    TEXT = "text"