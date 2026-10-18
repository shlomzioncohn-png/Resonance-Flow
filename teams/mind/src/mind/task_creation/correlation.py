"""The correlation_id of a creation request, derived from Bridge's item so a retry repeats it.

Why identity and not content, and why no revision: README.md, "The correlation_id".
"""

from uuid import UUID, uuid5

#: Never change: every id would change, and every request already sent would
#: be repeated as a new one.
_NAMESPACE = UUID("b596c115-93e5-46d2-b2c3-a26e39521909")


def correlation_id_for(bridge_correlation_id: UUID, task_index: int = 0) -> UUID:
    """The request id for task number `task_index` from Bridge's item.

    `task_index` is 0 while an item yields one task; a meeting that yields
    several needs one request each.
    """

    if task_index < 0:
        raise ValueError(f"task_index must be 0 or more, got {task_index}")
    return uuid5(_NAMESPACE, f"{bridge_correlation_id}:{task_index}")
