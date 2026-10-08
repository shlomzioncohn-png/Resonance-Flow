"""
Real-record loader for the embeddings ingest pipeline.

There is no R2 loader anywhere in this repo (checked; Bridge's object
storage work uses MinIO, not R2, and no loader code exists for either).
What *does* exist is a real-data manifest for a small FUNSD slice --
`eval/datasets/.manifest.json` + `.sha256` -- added in PR #141
(`yonit/funsd-foundation-gate`, not yet merged; this branch pulled in just
those two files). The manifest lists two real FUNSD test-split documents
by id, with checksums, but not the documents themselves -- per
`eval/datasets/README.md`'s own rule ("manifests + checksums only, never
the corpora"), the raw files are gitignored under `local-data/` off
`teams/mind/` (same convention as `ocr_asr_benchmark.py`'s
`load_manifest()`, so both loaders share one cache).

So "the loader" here does what a real object-storage loader would: given
the manifest, fetch each record's raw content (from FUNSD's public source,
the closest available equivalent to a real remote store for this slice),
verify it against the committed checksum, and hand back plain text. Once
cached it's not re-downloaded.

FUNSD annotation JSON is a list of form fields (`form: [{text, label,
words, ...}, ...]`); the text handed to embed() is those fields' text
values joined in document order -- a reasonable plain-text reconstruction
of the form, the same shape a real OCR/extraction pipeline would produce.
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
# ocr_asr_benchmark.py's load_manifest() (not DATASETS_DIR, which
# previously duplicated the local-data/ folder).
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

    # manifest paths are "<zip_root_prefix>dataset/testing_data/..." --
    # inside the zip it's just "dataset/testing_data/...". The prefix
    # comes from the manifest, not hardcoded here, so a future manifest
    # using a different layout doesn't hit an IndexError from a split()
    # that silently assumed this one's.
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
        # Otherwise the bad file stays cached: the next run sees it exists,
        # skips the download, and fails the same way forever.
        path.unlink()
        raise ValueError(f"Checksum mismatch for {path}: expected {expected_sha}, got {actual}")


def _annotation_to_text(annotation: dict[str, Any]) -> str:
    """FUNSD annotation -> plain text: join every non-empty form-field
    text value, in document order."""
    return " ".join(item["text"] for item in annotation["form"] if item["text"].strip())


def fetch_real_slice() -> list[Record]:
    """Fetch (downloading + caching under local-data/ if needed), verify,
    and return the FUNSD records described by the manifest, as (id, text)
    pairs ready for embed()."""
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
