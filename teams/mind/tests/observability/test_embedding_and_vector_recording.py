"""Embedding and vector-query calls go through record_call() (#482, proposed to R3).

On fakes -- a stand-in for the sentence-transformers model, a mocked pool and
cursor for pgvector -- so what is checked is the recording, not the model or
the database, which tests/test_vector_store.py covers.
"""

import uuid
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, MagicMock

import numpy as np
import pytest

from mind.config import VectorStoreSettings
from mind.embeddings import embeddings
from mind.embeddings.pgvector_store import PgVectorStore
from mind.observability.tracking import CallKind, CallStatus, _reset_for_tests, get_recent_calls

TENANT = uuid.UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")


@pytest.fixture(autouse=True)
def _clean_call_records():
    _reset_for_tests()


class _FakeModel:
    def __init__(self, fail: bool = False) -> None:
        self.fail = fail

    def encode(self, texts, normalize_embeddings: bool):
        if self.fail:
            raise RuntimeError("encode failed")
        if isinstance(texts, str):
            return np.zeros(embeddings.EMBEDDING_DIM)
        return np.zeros((len(texts), embeddings.EMBEDDING_DIM))


def test_an_embedding_is_recorded_with_its_cpu(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(embeddings, "_get_model", lambda: _FakeModel())

    assert len(embeddings.embed("Finish the cost sheet")) == embeddings.EMBEDDING_DIM

    [call] = get_recent_calls()
    assert (call.model_name, call.kind, call.status) == (
        embeddings.EMBEDDING_CALL_NAME,
        CallKind.EMBEDDING,
        CallStatus.SUCCESS,
    )
    assert call.cpu_s is not None


def test_a_batch_is_one_call(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(embeddings, "_get_model", lambda: _FakeModel())

    embeddings.embed_batch(["one", "two", "three"])

    assert len(get_recent_calls()) == 1


def test_a_failed_encode_is_recorded_as_an_error_and_raised(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(embeddings, "_get_model", lambda: _FakeModel(fail=True))

    with pytest.raises(RuntimeError, match="encode failed"):
        embeddings.embed("Finish the cost sheet")
    [call] = get_recent_calls()
    assert call.status == CallStatus.ERROR


def test_empty_text_is_rejected_before_anything_is_recorded():
    with pytest.raises(ValueError):
        embeddings.embed("   ")
    assert get_recent_calls() == []


@asynccontextmanager
async def _ctx(value):
    yield value


def _mocked_index(cursor: AsyncMock) -> PgVectorStore:
    index = PgVectorStore.__new__(PgVectorStore)  # no real pool
    index._settings = VectorStoreSettings(
        dsn="postgresql://u:p@h:5432/d", table="task_embeddings_test", search_effort=400
    )
    index._ready = True
    connection = MagicMock()
    connection.cursor = MagicMock(side_effect=lambda: _ctx(cursor))
    index._pool = MagicMock()
    index._pool.connection = MagicMock(side_effect=lambda: _ctx(connection))
    return index


async def test_a_vector_query_is_recorded_with_latency_only():
    cursor = AsyncMock()
    cursor.fetchall.return_value = [("task-1", 0.93, False)]

    results = await _mocked_index(cursor).for_tenant(TENANT).search([0.1, 0.2], k=5)

    assert [r.task_id for r in results] == ["task-1"]
    [call] = get_recent_calls()
    assert (call.model_name, call.kind, call.status) == (
        "pgvector-search",
        CallKind.VECTOR_QUERY,
        CallStatus.SUCCESS,
    )
    assert (call.cpu_s, call.cost) == (None, None)


async def test_a_failed_vector_query_is_recorded_as_an_error_and_raised():
    cursor = AsyncMock()
    cursor.execute.side_effect = RuntimeError("database gone")

    with pytest.raises(RuntimeError, match="database gone"):
        await _mocked_index(cursor).for_tenant(TENANT).search([0.1, 0.2], k=5)
    [call] = get_recent_calls()
    assert (call.kind, call.status) == (CallKind.VECTOR_QUERY, CallStatus.ERROR)
