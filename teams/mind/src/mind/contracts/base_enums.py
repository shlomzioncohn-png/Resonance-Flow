"""Enums shared across MIND contracts."""

from enum import Enum


class SourceType(str, Enum):
    EMAIL = "email"
    SLACK = "slack"
    WHATSAPP = "whatsapp"
    MEETING = "meeting"
    CALENDAR = "calendar"
    DOC = "doc"
    SCANNED_DOC = "scanned_doc"
    DRIVE = "drive"