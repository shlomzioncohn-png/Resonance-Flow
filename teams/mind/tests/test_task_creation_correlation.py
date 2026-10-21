"""The correlation_id of a creation request, and why a retry finds the same one (#353).

Derived, not random: a hash of the Bridge item the task came from, so a retry
— even after a crash, even after extraction ran again and worded the title
differently — computes the same id with nothing remembered, and Inbox treats
it as the same request. The content is deliberately not hashed: re-extraction
rewords it (two ids, two tasks), and two people can write the same words
(one id, one task lost).

The item's revision is deliberately not hashed either. One item, one task;
a later revision is an update to that task, which is a different flow — and
the revision is kept beside the task for it, not folded into its identity.
"""

from uuid import UUID

import pytest

from mind.task_creation.correlation import correlation_id_for

ITEM = UUID("6f1c2e9a-3b4d-4c8e-9a1f-2d7e5b0c4a83")
OTHER_ITEM = UUID("0d3e5f7a-9b1c-4d2e-8f6a-7b5c3d1e9f20")


def test_the_same_item_always_gets_the_same_id() -> None:
    """The retry case: computed again from scratch, it must come out equal."""

    assert correlation_id_for(ITEM) == correlation_id_for(ITEM)


def test_different_items_get_different_ids() -> None:
    assert correlation_id_for(ITEM) != correlation_id_for(OTHER_ITEM)


def test_tasks_from_the_same_item_get_different_ids() -> None:
    """A meeting can yield several tasks; each needs its own request."""

    assert correlation_id_for(ITEM, task_index=0) != correlation_id_for(ITEM, task_index=1)


def test_the_first_task_is_the_default() -> None:
    assert correlation_id_for(ITEM) == correlation_id_for(ITEM, task_index=0)


def test_it_is_a_uuid_inbox_accepts() -> None:
    """Inbox sends anything else to its error topic."""

    correlation_id = correlation_id_for(ITEM)

    assert UUID(str(correlation_id)) == correlation_id
    assert correlation_id.version == 5


def test_it_is_not_bridges_own_id() -> None:
    """Two ids, two meanings: Bridge's names the item, this names the request."""

    assert correlation_id_for(ITEM) != ITEM


def test_a_negative_task_index_is_refused() -> None:
    with pytest.raises(ValueError, match="task_index"):
        correlation_id_for(ITEM, task_index=-1)


def test_the_id_for_a_known_item_never_changes() -> None:
    """Pinned, because nothing else here can see across a restart.

    Every other test runs in one process, where any namespace looks stable.
    Were it drawn at start-up, a retry after a crash — exactly when items are
    reprocessed — would carry a new id and Inbox would create the task twice.
    If this fails, every request already sent will be repeated as a new one.
    """

    assert correlation_id_for(ITEM) == UUID("b5603af4-09b6-569a-b3ca-f6d18a0191a1")
