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