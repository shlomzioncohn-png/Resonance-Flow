from datetime import datetime
from typing import Literal, Protocol, runtime_checkable

from pydantic import BaseModel


class Task(BaseModel):
    """A single candidate task within a persona evaluation scenario.

    Mirrors the Task definition in eval/personas/scenario.schema.json —
    that JSON Schema is the binding source of truth; this is its Pydantic form.
    """

    task_id: str
    title: str
    description: str
    deadline: datetime | None = None
    estimated_duration_minutes: int
    tier: Literal["Operational", "Tactical", "Strategic"]
    blocked: bool


@runtime_checkable
class RankingModel(Protocol):
    """Contract every ranking model must implement to run through the evaluation harness."""

    def score(self, task: Task) -> float:
        """Returns a priority score for a single task. Higher score = higher priority."""
        ...
