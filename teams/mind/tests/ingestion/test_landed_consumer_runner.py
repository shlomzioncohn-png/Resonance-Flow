import logging
from pathlib import Path
from uuid import UUID

import pytest

from mind.config import LandedConsumerSettings
from mind.contracts import RawObjectEvent
from mind.contracts.raw_object_event_enums import Modality, ObjectRole, RawSourceType
from mind.ingestion.landed_consumer_runner import _log_texts
from mind.ingestion.normalized_record import NormalizedRecord

ITEM = UUID("6f1c1f9e-0000-4000-8000-000000000001")
SECRET = "Dana's salary goes up to 31,000 in March"


def test_the_stand_in_hand_off_logs_where_the_text_is_never_what_it_says(
    caplog: pytest.LogCaptureFixture,
) -> None:
    # Bridge may have classified the item HIGH sensitivity; logs travel further
    # than the content was meant to.
    caplog.set_level(logging.INFO)
    record = NormalizedRecord(
        object_ref="minio://EMAIL/t/2026-10-19/body.eml",
        content=SECRET.encode(),
        content_hash="sha256:" + "a" * 64,
        content_type=None,
        modality=Modality.TEXT,
        role=ObjectRole.BODY,
        source_type=RawSourceType.EMAIL,
        tenant_id=UUID("00000000-0000-0000-0000-000000000001"),
        source_ref=None,
        source_timestamp=None,
        participants=None,
        correlation_id=ITEM,
        revision=0,
    )
    event = RawObjectEvent.model_validate(
        {
            "schema_version": "1.0",
            "source_type": "EMAIL",
            "tenant_id": "00000000-0000-0000-0000-000000000001",
            "correlation_id": str(ITEM),
            "ingested_at": "2026-10-19T09:00:00Z",
            "scope_decision": "INCLUDED",
            "revision": 0,
            "change_type": "UPSERT",
            "change_significance_score": 80,
            "objects": [
                {
                    "role": "BODY",
                    "object_key": "EMAIL/t/2026-10-19/body.eml",
                    "content_hash": "sha256:" + "a" * 64,
                    "modality": "TEXT",
                }
            ],
        }
    )

    _log_texts(event, [(record, SECRET)])

    assert "body.eml" in caplog.text
    assert f"{len(SECRET)} chars" in caplog.text
    assert "salary" not in caplog.text


def test_the_consumer_group_is_its_own_and_can_be_overridden(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # Until extraction is wired, a run commits offsets with nothing extracted:
    # a test run against the shared topic takes a group of its own.
    monkeypatch.delenv("MIND_LANDED_CONSUMER__GROUP_ID", raising=False)
    no_dotenv = tmp_path / "absent.env"
    assert LandedConsumerSettings(_env_file=no_dotenv).group_id == "mind-landed-consumer"

    monkeypatch.setenv("MIND_LANDED_CONSUMER__GROUP_ID", "mind-landed-joint-test")
    assert LandedConsumerSettings(_env_file=no_dotenv).group_id == "mind-landed-joint-test"


def test_the_runner_settings_read_dotenv_like_the_rest_and_need_no_database(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # Set beside the MinIO keys in .env, the override must not be ignored; and
    # the runner must start without the vector-store DSN it never uses.
    for name in ("MIND_LANDED_CONSUMER__GROUP_ID", "MIND_LOG_LEVEL", "MIND_VECTOR_STORE__DSN"):
        monkeypatch.delenv(name, raising=False)
    dotenv = tmp_path / ".env"
    dotenv.write_text("MIND_LANDED_CONSUMER__GROUP_ID=from-dotenv\nMIND_LOG_LEVEL=DEBUG\n")

    settings = LandedConsumerSettings(_env_file=dotenv)

    assert (settings.group_id, settings.log_level) == ("from-dotenv", "DEBUG")
