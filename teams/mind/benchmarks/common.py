"""Helpers shared by the OCR and ASR benchmarks.

Dataset checksums (both benchmarks read a manifest + `sha256sum`-format file
under eval/datasets/) and in-process peak-memory sampling. Kept here, not in
either benchmark, so neither has to import the other.
"""

from __future__ import annotations

import hashlib
import threading
import time
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    # psutil is the optional [benchmark] extra -- imported lazily inside
    # sample_current_process, so this module (and the checksum helpers) stays
    # importable without it; this import only resolves the type hint.
    import psutil


def load_checksums(sha256_path: Path) -> dict[str, str]:
    """relative path (as written in the file) -> expected sha256, from a
    standard `sha256sum`-format file (`<hex digest>  <path>` per line)."""
    checksums: dict[str, str] = {}
    for line in sha256_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        digest, rel_path = line.split(None, 1)
        checksums[rel_path.strip()] = digest
    return checksums


def verify_checksum(path: Path, expected_sha256: str) -> None:
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected_sha256:
        raise RuntimeError(
            f"checksum mismatch for {path}: expected {expected_sha256}, got {actual} "
            "-- the local file doesn't match the dataset's committed sha256 file "
            "under eval/datasets/"
        )


def sample_current_process(
    stop_event: threading.Event, proc: psutil.Process, peak: list[int]
) -> None:
    """Poll `proc`'s RSS every 50ms until `stop_event` is set, keeping the
    maximum in `peak[0]`. Run on a thread alongside the work being measured."""
    import psutil  # optional [benchmark] extra

    while not stop_event.is_set():
        try:
            peak[0] = max(peak[0], proc.memory_info().rss)
        except psutil.NoSuchProcess:
            break
        time.sleep(0.05)
