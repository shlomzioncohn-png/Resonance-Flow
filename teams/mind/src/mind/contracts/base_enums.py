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
    GITHUB_ISSUE = "GITHUB_ISSUE"
    GITHUB_PR = "GITHUB_PR"
    CRM_DEAL = "CRM_DEAL"
    MANUAL = "MANUAL"
    