"""#245: resolve_dependency_targets() against a real pgvector store --
needed for real similarity scores, which a fake store can't produce."""

import os
from unittest.mock import AsyncMock, call, patch

import pytest
from benchmarks.vector_store_test_table import create_vector_store_test_table

from mind.config import VectorStoreSettings, get_settings
from mind.embeddings import embed, task_text
from mind.embeddings.base import SearchResult
from mind.embeddings.dependency_resolution import resolve_dependency_targets
from mind.embeddings.embeddings import EMBEDDING_DIM
from mind.embeddings.pgvector_store import PgVectorStore


@pytest.fixture(scope="module")
async def store():
    """Same pattern as test_vector_store.py's `store` fixture."""
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test_deps",
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


async def test_resolves_a_strong_match(store):
    """Realistic short mention, not a copy of the task's own words."""
    await store.upsert(
        "task-db-upgrade", embed(task_text("Upgrade the production database", "Move to the new major version"))
    )

    [candidates] = await resolve_dependency_targets(store, ["the DB upgrade"])

    assert candidates[0].task_id == "task-db-upgrade"


async def test_resolves_a_weak_match(store):
    """A vaguer mention still resolves to the right task, lower score --
    threshold=0.0 on purpose: this test is about ranking, not filtering
    (see test_threshold_* below for that)."""
    await store.upsert(
        "task-db-upgrade", embed(task_text("Upgrade the production database", "Move to the new major version"))
    )
    await store.upsert(
        "task-unrelated", embed(task_text("Plan the team offsite catering", "Order food for the offsite"))
    )

    [strong] = await resolve_dependency_targets(store, ["the DB upgrade"], threshold=0.0)
    [weak] = await resolve_dependency_targets(store, ["something about a database maybe"], threshold=0.0)

    assert strong[0].task_id == "task-db-upgrade"
    assert weak[0].task_id == "task-db-upgrade"
    assert weak[0].similarity < strong[0].similarity


async def test_weak_match_with_a_similar_sibling_still_lands_in_top_k(store):
    """Correct = in top k, not necessarily #1 -- a real near-duplicate
    sibling is allowed to outrank it. threshold=0.0: ranking, not filtering."""
    await store.upsert(
        "task-db-upgrade", embed(task_text("Upgrade the production database", "Move to the new major version"))
    )
    await store.upsert(
        "task-db-backup", embed(task_text("Back up the production database", "Create a backup before the migration"))
    )
    await store.upsert(
        "task-catering", embed(task_text("Plan the team offsite catering", "Order food for the offsite"))
    )

    [candidates] = await resolve_dependency_targets(
        store, ["something about the database maybe"], k=2, threshold=0.0
    )

    assert "task-db-upgrade" in {c.task_id for c in candidates}


async def test_sends_is_query_true_to_embed():
    store = AsyncMock()
    store.search.return_value = []
    with patch("mind.embeddings.search.embed_async") as mock_embed_async:
        mock_embed_async.return_value = [0.0] * EMBEDDING_DIM
        await resolve_dependency_targets(store, ["the DB upgrade", "release notes for v2.3"])

    assert mock_embed_async.await_args_list == [
        call("the DB upgrade", is_query=True),
        call("release notes for v2.3", is_query=True),
    ]


async def test_no_match_when_the_store_is_empty(store):
    [candidates] = await resolve_dependency_targets(store, ["the DB upgrade"])
    assert candidates == []


async def test_no_strong_match_when_the_store_has_only_unrelated_tasks(store):
    """Threshold rejects every candidate outright -- not just a relatively
    lower score, an actually empty result (#133's real contract)."""
    await store.upsert(
        "task-catering", embed(task_text("Plan the team offsite catering", "Order food for the offsite"))
    )
    await store.upsert(
        "task-standup-notes", embed(task_text("Write up standup notes", "Notes from yesterday's standup"))
    )

    [candidates] = await resolve_dependency_targets(
        store, ["the DB upgrade"], threshold=get_settings().dependency_match_threshold
    )

    assert candidates == []


