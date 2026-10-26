"""A fingerprint of a generated dataset, so the evaluation set can be shown to be untouched.

Every file the generator writes is hashed into `SEAL.json`. Before the final evaluation, `--verify` recomputes
the hashes: any edited, added or missing file is reported. The seed alone is not enough proof, because the
same seed gives different data once the generator's code changes.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

SEAL_NAME = "SEAL.json"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _files(directory: Path) -> list[Path]:
    return sorted(p for p in directory.iterdir() if p.is_file() and p.name != SEAL_NAME)


def seal(directory: Path, seed: int) -> dict:
    record = {"seed": seed, "files": {p.name: _sha256(p) for p in _files(directory)}}
    (directory / SEAL_NAME).write_text(json.dumps(record, indent=2), encoding="utf-8")
    return record


def verify(directory: Path) -> list[str]:
    """Differences between the directory and its seal; an empty list means untouched."""
    seal_file = directory / SEAL_NAME
    if not seal_file.is_file():
        return [f"{directory}: no {SEAL_NAME}"]
    sealed = json.loads(seal_file.read_text(encoding="utf-8"))["files"]
    present = {p.name: p for p in _files(directory)}
    errors = [f"{name}: missing" for name in sealed if name not in present]
    errors += [f"{name}: changed" for name, path in present.items() if name in sealed and _sha256(path) != sealed[name]]
    errors += [f"{name}: not in the seal" for name in present if name not in sealed]
    return errors
