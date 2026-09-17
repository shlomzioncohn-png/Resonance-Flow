import pytest
from pydantic import ValidationError

from mind.contracts.base_enums import SourceType
from mind.contracts.raw_object_event_enums import Modality
from mind.ingestion.normalized_record import NormalizedRecord


VALID_HASH = "sha256:" + "a" * 64


def test_accepts_valid_normalized_record() -> None:
    record = NormalizedRecord(
        record_id=VALID_HASH,
        source_ref="FUNSD/testing_data/images/82092117.png",
        content=b"fake image bytes",
        content_type="image/png",
        modality=Modality.SCANNED_DOC,
        source_type=SourceType.SCANNED_DOC,
        metadata={
            "dataset": "FUNSD",
            "split": "testing_data",
        },
    )

    assert record.record_id == VALID_HASH
    assert record.source_ref == "FUNSD/testing_data/images/82092117.png"
    assert record.content == b"fake image bytes"
    assert record.content_type == "image/png"
    assert record.modality == Modality.SCANNED_DOC
    assert record.source_type == SourceType.SCANNED_DOC
    assert record.metadata["dataset"] == "FUNSD"


def test_accepts_missing_content_type() -> None:
    record = NormalizedRecord(
        record_id=VALID_HASH,
        source_ref="FUNSD/testing_data/images/82092117.png",
        content=b"fake image bytes",
        modality=Modality.SCANNED_DOC,
        source_type=SourceType.SCANNED_DOC,
    )

    assert record.content_type is None
    assert record.metadata == {}


def test_rejects_invalid_record_id() -> None:
    with pytest.raises(ValidationError):
        NormalizedRecord(
            record_id="not-a-valid-hash",
            source_ref="FUNSD/testing_data/images/82092117.png",
            content=b"fake image bytes",
            content_type="image/png",
            modality=Modality.SCANNED_DOC,
            source_type=SourceType.SCANNED_DOC,
        )


def test_rejects_empty_source_ref() -> None:
    with pytest.raises(ValidationError):
        NormalizedRecord(
            record_id=VALID_HASH,
            source_ref="",
            content=b"fake image bytes",
            content_type="image/png",
            modality=Modality.SCANNED_DOC,
            source_type=SourceType.SCANNED_DOC,
        )


def test_rejects_unknown_fields() -> None:
    with pytest.raises(ValidationError):
        NormalizedRecord(
            record_id=VALID_HASH,
            source_ref="FUNSD/testing_data/images/82092117.png",
            content=b"fake image bytes",
            content_type="image/png",
            modality=Modality.SCANNED_DOC,
            source_type=SourceType.SCANNED_DOC,
            unexpected_field="value",
        )
        