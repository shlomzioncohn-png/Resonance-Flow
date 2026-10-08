"""The Tesseract PSM lives in two places on purpose: service code (src/mind/multimodal/ocr.py)
must not import the benchmark scripts, and the benchmark scripts record the decision.
These tests make the two copies fail loudly if they ever drift apart, and check the
service really passes the constant to Tesseract. No tesseract binary needed.
"""

from __future__ import annotations

import subprocess
from types import SimpleNamespace

import pytest
from benchmarks.ocr_asr_benchmark import CHOSEN_PSM_ARGS

from mind.multimodal import ocr


def test_service_psm_matches_the_benchmark_decision():
    assert ocr.OCR_PSM_ARGS == CHOSEN_PSM_ARGS


def test_tesseract_is_called_with_the_psm_constant(monkeypatch: pytest.MonkeyPatch):
    marker = ["--psm", "99"]
    monkeypatch.setattr(ocr, "OCR_PSM_ARGS", marker)
    commands: list[list[str]] = []

    def fake_run(cmd, **kwargs):
        commands.append(cmd)
        return SimpleNamespace(returncode=0, stdout=b"header\n", stderr=b"")

    monkeypatch.setattr(subprocess, "run", fake_run)
    ocr._run_tesseract_tsv("tesseract", b"png")
    assert commands[0][-3:-1] == marker
