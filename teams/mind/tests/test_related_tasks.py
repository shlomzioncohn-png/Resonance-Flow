"""#472: find_related() against a real pgvector store -- needed for real
similarity scores, which a fake store can't produce."""

import os
import time
from unittest.mock import AsyncMock

import pytest
from benchmarks.vector_store_test_table import create_vector_store_test_table

from mind.config import VectorStoreSettings
from mind.embeddings import embed, task_text
from mind.embeddings.pgvector_store import PgVectorStore
from mind.embeddings.related_tasks import find_related


@pytest.fixture(scope="module")
async def store():
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test_related",
    )
    await create_vector_store_test_table(settings)
    s = PgVectorStore(settings)
    await s.open()
    yield s
    await s.close()


@pytest.fixture(autouse=True)
async def clean_store(store):
    await store.clear()
    yield


async def test_a_known_related_pair_is_found(store):
    """prod/staging deploys of the same change -- related, not duplicate
    (#471's own hard-pair siblings), which is exactly #472's use case."""
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
    """The issue calls this out explicitly: the single most common bug in
    this feature. threshold=0.0 on purpose -- this isolates the
    exclusion itself from threshold filtering, which could otherwise
    hide the bug by coincidence."""
    await store.upsert(
        "task-deploy-prod", embed(task_text("Push the DB migration out to prod", ""))
    )

    results = await find_related(store, "task-deploy-prod", threshold=0.0)

    assert "task-deploy-prod" not in {r.task_id for r in results}


async def test_a_task_with_no_embedding_yet_returns_an_empty_list(store):
    """The query task_id was never indexed -- store.get_vector() returns
    None. Must be a clean [], never a raised error."""
    results = await find_related(store, "task-never-indexed")

    assert results == []


async def test_the_only_row_in_the_index_is_itself_so_nothing_is_related(store):
    """The query task is indexed, but it's the only row -- after excluding
    itself there's nothing left to compare against. Different code path
    from an unindexed task_id (get_vector succeeds; search() just finds
    no other rows)."""
    await store.upsert(
        "task-deploy-prod", embed(task_text("Push the DB migration out to prod", ""))
    )

    results = await find_related(store, "task-deploy-prod", threshold=0.0)

    assert results == []


async def test_no_candidate_clears_the_threshold(store):
    """Candidates exist, but none clear the floor -- a real similarity
    query, not a mocked one, and threshold=1.0 forces this deterministically
    without relying on a borderline model score."""
    await store.upsert(
        "task-deploy-prod", embed(task_text("Push the DB migration out to prod", ""))
    )
    await store.upsert(
        "task-deploy-staging", embed(task_text("Push the DB migration out to staging", ""))
    )

    results = await find_related(store, "task-deploy-prod", threshold=1.0)

    assert results == []


async def test_requests_exclude_task_id_from_the_store():
    """Deterministic: confirms find_related() actually asks the store to
    exclude the query task_id, not just that it happens not to come back
    in a particular real-model run."""
    store = AsyncMock()
    store.get_vector.return_value = [0.0] * 4
    store.search.return_value = []

    await find_related(store, "task-deploy-prod", k=5, threshold=0.0)

    store.get_vector.assert_awaited_once_with("task-deploy-prod")
    store.search.assert_awaited_once_with([0.0] * 4, k=5, exclude_task_id="task-deploy-prod")


async def test_find_related_responds_within_a_generous_latency_bound(store):
    """Not a real p50/p95 proof (see benchmarks/related_tasks_benchmark.py
    for that) -- just a basic regression guard, generous on purpose."""
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
