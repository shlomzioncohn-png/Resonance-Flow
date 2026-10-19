from uuid import UUID

import pytest

from mind.ingestion.seen_revisions import Arrival, InMemoryRevisionStore, classify_arrival

ITEM = UUID("6f1c1f9e-0000-4000-8000-000000000001")
OTHER_ITEM = UUID("6f1c1f9e-0000-4000-8000-000000000002")


@pytest.mark.parametrize(
    ("processed", "arriving", "expected"),
    [
        (None, 0, Arrival.NEW),
        (2, 2, Arrival.DUPLICATE),
        (2, 1, Arrival.OUT_OF_ORDER),
        (2, 3, Arrival.UPDATE),
    ],
)
def test_an_arrival_is_judged_against_the_revision_already_processed(
    processed: int | None, arriving: int, expected: Arrival
) -> None:
    assert classify_arrival(processed, arriving) is expected


def test_only_new_items_are_processed() -> None:
    assert [a for a in Arrival if a.should_process] == [Arrival.NEW]


def test_the_store_remembers_each_item_separately() -> None:
    store = InMemoryRevisionStore()
    store.mark_processed(ITEM, 2)

    assert store.processed_revision(ITEM) == 2
    assert store.processed_revision(OTHER_ITEM) is None


def test_the_store_forgets_the_oldest_items_past_its_size() -> None:
    # Dedup matters for redeliveries soon after an item; memory must stay flat.
    items = [UUID(int=n) for n in range(3)]
    store = InMemoryRevisionStore(max_items=2)

    for item in items:
        store.mark_processed(item, 0)

    assert store.processed_revision(items[0]) is None
    assert store.processed_revision(items[1]) == 0
    assert store.processed_revision(items[2]) == 0