async def test_resolves_multiple_references_independently_in_order(store):
    await store.upsert(
        "task-db-upgrade", embed(task_text("Upgrade the production database", "Move to the new major version"))
    )
    await store.upsert(
        "task-release-notes", embed(task_text("Write release notes", "Release notes for v2.3"))
    )

    results = await resolve_dependency_targets(store, ["the DB upgrade", "release notes for v2.3"], threshold=0.0)

    assert len(results) == 2
    assert results[0][0].task_id == "task-db-upgrade"
    assert results[1][0].task_id == "task-release-notes"


async def test_propagates_embed_error_for_an_empty_reference(store):
    """match= confirms this is embed()'s own "no extractable content"
    error, not just any ValueError from somewhere else in the chain."""
    with pytest.raises(ValueError, match="no extractable content"):
        await resolve_dependency_targets(store, [""])


async def test_threshold_defaults_to_settings_not_unfiltered(store):
    """#245's real default is "no match" (#133), not "everything" -- an
    explicit threshold=None call must filter exactly like passing
    Settings.dependency_match_threshold would."""
    await store.upsert(
        "task-db-upgrade", embed(task_text("Upgrade the production database", "Move to the new major version"))
    )
    await store.upsert(
        "task-catering", embed(task_text("Plan the team offsite catering", "Order food for the offsite"))
    )

    [candidates] = await resolve_dependency_targets(store, ["the DB upgrade"])

    assert {c.task_id for c in candidates} == {"task-db-upgrade"}


async def test_threshold_drops_weak_candidates(store):
    await store.upsert(
        "task-db-upgrade", embed(task_text("Upgrade the production database", "Move to the new major version"))
    )
    await store.upsert(
        "task-catering", embed(task_text("Plan the team offsite catering", "Order food for the offsite"))
    )

    [candidates] = await resolve_dependency_targets(
        store, ["the DB upgrade"], threshold=get_settings().dependency_match_threshold
    )

    assert {c.task_id for c in candidates} == {"task-db-upgrade"}


async def test_threshold_can_empty_out_a_reference_with_no_strong_candidate():
    """#244: no event when nothing clears the threshold (#133), not a
    placeholder -- an empty list, same shape as #245's own no-match case."""
    store = AsyncMock()
    store.search.return_value = [SearchResult(task_id="task-weak", similarity=0.2)]

    [candidates] = await resolve_dependency_targets(store, ["the DB upgrade"], threshold=0.8)

    assert candidates == []


async def test_threshold_comparison_is_inclusive_at_the_boundary():
    """Deterministic, not model-dependent -- the >= itself, not whether a
    real embedding happens to land above or below 0.8. Threshold kept as
    a literal on purpose: this tests the comparison operator in the
    abstract, independent of whatever Settings.dependency_match_threshold
    currently is."""
    store = AsyncMock()
    store.search.return_value = [
        SearchResult(task_id="task-above", similarity=0.81),
        SearchResult(task_id="task-at", similarity=0.8),
        SearchResult(task_id="task-below", similarity=0.79),
    ]

    [candidates] = await resolve_dependency_targets(store, ["the DB upgrade"], threshold=0.8)

    assert {c.task_id for c in candidates} == {"task-above", "task-at"}


async def test_empty_references_list_returns_empty_list_without_calling_the_store():
    store = AsyncMock()

    results = await resolve_dependency_targets(store, [])

    assert results == []
    store.search.assert_not_awaited()


async def test_duplicate_references_are_each_resolved_independently():
    store = AsyncMock()
    store.search.return_value = [SearchResult(task_id="task-1", similarity=0.9)]

    results = await resolve_dependency_targets(store, ["the DB upgrade", "the DB upgrade"])

    assert len(results) == 2
    assert results[0] == results[1]
    assert store.search.await_count == 2


async def test_propagates_a_store_search_failure():
    store = AsyncMock()
    store.search.side_effect = RuntimeError("connection reset")

    with pytest.raises(RuntimeError, match="connection reset"):
        await resolve_dependency_targets(store, ["the DB upgrade"])
