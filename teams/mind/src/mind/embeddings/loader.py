"""
Real FUNSD-slice loader for the embeddings ingest pipeline. No R2 loader
exists in this repo, so this reads `eval/datasets/.manifest.json`/`.sha256`
(PR #141), downloads/caches under `local-data/` (same convention as
`ocr_asr_benchmark.py`), verifies checksums, and returns plain text.
"""

import hashlib
import io
import json
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.request import urlopen

# teams/mind/ -- manifest paths are relative to here, matching
# ocr_asr_benchmark.py's load_manifest().
_PROJECT_ROOT = Path(__file__).resolve().parents[3]
DATASETS_DIR = _PROJECT_ROOT / "eval" / "datasets"
MANIFEST_PATH = DATASETS_DIR / ".manifest.json"
SHA256_PATH = DATASETS_DIR / ".sha256"


@dataclass(frozen=True)
class Record:
    source_ref: str
    text: str


def _load_manifest() -> dict[str, Any]:
    with open(MANIFEST_PATH) as f:
        return json.load(f)


def _load_checksums() -> dict[str, str]:
    checksums = {}
    with open(SHA256_PATH) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            sha, path = line.split(None, 1)
            checksums[path.strip()] = sha.strip()
    return checksums


def _fetch_missing_files(manifest: dict[str, Any]) -> None:
    """Download the source zip once and extract only the files the
    manifest references that aren't already cached locally."""
    needed = [
        _PROJECT_ROOT / rel_path
        for record in manifest["records"]
        for rel_path in record["files"]
    ]
    missing = [p for p in needed if not p.exists()]
    if not missing:
        return

    with urlopen(manifest["source_url"], timeout=60) as resp:
        zip_bytes = resp.read()

    # Prefix from the manifest, not hardcoded -- zip paths lack it.
    zip_root_prefix = manifest["zip_root_prefix"]
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        for path in missing:
            rel_path = path.relative_to(_PROJECT_ROOT).as_posix()
            if not rel_path.startswith(zip_root_prefix):
                raise ValueError(
                    f"Manifest file {rel_path!r} does not start with its own "
                    f"zip_root_prefix {zip_root_prefix!r}"
                )
            zip_member = rel_path[len(zip_root_prefix) :]
            data = zf.read(zip_member)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)


def _verify_checksum(path: Path, expected_sha: str) -> None:
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected_sha:
        path.unlink()  # else it stays cached and fails the same way forever
        raise ValueError(f"Checksum mismatch for {path}: expected {expected_sha}, got {actual}")


def _annotation_to_text(annotation: dict[str, Any]) -> str:
    """FUNSD annotation -> plain text, form-field text joined in order."""
    return " ".join(item["text"] for item in annotation["form"] if item["text"].strip())


def fetch_real_slice() -> list[Record]:
    """Fetch, verify, and return the manifest's FUNSD records as text."""
    manifest = _load_manifest()
    checksums = _load_checksums()
    _fetch_missing_files(manifest)

    records = []
    for record in manifest["records"]:
        annotation_path: Path | None = None
        for rel_path in record["files"]:
            full_path = _PROJECT_ROOT / rel_path
            if rel_path not in checksums:
                raise ValueError(
                    f"{rel_path!r} is listed in {MANIFEST_PATH.name} but has no entry in "
                    f"{SHA256_PATH.name} -- the two files are out of sync."
                )
            _verify_checksum(full_path, checksums[rel_path])
            if rel_path.endswith(".json"):
                annotation_path = full_path

        if annotation_path is None:
            raise ValueError(
                f"Manifest record {record['id']!r} has no .json annotation file "
                f"in its 'files' list: {record['files']!r}"
            )

        annotation = json.loads(annotation_path.read_text())
        text = _annotation_to_text(annotation)
        records.append(Record(source_ref=record["id"], text=text))

    return records


if __name__ == "__main__":
    for r in fetch_real_slice():
        print(f"--- {r.source_ref} ---")
        print(r.text[:300])
        print()
