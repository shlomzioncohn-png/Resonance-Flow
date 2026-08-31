"""Enums used by the RawObjectEvent contract."""

from enum import Enum


class ScopeDecision(str, Enum):
    """Bridge work-relevance decision."""

    INCLUDED = "included"
    EXCLUDED = "excluded"


class Modality(str, Enum):
    """Technical routing hint supplied by Bridge."""

    AUDIO = "audio"
    SCANNED_DOC = "scanned_doc"
    TEXT = "text"