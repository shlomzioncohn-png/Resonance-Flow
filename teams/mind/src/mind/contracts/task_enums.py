"""Enums used by Task-related contracts."""

from enum import Enum


class TaskSourceType(str, Enum):
    EMAIL = "email"
    SLACK = "slack"
    WHATSAPP = "whatsapp"
    MEETING = "meeting"
    CALENDAR = "calendar"
    DOC = "doc"
    SCANNED_DOC = "scanned_doc"
    DRIVE = "drive"


class TaskTier(str, Enum):
    OPERATIONAL = "operational"
    TACTICAL = "tactical"
    STRATEGIC = "strategic"


class RoleContext(str, Enum):
    DEVELOPER = "developer"
    MANAGER = "manager"
    HIGHER_MANAGER = "higher_manager"
    FIELD_ENGINEER = "field_engineer"
    SALESPERSON = "salesperson"
    CEO = "ceo"


class TaskStatus(str, Enum):
    NEW = "new"
    IN_REVIEW = "in_review"
    APPROVED = "approved"
    IN_PROGRESS = "in_progress"
    SNOOZED = "snoozed"
    DONE = "done"
    REJECTED = "rejected"
    CANCELLED = "cancelled"


class ExtractionProvenance(str, Enum):
    ETL = "etl"
    ML_INFERRED = "ml_inferred"
    MANUAL = "manual"


class AssigneeType(str, Enum):
    USER = "user"
    TEAM = "team"
    