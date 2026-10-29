"""The Dockerfile bakes the embedding model the service loads, where it loads it from.
These tests fail loudly if the two ever drift apart. No model weights needed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from mind.config import EmbeddingModelSettings
from mind.embeddings.embeddings import _local_dir

_DOCKERFILE = (Path(__file__).resolve().parent.parent / "Dockerfile").read_text()
_NAME = EmbeddingModelSettings.model_fields["name"].default
_DIRNAME = _NAME.rsplit("/", 1)[-1]


def test_the_dockerfile_bakes_the_model_the_service_loads():
    assert f"ARG EMBEDDING_REPO={_NAME}\n" in _DOCKERFILE
    assert f"local_dir='/opt/models/embeddings/{_DIRNAME}'" in _DOCKERFILE
    assert "ENV EMBEDDING_MODELS_DIR=/opt/models/embeddings\n" in _DOCKERFILE


def test_the_model_layer_stays_above_the_source_so_ci_caches_it():
    assert _DOCKERFILE.index("ARG EMBEDDING_REPO=") < _DOCKERFILE.index("COPY src ./src")


def _settings(
    monkeypatch: pytest.MonkeyPatch, models_dir: Path, cache_dir: Path
) -> EmbeddingModelSettings:
    monkeypatch.setenv("EMBEDDING_MODELS_DIR", str(models_dir))
    monkeypatch.setenv("MODEL_CACHE_DIR", str(cache_dir))
    return EmbeddingModelSettings(_env_file=None)  # type: ignore[call-arg]


def test_the_baked_copy_wins_over_the_local_dev_one(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    baked, cache = tmp_path / "baked", tmp_path / "cache"
    (baked / _DIRNAME).mkdir(parents=True)
    (cache / _DIRNAME).mkdir(parents=True)
    assert _local_dir(_settings(monkeypatch, baked, cache)) == baked / _DIRNAME


def test_falls_back_to_the_local_dev_copy(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    cache = tmp_path / "cache"
    (cache / _DIRNAME).mkdir(parents=True)
    assert _local_dir(_settings(monkeypatch, tmp_path / "missing", cache)) == cache / _DIRNAME


def test_no_local_copy_means_a_download(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    missing = tmp_path / "missing"
    assert _local_dir(_settings(monkeypatch, missing, missing)) is None
