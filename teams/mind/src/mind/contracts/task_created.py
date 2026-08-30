"""Contract for task.created events received from Inbox."""

from pydantic import BaseModel, ConfigDict

from .task import Task


class TaskCreated(BaseModel):
    """Payload published by Inbox after a Task is created."""

    model_config = ConfigDict(extra="forbid")

    task: Task
    