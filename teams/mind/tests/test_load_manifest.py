"""Tests for load_manifest() in benchmarks/ocr_asr_benchmark.py -- the friendly errors.

A manifest/checksum mismatch used to surface as a bare KeyError from
`checksums[rel_path]`, while the missing-file case right above it already
raised a RuntimeError with a pointer to the fix (review feedback on PR #345).
"""

import hashlib
import json
from pathlib import Path

import pytest
from benchmarks import ocr_asr_benchmark
from benchmarks.ocr_asr_benchmark import load_manifest

IMAGE = "eval/real_slice/doc1.png"
ANNOTATION = "eval/real_slice/doc1.json"


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@pytest.fixture
def dataset(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """A tiny valid dataset under tmp_path, with load_manifest pointed at it."""
    (tmp_path / "eval" / "real_slice").mkdir(parents=True)
    (tmp_path / "eval" / "datasets").mkdir(parents=True)
    (tmp_path / IMAGE).write_bytes(b"png")
    (tmp_path / ANNOTATION).write_bytes(b"{}")
    manifest = {
        "source_url": "https://example.test/funsd",
        "records": [{"id": "doc1", "files": [IMAGE, ANNOTATION]}],
    }
    (tmp_path / "eval/datasets/.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    (tmp_path / "eval/datasets/.sha256").write_text(
        f"{_sha(b'png')}  {IMAGE}\n{_sha(b'{}')}  {ANNOTATION}\n", encoding="utf-8"
    )
    monkeypatch.setattr(ocr_asr_benchmark, "_PROJECT_ROOT", tmp_path)
    load_manifest.cache_clear()
    yield tmp_path
    load_manifest.cache_clear()


def test_valid_dataset_loads(dataset: Path):
    docs = load_manifest()
    assert docs["doc1"].image == dataset / IMAGE
    assert docs["doc1"].annotation == dataset / ANNOTATION


def test_path_missing_from_sha256_is_a_friendly_error_not_a_keyerror(dataset: Path):
    (dataset / "eval/datasets/.sha256").write_text(f"{_sha(b'png')}  {IMAGE}\n", encoding="utf-8")
    with pytest.raises(RuntimeError, match=r"doc1\.json.*no entry in \.sha256"):
        load_manifest()


def test_checksum_mismatch_is_reported(dataset: Path):
    (dataset / IMAGE).write_bytes(b"tampered")
    with pytest.raises(RuntimeError, match="checksum mismatch"):
        load_manifest()


def test_missing_data_file_points_to_the_download_instructions(dataset: Path):
    (dataset / ANNOTATION).unlink()
    with pytest.raises(RuntimeError, match=r"doc1\.json not found.*https://example\.test/funsd"):
        load_manifest()


def test_unknown_file_extension_is_a_clear_error_not_a_pydantic_one(dataset: Path):
    # e.g. RVL-CDIP page images are .tif; they must not be taken for annotations
    tif = "eval/real_slice/doc1.tif"
    (dataset / tif).write_bytes(b"tif")
    manifest = {"records": [{"id": "doc1", "files": [tif, ANNOTATION]}]}
    (dataset / "eval/datasets/.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    (dataset / "eval/datasets/.sha256").write_text(
        f"{_sha(b'tif')}  {tif}\n{_sha(b'{}')}  {ANNOTATION}\n", encoding="utf-8"
    )
    with pytest.raises(RuntimeError, match=r"doc1\.tif: unsupported file type '\.tif'"):
        load_manifest()


def test_record_without_an_image_is_a_clear_error(dataset: Path):
    manifest = {"records": [{"id": "doc1", "files": [ANNOTATION]}]}
    (dataset / "eval/datasets/.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(RuntimeError, match=r"record 'doc1'.*no image file"):
        load_manifest()
