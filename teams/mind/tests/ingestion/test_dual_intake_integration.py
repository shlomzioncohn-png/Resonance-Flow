"""Integration test proving both MIND intake paths share one output shape."""

import json
from datetime import datetime, timezone
from pathlib import Path

import pytest

from mind.contracts import RawObjectEvent
from mind.contracts.raw_object_event_enums import (
    ChangeType,
    ScopeDecision,
)
from mind.ingestion.corpus_loader import CorpusLoader
from mind.ingestion.normalized_record import NormalizedRecord
from mind.ingestion.raw_object_event_handler import handle_raw_object_event


MIND_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = MIND_ROOT / "eval" / "datasets" / ".manifest.json"

IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg"}


def require_real_funsd_slice() -> None:
    """Skip when the local FUNSD slice has not been downloaded."""

    if not MANIFEST_PATH.exists():
        pytest.skip("FUNSD manifest is not available")

    manifest = json.loads(
        MANIFEST_PATH.read_text(encoding="utf-8")
    )

    image_paths = [
        MIND_ROOT / file_ref
        for manifest_record in manifest["records"]
        for file_ref in manifest_record["files"]
        if Path(file_ref).suffix.lower() in IMAGE_SUFFIXES
    ]

    missing_paths = [
        path
        for path in image_paths
        if not path.exists()
    ]

    if missing_paths:
        pytest.skip(
            "Local FUNSD real-data slice is not available"
        )


def make_bridge_event(
    record: NormalizedRecord,
    object_key: str,
) -> RawObjectEvent:
    """Create a Bridge event for a real FUNSD file."""

    return RawObjectEvent(
        object_key=object_key,
        source_type=record.source_type,
        tenant_id="funsd-test",
        ingested_at=datetime(
            2026,
            8,
            20,
            12,
            30,
            tzinfo=timezone.utc,
        ),
        scope_decision=ScopeDecision.INCLUDED,
        content_hash=record.record_id,
        schema_version="1.0",
        attachment_index=0,
        attachment_count=1,
        revision=0,
        change_type=ChangeType.UPSERT,
        change_significance_score=100,
        content_type=record.content_type,
        size_bytes=len(record.content),
        modality=record.modality,
    )


def test_dual_intake_produces_same_normalized_shape() -> None:
    require_real_funsd_slice()

    # Path A:
    # real FUNSD files -> CorpusLoader -> NormalizedRecord
    loader = CorpusLoader(root_dir=MIND_ROOT)

    loader_records = loader.load(MANIFEST_PATH)

    assert len(loader_records) == 2
    assert all(
        isinstance(record, NormalizedRecord)
        for record in loader_records
    )

    # Path B:
    # mock RawObjectEvent -> real FUNSD files -> handler
    object_paths: dict[str, Path] = {}
    bridge_events: list[RawObjectEvent] = []

    for record in loader_records:
        real_path = MIND_ROOT / record.source_ref
        file_name = real_path.name

        for delivery_number in (1, 2):
            object_key = (
                "SCANNED_DOC/"
                "funsd-test/"
                "2026-08-20/"
                f"{delivery_number}-{file_name}"
            )

            object_paths[object_key] = real_path

            bridge_events.append(
                make_bridge_event(
                    record=record,
                    object_key=object_key,
                )
            )

    assert len(bridge_events) == 4

    def read_object(object_key: str) -> bytes:
        return object_paths[object_key].read_bytes()

    bridge_records = [
        handle_raw_object_event(
            event=event,
            read_object=read_object,
        )
        for event in bridge_events
    ]

    assert len(bridge_records) == 4
    assert all(
        isinstance(record, NormalizedRecord)
        for record in bridge_records
    )

    # Both paths must expose the exact same NormalizedRecord fields.
    expected_shape = set(
        loader_records[0].model_dump().keys()
    )

    all_records = loader_records + bridge_records

    assert all(
        set(record.model_dump().keys()) == expected_shape
        for record in all_records
    )

    # For the same real content, the canonical normalized values
    # must match regardless of intake path.
    loader_by_record_id = {
        record.record_id: record
        for record in loader_records
    }

    for bridge_record in bridge_records:
        loader_record = loader_by_record_id[
            bridge_record.record_id
        ]

        assert bridge_record.content == loader_record.content
        assert (
            bridge_record.content_type
            == loader_record.content_type
        )
        assert bridge_record.modality == loader_record.modality
        assert (
            bridge_record.source_type
            == loader_record.source_type
        )

    # Simulate sending both paths sequentially
    # into the same downstream consumer.
    downstream_records: list[NormalizedRecord] = []

    downstream_records.extend(loader_records)
    downstream_records.extend(bridge_records)

    assert len(downstream_records) == 6
    assert len(loader_records) == 2
    assert len(bridge_records) == 4
    