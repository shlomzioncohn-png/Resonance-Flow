"""
Tests for mind.embeddings.loader -- no network required (loader.py's
own network call, _fetch_missing_files' urlopen, is exercised only via a
patched stand-in that fails the test if it's ever actually called).
"""

from unittest.mock import patch

import pytest

from mind.embeddings import loader


def test_verify_checksum_mismatch_raises_and_deletes_the_bad_file(tmp_path):
    bad_file = tmp_path / "downloaded.bin"
    bad_file.write_bytes(b"not what we expected")

    with pytest.raises(ValueError, match="Checksum mismatch"):
        loader._verify_checksum(bad_file, expected_sha="0" * 64)

    # Otherwise the next run sees the file exists, skips the download, and
    # fails the same way forever -- see _verify_checksum's comment.
    assert not bad_file.exists()


def test_fetch_missing_files_does_not_redownload_a_cached_file(tmp_path, monkeypatch):
    monkeypatch.setattr(loader, "_PROJECT_ROOT", tmp_path)
    cached = tmp_path / "local-data" / "funsd" / "dataset" / "testing_data" / "images" / "1.png"
    cached.parent.mkdir(parents=True)
    cached.write_bytes(b"already here")

    manifest = {
        "source_url": "https://example.invalid/should-not-be-fetched.zip",
        "zip_root_prefix": "local-data/funsd/",
        "records": [{"id": "1", "files": [str(cached.relative_to(tmp_path).as_posix())]}],
    }

    with patch.object(loader, "urlopen") as mock_urlopen:
        loader._fetch_missing_files(manifest)

    mock_urlopen.assert_not_called()
    assert cached.read_bytes() == b"already here"


def test_annotation_to_text_joins_non_empty_field_text_in_order():
    annotation = {
        "form": [
            {"text": "Invoice Number:", "label": "question"},
            {"text": "", "label": "other"},  # blank fields are dropped
            {"text": "INV-2024-001", "label": "answer"},
            {"text": "  ", "label": "other"},  # whitespace-only also dropped
            {"text": "Total Due", "label": "question"},
        ]
    }

    assert loader._annotation_to_text(annotation) == "Invoice Number: INV-2024-001 Total Due"
