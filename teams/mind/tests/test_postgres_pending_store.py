"""#918: `PostgresPendingStore` against a real Postgres -- the same
`PendingStore` contract `PendingRequests` satisfies in memory, plus the
one thing memory cannot: a request is still there after a restart.
"""

import asyncio
import os
import threading
from datetime import UTC, datetime
from uuid import UUID

import pytest

from mind.task_creation.pending import PendingRequest
from mind.task_creation.postgres_pending_store import PostgresPendingStore

ITEM = UUID("6f1c2e9a-3b4d-4c8e-9a1f-2d7e5b0c4a83")
OTHER_ITEM = UUID("1a9c3e2b-5d4f-4a8e-9b1c-2d7e5b0c4a99")
REQUEST_ID = UUID("b5603af4-09b6-569a-b3ca-f6d18a0191a1")
OTHER_REQUEST_ID = UUID("c6714b05-1ac7-67ab-c4db-07e29b1202b2")
TENANT = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
SENT = datetime(2026, 10, 14, 12, 0, tzinfo=UTC)


def _request(**overrides: object) -> PendingRequest:
    defaults: dict[str, object] = {
        "bridge_correlation_id": ITEM,
        "revision": 0,
        "task_index": 0,
        "tenant_id": TENANT,
        "dependency_mentions": (),
        "sent_at": SENT,
    }
    defaults.update(overrides)
    return PendingRequest(**defaults)  # type: ignore[arg-type]


@pytest.fixture
async def store(migrated_db):
    s = PostgresPendingStore(os.environ["MIND_VECTOR_STORE__DSN"])
    await s.open()
    yield s
    async with s._cursor() as cur:  # test-only cleanup, not part of the protocol
        await cur.execute("DELETE FROM pending_creation_requests;")
    await s.close()


async def test_add_returns_true_the_first_time(store: PostgresPendingStore) -> None:
    assert await store.add(REQUEST_ID, _request()) is True


async def test_add_returns_false_on_a_retry(store: PostgresPendingStore) -> None:
    await store.add(REQUEST_ID, _request())

    assert await store.add(REQUEST_ID, _request()) is False


async def test_get_returns_what_was_added(store: PostgresPendingStore) -> None:
    request = _request(dependency_mentions=("the pricing bug",))
    await store.add(REQUEST_ID, request)

    got = await store.get(REQUEST_ID)

    assert got == request


async def test_get_is_none_for_an_unknown_id(store: PostgresPendingStore) -> None:
    assert await store.get(REQUEST_ID) is None


async def test_renew_replaces_a_still_pending_entry(store: PostgresPendingStore) -> None:
    await store.add(REQUEST_ID, _request(revision=0))

    await store.renew(REQUEST_ID, _request(revision=1))

    got = await store.get(REQUEST_ID)
    assert got is not None and got.revision == 1


async def test_renew_is_a_noop_once_the_entry_is_gone(store: PostgresPendingStore) -> None:
    """The retry's reply was matched and the entry discarded before the
    sender's renew() arrived -- renew must not bring it back."""
    await store.add(REQUEST_ID, _request())
    await store.discard(REQUEST_ID)

    await store.renew(REQUEST_ID, _request(revision=5))

    assert await store.get(REQUEST_ID) is None


async def test_discard_removes_the_entry(store: PostgresPendingStore) -> None:
    await store.add(REQUEST_ID, _request())

    await store.discard(REQUEST_ID)

    assert await store.get(REQUEST_ID) is None


async def test_discard_is_a_noop_for_an_unknown_id(store: PostgresPendingStore) -> None:
    await store.discard(REQUEST_ID)  # must not raise


async def test_mark_reported_is_true_the_first_time(store: PostgresPendingStore) -> None:
    await store.add(REQUEST_ID, _request())

    assert await store.mark_reported(REQUEST_ID) is True


async def test_mark_reported_is_false_the_second_time(store: PostgresPendingStore) -> None:
    await store.add(REQUEST_ID, _request())
    await store.mark_reported(REQUEST_ID)

    assert await store.mark_reported(REQUEST_ID) is False


async def test_mark_reported_is_false_for_an_unknown_id(store: PostgresPendingStore) -> None:
    assert await store.mark_reported(REQUEST_ID) is False


async def test_renew_clears_a_previous_report(store: PostgresPendingStore) -> None:
    await store.add(REQUEST_ID, _request())
    await store.mark_reported(REQUEST_ID)

    await store.renew(REQUEST_ID, _request(revision=1))

    assert await store.mark_reported(REQUEST_ID) is True


async def test_all_returns_every_pending_entry(store: PostgresPendingStore) -> None:
    await store.add(REQUEST_ID, _request())
    await store.add(OTHER_REQUEST_ID, _request(bridge_correlation_id=OTHER_ITEM))

    entries = await store.all()

    assert {correlation_id for correlation_id, _ in entries} == {REQUEST_ID, OTHER_REQUEST_ID}


async def test_all_excludes_a_discarded_entry(store: PostgresPendingStore) -> None:
    await store.add(REQUEST_ID, _request())
    await store.add(OTHER_REQUEST_ID, _request(bridge_correlation_id=OTHER_ITEM))
    await store.discard(REQUEST_ID)

    entries = await store.all()

    assert {correlation_id for correlation_id, _ in entries} == {OTHER_REQUEST_ID}


async def test_a_request_survives_a_restart(store: PostgresPendingStore) -> None:
    """The actual point of this store over the in-memory one (#918): a
    second, independent instance -- as a fresh process after a restart
    would construct -- sees the request the first one wrote, with no
    shared Python state between them, only the same database."""
    request = _request(dependency_mentions=("the pricing bug",))
    await store.add(REQUEST_ID, request)

    restarted = PostgresPendingStore(os.environ["MIND_VECTOR_STORE__DSN"])
    await restarted.open()
    try:
        got = await restarted.get(REQUEST_ID)
        assert got == request
    finally:
        await restarted.close()


async def test_one_instance_is_safe_from_three_real_loops_at_once(
    store: PostgresPendingStore,
) -> None:
    """The actual point of the per-loop pool (#918): `TaskCreationFlow`
    shares one `pending` across the sender's loop, the listener's loop
    and the overdue timer's loop -- three real threads, each running
    its own event loop at the same time, not just three sequential
    `asyncio.run()` calls."""
    request = _request()
    await store.add(REQUEST_ID, request)

    results: list[PendingRequest | None] = [None, None]
    errors: list[BaseException] = []

    def _run_on_its_own_loop(slot: int) -> None:
        loop = asyncio.new_event_loop()
        try:
            results[slot] = loop.run_until_complete(store.get(REQUEST_ID))
        except BaseException as exc:  # noqa: BLE001 -- surfaced via `errors`, not swallowed
            errors.append(exc)
        finally:
            # This loop's own pool must close while this loop still runs --
            # closing it from elsewhere, after this loop is gone, is exactly
            # the restriction `close()`'s docstring explains.
            loop.run_until_complete(store.close())
            loop.close()

    threads = [threading.Thread(target=_run_on_its_own_loop, args=(i,)) for i in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert errors == []
    assert results == [request, request]
