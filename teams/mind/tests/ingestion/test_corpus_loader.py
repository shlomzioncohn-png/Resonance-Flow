import json
from pathlib import Path

from mind.contracts.base_enums import SourceType
from mind.contracts.raw_object_event_enums import Modality
from mind.ingestion.corpus_loader import CorpusLoader


def test_loads_image_as_normalized_record(tmp_path: Path) -> None:
    image_relative_path = Path(
        "local-data/funsd/dataset/testing_data/images/82092117.png"
    )
    annotation_relative_path = Path(
        "local-data/funsd/dataset/testing_data/annotations/82092117.json"
    )

    image_path = tmp_path / image_relative_path
    annotation_path = tmp_path / annotation_relative_path

    image_path.parent.mkdir(parents=True, exist_ok=True)
    annotation_path.parent.mkdir(parents=True, exist_ok=True)

    image_content = b"fake png bytes"
    image_path.write_bytes(image_content)
    annotation_path.write_text("{}", encoding="utf-8")

    manifest = {
        "dataset": "FUNSD",
        "split": "testing_data",
        "records": [
            {
                "id": "82092117",
                "files": [
                    image_relative_path.as_posix(),
                    annotation_relative_path.as_posix(),
                ],
            }
        ],
    }

    manifest_path = tmp_path / "funsd.manifest.json"
    manifest_path.write_text(
        json.dumps(manifest),
        encoding="utf-8",
    )

    loader = CorpusLoader(root_dir=tmp_path)

    records = loader.load(manifest_path)

    assert len(records) == 1

    record = records[0]

    assert record.source_ref == image_relative_path.as_posix()
    assert record.content == image_content
    assert record.content_type == "image/png"
    assert record.modality == Modality.SCANNED_DOC
    assert record.source_type == SourceType.SCANNED_DOC

    assert record.metadata["dataset"] == "FUNSD"
    assert record.metadata["split"] == "testing_data"
    assert record.metadata["dataset_record_id"] == "82092117"
    assert (
        record.metadata["annotation_ref"]
        == annotation_relative_path.as_posix()
    )


def test_ignores_annotation_as_separate_record(tmp_path: Path) -> None:
    image_relative_path = Path(
        "local-data/funsd/dataset/testing_data/images/82092117.png"
    )
    annotation_relative_path = Path(
        "local-data/funsd/dataset/testing_data/annotations/82092117.json"
    )

    image_path = tmp_path / image_relative_path
    annotation_path = tmp_path / annotation_relative_path

    image_path.parent.mkdir(parents=True, exist_ok=True)
    annotation_path.parent.mkdir(parents=True, exist_ok=True)

    image_path.write_bytes(b"fake png bytes")
    annotation_path.write_text("{}", encoding="utf-8")

    manifest = {
        "dataset": "FUNSD",
        "split": "testing_data",
        "records": [
            {
                "id": "82092117",
                "files": [
                    image_relative_path.as_posix(),
                    annotation_relative_path.as_posix(),
                ],
            }
        ],
    }

    manifest_path = tmp_path / "funsd.manifest.json"
    manifest_path.write_text(
        json.dumps(manifest),
        encoding="utf-8",
    )

    loader = CorpusLoader(root_dir=tmp_path)

    records = loader.load(manifest_path)

    assert len(records) == 1