"""#472: find_related() against a real pgvector store -- needed for real
similarity scores, which a fake store can't produce."""

import os
import time
from unittest.mock import AsyncMock
from uuid import UUID

import pytest
from benchmarks.vector_store_test_table import create_vector_store_test_table

from mind.config import VectorStoreSettings
from mind.embeddings import embed, task_text
from mind.embeddings.pgvector_store import PgVectorStore
from mind.embeddings.related_tasks import find_related

TENANT = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
OTHER_TENANT = UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")


@pytest.fixture(scope="module")
async def index():
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test_related",
    )
    await create_vector_store_test_table(settings)
    s = PgVectorStore(settings)
    await s.open()
    yield s
    await s.close()


@pytest.fixture
def store(index):
    return index.for_tenant(TENANT)


@pytest.fixture
def other_store(index):
    return index.for_tenant(OTHER_TENANT)


@pytest.fixture(autouse=True)
async def clean_store(index):
    await index.clear()
    yield


async def test_a_known_related_pair_is_found(store):
    """prod/staging deploys of the same change -- related, not duplicate."""
    await store.upsert(
        "task-deploy-prod", embed(task_text("Push the DB migration out to prod", ""))
    )
    await store.upsert(
        "task-deploy-staging", embed(task_text("Push the DB migration out to staging", ""))
    )

    results = await find_related(store, "task-deploy-prod")

    assert "task-deploy-staging" in {r.task_id for r in results}


async def test_an_unrelated_task_scores_below_threshold_and_is_not_returned(store):
    await store.upsert(
        "task-deploy-prod", embed(task_text("Push the DB migration out to prod", ""))
    )
    await store.upsert("task-catering", embed(task_text("Order food for the company offsite", "")))

    results = await find_related(store, "task-deploy-prod")

    assert "task-catering" not in {r.task_id for r in results}


async def test_the_query_task_never_appears_in_its_own_results(store):
    """threshold=0.0 -- isolates exclusion from threshold filtering."""
    await store.upsert(
        "task-deploy-prod", embed(task_text("Push the DB migration out to prod", ""))
    )

    results = await find_related(store, "task-deploy-prod", threshold=0.0)

    assert "task-deploy-prod" not in {r.task_id for r in results}


async def test_a_task_with_no_embedding_yet_returns_an_empty_list(store):
    """Unindexed task_id -- get_vector() returns None, not an error."""
    results = await find_related(store, "task-never-indexed")

    assert results == []


async def test_the_only_row_in_the_index_is_itself_so_nothing_is_related(store):
    """Indexed, but the only row -- nothing left after excluding itself."""
    await store.upsert(
        "task-deploy-prod", embed(task_text("Push the DB migration out to prod", ""))
    )

    results = await find_related(store, "task-deploy-prod", threshold=0.0)

    assert results == []


async def test_no_candidate_clears_the_threshold(store):
    """threshold=1.0 forces this deterministically."""
    await store.upsert(
        "task-deploy-prod", embed(task_text("Push the DB migration out to prod", ""))
    )
    await store.upsert(
        "task-deploy-staging", embed(task_text("Push the DB migration out to staging", ""))
    )

    results = await find_related(store, "task-deploy-prod", threshold=1.0)

    assert results == []


async def test_requests_exclude_task_id_from_the_store():
    """Deterministic: find_related() actually asks the store to exclude it."""
    store = AsyncMock()
    store.get_vector.return_value = [0.0] * 4
    store.search.return_value = []

    await find_related(store, "task-deploy-prod", k=5, threshold=0.0)

    store.get_vector.assert_awaited_once_with("task-deploy-prod")
    store.search.assert_awaited_once_with([0.0] * 4, k=5, exclude_task_id="task-deploy-prod")


@pytest.mark.parametrize("k", [0, -1])
async def test_rejects_a_non_positive_k_before_touching_the_store(k):
    store = AsyncMock()

    with pytest.raises(ValueError, match="k must be positive"):
        await find_related(store, "task-1", k=k)

    store.get_vector.assert_not_awaited()


async def test_find_related_responds_within_a_generous_latency_bound(store):
    """Basic regression guard, not a p50/p95 proof (see benchmarks/)."""
    await store.upsert(
        "task-deploy-prod", embed(task_text("Push the DB migration out to prod", ""))
    )
    await store.upsert(
        "task-deploy-staging", embed(task_text("Push the DB migration out to staging", ""))
    )

    start = time.perf_counter()
    await find_related(store, "task-deploy-prod")
    duration_ms = (time.perf_counter() - start) * 1000

    assert duration_ms < 1000


async def test_only_tasks_of_the_same_tenant_are_related(store, other_store):
    """#841: a near-identical task in another tenant is never returned."""
    await store.upsert(
        "task-deploy-prod", embed(task_text("Push the DB migration out to prod", ""))
    )
    await store.upsert(
        "task-deploy-staging", embed(task_text("Push the DB migration out to staging", ""))
    )
    await other_store.upsert(
        "task-theirs", embed(task_text("Push the DB migration out to prod", ""))
    )

    results = await find_related(store, "task-deploy-prod", threshold=0.0)

    assert [r.task_id for r in results] == ["task-deploy-staging"]
