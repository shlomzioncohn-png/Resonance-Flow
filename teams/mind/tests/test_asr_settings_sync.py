"""How Whisper is run lives in two places on purpose: service code (src/mind/multimodal/asr.py)
must not import the benchmark scripts, and the benchmark scripts record the decision (#135).
These tests make the two copies fail loudly if they ever drift apart -- the service must run
the configuration that was measured. No model weights needed.
"""

from __future__ import annotations

from pathlib import Path

from benchmarks.asr_benchmark import BEAM_SIZE, CHOSEN_WHISPER_SIZE, COMPUTE_TYPE, CPU_THREADS

from mind.config import ASRSettings
from mind.multimodal import asr


def test_the_default_model_is_the_benchmark_decision():
    assert ASRSettings.model_fields["whisper_model"].default == CHOSEN_WHISPER_SIZE


def test_the_service_runs_whisper_as_the_benchmark_measured_it():
    assert (asr.COMPUTE_TYPE, asr.BEAM_SIZE, asr.CPU_THREADS) == (
        COMPUTE_TYPE,
        BEAM_SIZE,
        CPU_THREADS,
    )


_DOCKERFILE = (Path(__file__).resolve().parent.parent / "Dockerfile").read_text()


def test_the_dockerfile_bakes_the_pinned_revision_of_the_chosen_model():
    pinned = asr.PINNED_WHISPER_MODELS[CHOSEN_WHISPER_SIZE]
    assert f"ARG WHISPER_REPO={pinned.repo}\n" in _DOCKERFILE
    assert f"ARG WHISPER_REVISION={pinned.revision}\n" in _DOCKERFILE
    assert f"ARG WHISPER_MODEL_SHA256={pinned.model_sha256}\n" in _DOCKERFILE
    assert f"/opt/models/whisper/{CHOSEN_WHISPER_SIZE}" in _DOCKERFILE


def test_the_model_layer_stays_above_the_source_so_ci_caches_it():
    # Team-lead review on #478: below `COPY src`, the 145 MB download would rerun
    # on every code change instead of coming from CI's layer cache.
    assert _DOCKERFILE.index("snapshot_download(") < _DOCKERFILE.index("COPY src ./src")
