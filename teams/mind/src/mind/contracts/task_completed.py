"""Contract for task.completed events received from Inbox."""

from uuid import UUID

from pydantic import BaseModel, ConfigDict


class TaskCompleted(BaseModel):
    """Payload published when Inbox marks a Task as completed."""

    model_config = ConfigDict(extra="forbid")

    task_id: UUID
    is_completed: bool
    