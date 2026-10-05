"""Enums used by Task-related contracts."""

from enum import Enum


class TaskTier(str, Enum):
    OPERATIONAL = "OPERATIONAL"
    TACTICAL = "TACTICAL"
    STRATEGIC = "STRATEGIC"


class RoleContext(str, Enum):
    DEVELOPER = "DEVELOPER"
    MANAGER = "MANAGER"
    HIGHER_MANAGER = "HIGHER_MANAGER"
    FIELD_ENGINEER = "FIELD_ENGINEER"
    SALESPERSON = "SALESPERSON"
    CEO = "CEO"


class TaskStatus(str, Enum):
    NEW = "NEW"
    IN_REVIEW = "IN_REVIEW"
    APPROVED = "APPROVED"
    IN_PROGRESS = "IN_PROGRESS"
    SNOOZED = "SNOOZED"
    DONE = "DONE"
    REJECTED = "REJECTED"
    CANCELLED = "CANCELLED"


class ExtractionProvenance(str, Enum):
    ETL = "ETL"
    ML_INFERRED = "ML_INFERRED"
    MANUAL = "MANUAL"


class AssigneeType(str, Enum):
    USER = "USER"
    TEAM = "TEAM"