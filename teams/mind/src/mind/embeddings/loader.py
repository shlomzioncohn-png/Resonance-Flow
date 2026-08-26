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
the corpora"), the raw files are gitignored under `eval/datasets/local-data/`.

So "the loader" here does what a real object-storage loader would: given
the manifest, fetch each record's raw content (from FUNSD's public source,
the closest available equivalent to a real remote store for this slice),
verify it against the committed checksum, and hand back plain text. Once
cached under `local-data/` it's not re-downloaded.

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
from urllib.request import urlopen

DATASETS_DIR = Path(__file__).resolve().parents[3] / "eval" / "datasets"
MANIFEST_PATH = DATASETS_DIR / ".manifest.json"
SHA256_PATH = DATASETS_DIR / ".sha256"


@dataclass(frozen=True)
class Record:
    source_ref: str
    text: str


def _load_manifest() -> dict:
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


def _fetch_missing_files(manifest: dict, checksums: dict[str, str]) -> None:
    """Download the source zip once and extract only the files the
    manifest references that aren't already cached locally."""
    needed = [
        DATASETS_DIR / rel_path
        for record in manifest["records"]
        for rel_path in record["files"]
    ]
    missing = [p for p in needed if not p.exists()]
    if not missing:
        return

    with urlopen(manifest["source_url"], timeout=60) as resp:
        zip_bytes = resp.read()

    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        for path in missing:
            rel_path = path.relative_to(DATASETS_DIR).as_posix()
            # manifest paths are "local-data/funsd/dataset/testing_data/..."
            # -- inside the zip it's just "dataset/testing_data/...".
            zip_member = rel_path.split("local-data/funsd/", 1)[1]
            data = zf.read(zip_member)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)


def _verify_checksum(path: Path, expected_sha: str) -> None:
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected_sha:
        raise ValueError(f"Checksum mismatch for {path}: expected {expected_sha}, got {actual}")


def _annotation_to_text(annotation: dict) -> str:
    """FUNSD annotation -> plain text: join every non-empty form-field
    text value, in document order."""
    return " ".join(item["text"] for item in annotation["form"] if item["text"].strip())


def fetch_real_slice() -> list[Record]:
    """Fetch (downloading + caching under local-data/ if needed), verify,
    and return the FUNSD records described by the manifest, as (id, text)
    pairs ready for embed()."""
    manifest = _load_manifest()
    checksums = _load_checksums()
    _fetch_missing_files(manifest, checksums)

    records = []
    for record in manifest["records"]:
        annotation_path = None
        for rel_path in record["files"]:
            full_path = DATASETS_DIR / rel_path
            _verify_checksum(full_path, checksums[rel_path])
            if rel_path.endswith(".json"):
                annotation_path = full_path

        annotation = json.loads(annotation_path.read_text())
        text = _annotation_to_text(annotation)
        records.append(Record(source_ref=record["id"], text=text))

    return records


if __name__ == "__main__":
    for r in fetch_real_slice():
        print(f"--- {r.source_ref} ---")
        print(r.text[:300])
        print()
