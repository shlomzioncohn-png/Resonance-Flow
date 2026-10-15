"""The correlation_id a creation request carries, derived so a retry repeats it.

Inbox de-duplicates on correlation_id, so a retry has to carry the same one.
Derived rather than random and stored: a hash of the Bridge item the task came
from computes the same id after a crash with nothing remembered.

What is hashed is the item's identity, never its content. Re-extraction rewords
the content (two ids for one item: two tasks), and two people can write the
same words (one id for two items: a task lost). The item's revision is left out
too: one item makes one task, and a later revision is an update to it — a
different flow, which will find the revision kept beside the task.
"""

from uuid import UUID, uuid5

#: Fixed for ever. Changing it changes every id, and every request already
#: sent would be repeated as a new one.
_NAMESPACE = UUID("b596c115-93e5-46d2-b2c3-a26e39521909")


def correlation_id_for(bridge_correlation_id: UUID, task_index: int = 0) -> UUID:
    """The request id for task number `task_index` from Bridge's item.

    `task_index` is 0 while an item yields one task; a meeting that yields
    several needs one request each.
    """

    if task_index < 0:
        raise ValueError(f"task_index must be 0 or more, got {task_index}")
    return uuid5(_NAMESPACE, f"{bridge_correlation_id}:{task_index}")
