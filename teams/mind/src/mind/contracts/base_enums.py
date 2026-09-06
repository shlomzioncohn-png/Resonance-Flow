"""Enums shared across MIND contracts."""

from enum import Enum


class SourceType(str, Enum):
    EMAIL = "EMAIL"
    SLACK = "SLACK"
    WHATSAPP = "WHATSAPP"
    MEETING = "MEETING"
    CALENDAR = "CALENDAR"
    DOC = "DOC"
    SCANNED_DOC = "SCANNED_DOC"
    DRIVE = "DRIVE"