"""#471: find_duplicate() against a real pgvector store -- needed for
real similarity scores, which a fake store can't produce."""

import os
from unittest.mock import AsyncMock, patch
from uuid import UUID

import pytest
from benchmarks.vector_store_test_table import create_vector_store_test_table

from mind.config import VectorStoreSettings, get_settings
from mind.embeddings import embed, task_text
from mind.embeddings.duplicate_detection import find_duplicate
from mind.embeddings.embeddings import EMBEDDING_DIM
from mind.embeddings.pgvector_store import PgVectorStore

TENANT = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
OTHER_TENANT = UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")


@pytest.fixture(scope="module")
async def index():
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],
        table="task_embeddings_test_dupes",
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


async def test_a_near_identical_task_is_flagged_as_a_likely_duplicate(store):
    existing = task_text(
        "Upgrade the production database", "Move to the new major version before Q2"
    )
    await store.upsert("task-db-upgrade", embed(existing))

    match = await find_duplicate(
        store,
        "Upgrade production DB",
        "Move to the new major version before Q2 ends",
        threshold=get_settings().duplicate_similarity_threshold,
    )

    assert match is not None
    assert match.task_id == "task-db-upgrade"


async def test_different_tasks_with_surface_wording_similarity_are_not_flagged(store):
    await store.upsert(
        "task-db-upgrade",
        embed(task_text("Upgrade the production database", "Move to the new major version")),
    )

    candidate_title, candidate_description = (
        "Database onboarding doc",
        "Write a guide for new hires on our DB setup",
    )
    match = await find_duplicate(
        store,
        candidate_title,
        candidate_description,
        threshold=get_settings().duplicate_similarity_threshold,
    )

    assert match is None


async def test_threshold_defaults_to_settings_when_not_passed(store):
    await store.upsert(
        "task-db-upgrade",
        embed(task_text("Upgrade the production database", "Move to the new major version")),
    )

    match = await find_duplicate(
        store, "Database onboarding doc", "Write a guide for new hires on our DB setup"
    )

    assert match is None


async def test_empty_store_never_flags_a_duplicate(store):
    match = await find_duplicate(
        store,
        "Upgrade the production database",
        "Move to the new version",
        threshold=get_settings().duplicate_similarity_threshold,
    )

    assert match is None


async def test_a_completed_task_is_never_flagged_as_a_duplicate(store):
    """find_duplicate() is built on resolve_dependency_targets() -- inherits
    its is_completed filter, doesn't need its own separate logic."""
    existing = task_text(
        "Upgrade the production database", "Move to the new major version before Q2"
    )
    await store.upsert("task-db-upgrade", embed(existing))
    await store.mark_completed("task-db-upgrade")

    match = await find_duplicate(
        store,
        "Upgrade production DB",
        "Move to the new major version before Q2 ends",
        threshold=0.0,
    )

    assert match is None


async def test_a_completed_top_match_does_not_starve_out_an_open_runner_up(store):
    """find_duplicate() uses k=1 -- the exact scenario where post-hoc
    filtering fails: if the single fetched row is completed and gets
    discarded afterward, nothing is left, even though a genuinely open
    duplicate exists just outside that k=1 window."""
    await store.upsert(
        "task-db-upgrade-done",
        embed(
            task_text("Upgrade the production database", "Move to the new major version before Q2")
        ),
    )
    await store.mark_completed("task-db-upgrade-done")
    await store.upsert(
        "task-db-upgrade-open",
        embed(
            task_text("Upgrade the production database", "Move to a newer major version before Q3")
        ),
    )

    match = await find_duplicate(
        store,
        "Upgrade production DB",
        "Move to the new major version before Q2 ends",
        threshold=0.0,
    )

    assert match is not None
    assert match.task_id == "task-db-upgrade-open"


async def test_uses_symmetric_embedding_not_the_mention_vs_passage_case():
    """#471 compares full task vs full task -- is_query=False. Reusing
    resolve_dependency_targets()'s default (True, for #245's short-mention
    case) would be the same silent-asymmetry bug #245 already had once."""
    store = AsyncMock()
    store.search.return_value = []
    with patch("mind.embeddings.search.embed_async") as mock_embed_async:
        mock_embed_async.return_value = [0.0] * EMBEDDING_DIM
        await find_duplicate(store, "Title", "Description", threshold=0.9)

    mock_embed_async.assert_awaited_once_with("Title Description", is_query=False)
    store.search.assert_awaited_once_with([0.0] * EMBEDDING_DIM, k=1, exclude_completed=True)


async def test_a_near_identical_task_in_another_tenant_is_not_a_duplicate(store, other_store):
    """#841: another customer's task is never flagged."""
    existing = task_text(
        "Upgrade the production database", "Move to the new major version before Q2"
    )
    await other_store.upsert("task-theirs", embed(existing))

    match = await find_duplicate(
        store, "Upgrade the production database", "Move to the new major version before Q2"
    )

    assert match is None
