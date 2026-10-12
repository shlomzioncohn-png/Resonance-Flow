"""How Whisper is run lives in two places on purpose: service code (src/mind/multimodal/asr.py)
must not import the benchmark scripts, and the benchmark scripts record the decision (#135).
These tests make the two copies fail loudly if they ever drift apart -- the service must run
the configuration that was measured. No model weights needed.
"""

from __future__ import annotations

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


def test_the_dockerfile_bakes_the_chosen_model(tmp_path):
    from pathlib import Path

    dockerfile = (Path(__file__).resolve().parent.parent / "Dockerfile").read_text()
    assert f"Systran/faster-whisper-{CHOSEN_WHISPER_SIZE}" in dockerfile
    assert f"/opt/models/whisper/{CHOSEN_WHISPER_SIZE}" in dockerfile
