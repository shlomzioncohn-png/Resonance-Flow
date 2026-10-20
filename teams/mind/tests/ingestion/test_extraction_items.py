import hashlib
import logging
from pathlib import PurePosixPath
from typing import Any

import pytest

from mind.contracts import RawObjectEvent
from mind.contracts.base_enums import SourceType
from mind.contracts.raw_object_event_enums import RawSourceType
from mind.extraction.extractor import ExtractionItem
from mind.ingestion.extraction_items import extraction_items, task_source_type
from mind.ingestion.raw_object_event_handler import (
    InvalidEvent,
    ObjectNotFound,
    ObjectTooLarge,
)
from mind.multimodal.asr import ASRResult
from mind.multimodal.ocr import OCRResult


@pytest.mark.parametrize("meeting", [RawSourceType.ZOOM, RawSourceType.BIGBLUEBUTTON])
def test_a_meeting_platform_becomes_a_meeting(meeting: RawSourceType) -> None:
    # The task side has no ZOOM / BIGBLUEBUTTON until Inbox's values reach shared/proto.
    assert task_source_type(meeting) is SourceType.MEETING


@pytest.mark.parametrize(
    "raw",
    [
        RawSourceType.EMAIL,
        RawSourceType.SLACK,
        RawSourceType.WHATSAPP,
        RawSourceType.DRIVE,
        RawSourceType.CALENDAR,
        RawSourceType.SCANNED_DOC,
    ],
)
def test_every_other_source_keeps_its_name(raw: RawSourceType) -> None:
    assert task_source_type(raw).value == raw.value


def test_every_bridge_source_type_has_a_task_source_type() -> None:
    # A new value on Bridge's side must be mapped on purpose, not fail at run time.
    for raw in RawSourceType:
        assert isinstance(task_source_type(raw), SourceType)


# --- one pass, one file at a time ---------------------------------------------

TENANT = "550e8400-e29b-41d4-a716-446655440000"
RECORDING_KEY = f"ZOOM/{TENANT}/2026-10-20/meeting.m4a"
TRANSCRIPT_KEY = f"ZOOM/{TENANT}/2026-10-20/meeting.vtt"
SCAN_KEY = f"ZOOM/{TENANT}/2026-10-20/quote.pdf"
SCAN_2_KEY = f"ZOOM/{TENANT}/2026-10-20/form.jpg"
META_KEY = f"ZOOM/{TENANT}/2026-10-20/meeting.json"
VOICE_NOTE_KEY = f"ZOOM/{TENANT}/2026-10-20/note.ogg"
BODY_KEY = f"ZOOM/{TENANT}/2026-10-20/body.txt"
STORE = {
    RECORDING_KEY: b"m4a-bytes",
    # A real VTT: the header on its own line, then cues of number, timing, speech.
    TRANSCRIPT_KEY: b"WEBVTT\n\n1\n00:00:01.000 --> 00:00:03.000\nwe will send the quote\n",
    SCAN_KEY: b"%PDF-1.7",
    SCAN_2_KEY: b"\xff\xd8jpeg",
    META_KEY: b"{}",
    VOICE_NOTE_KEY: b"OggS",
    BODY_KEY: b"  \n",
}
MODALITY = {RECORDING_KEY: "AUDIO", TRANSCRIPT_KEY: "TEXT", SCAN_KEY: "SCANNED_DOC"}
MODALITY |= {SCAN_2_KEY: "SCANNED_DOC", META_KEY: "TEXT", VOICE_NOTE_KEY: "AUDIO"}
MODALITY[BODY_KEY] = "TEXT"


class _Trace:
    """Reader and OCR/ASR fakes that log, in order, what was done to which file."""

    def __init__(self, store: dict[str, bytes]) -> None:
        self.store, self.calls = store, []

    def read(self, key: str) -> bytes:
        self.calls.append(("read", PurePosixPath(key).name))
        if key not in self.store:
            raise ObjectNotFound(key)
        return self.store[key]

    def ocr(self, content: bytes, *, filename: str = "") -> OCRResult:
        self.calls.append(("ocr", filename))
        return OCRResult(success=True, text=f"text of {filename}")

    def asr(self, content: bytes, *, filename: str = "") -> ASRResult:
        self.calls.append(("asr", filename))
        return ASRResult(success=True, text=f"speech of {filename}")


def _ref(role: str, key: str) -> dict[str, Any]:
    digest = hashlib.sha256(STORE[key]).hexdigest()
    return {
        "role": role,
        "object_key": key,
        "content_hash": f"sha256:{digest}",
        "modality": MODALITY[key],
    }


def _landed(*objects: dict[str, Any], **overrides: Any) -> RawObjectEvent:
    payload = {
        "schema_version": "1.0",
        "source_type": "ZOOM",
        "tenant_id": TENANT,
        "correlation_id": "6f1c1f9e-0000-4000-8000-000000000001",
        "ingested_at": "2026-10-20T09:00:00Z",
        "scope_decision": "INCLUDED",
        "revision": 0,
        "change_type": "UPSERT",
        "change_significance_score": 80,
        "objects": list(objects),
    }
    return RawObjectEvent.model_validate(payload | overrides)


def _items(event: RawObjectEvent, trace: _Trace) -> list[ExtractionItem]:
    return extraction_items(event, trace.read, ocr=trace.ocr, asr=trace.asr)


def test_each_file_becomes_an_item_with_the_task_source_type_and_its_key() -> None:
    (item,) = _items(_landed(_ref("ATTACHMENT", SCAN_KEY)), _Trace(STORE))

    assert item == ExtractionItem(
        text="text of quote.pdf", source_type=SourceType.MEETING, item_key=SCAN_KEY
    )


def test_one_file_is_read_and_converted_before_the_next_is_read() -> None:
    trace = _Trace(STORE)

    _items(_landed(_ref("ATTACHMENT", SCAN_KEY), _ref("ATTACHMENT", SCAN_2_KEY)), trace)

    assert trace.calls == [
        ("read", "quote.pdf"),
        ("ocr", "quote.pdf"),
        ("read", "form.jpg"),
        ("ocr", "form.jpg"),
    ]


def test_a_readable_transcript_means_the_recording_is_never_read() -> None:
    # The recording comes first in the event; the transcript is still read first.
    trace = _Trace(STORE)

    items = _items(
        _landed(_ref("RECORDING", RECORDING_KEY), _ref("TRANSCRIPT", TRANSCRIPT_KEY)), trace
    )

    assert [i.text for i in items] == [STORE[TRANSCRIPT_KEY].decode()]
    assert ("read", "meeting.m4a") not in trace.calls


def test_a_missing_transcript_leaves_the_recording_to_be_transcribed() -> None:
    trace = _Trace({k: v for k, v in STORE.items() if k != TRANSCRIPT_KEY})

    items = _items(
        _landed(_ref("RECORDING", RECORDING_KEY), _ref("TRANSCRIPT", TRANSCRIPT_KEY)), trace
    )

    assert [i.text for i in items] == ["speech of meeting.m4a"]


def test_an_excluded_item_reads_nothing() -> None:
    trace = _Trace(STORE)

    assert _items(_landed(_ref("BODY", TRANSCRIPT_KEY), scope_decision="EXCLUDED"), trace) == []
    assert trace.calls == []


def test_a_file_that_does_not_match_its_hash_is_skipped_and_the_rest_kept() -> None:
    trace = _Trace(STORE | {SCAN_KEY: b"something else"})

    items = _items(_landed(_ref("ATTACHMENT", SCAN_KEY), _ref("ATTACHMENT", SCAN_2_KEY)), trace)

    assert [i.item_key for i in items] == [SCAN_2_KEY]


def test_a_tenant_id_that_is_not_a_uuid_is_invalid() -> None:
    with pytest.raises(InvalidEvent):
        _items(_landed(_ref("BODY", TRANSCRIPT_KEY), tenant_id="kamatech"), _Trace(STORE))


def test_a_transcript_with_no_speech_in_it_leaves_the_recording_to_be_transcribed() -> None:
    # Zoom can deliver a WEBVTT file with cue numbers and timings but no words.
    empty_vtt = b"WEBVTT\n\n1\n00:00:01.000 --> 00:00:04.000\n\n"
    store = STORE | {TRANSCRIPT_KEY: empty_vtt}
    transcript = _ref("TRANSCRIPT", TRANSCRIPT_KEY) | {
        "content_hash": f"sha256:{hashlib.sha256(empty_vtt).hexdigest()}"
    }

    items = _items(_landed(_ref("RECORDING", RECORDING_KEY), transcript), _Trace(store))

    assert [i.text for i in items] == ["speech of meeting.m4a"]


def test_source_metadata_describes_the_item_and_is_not_read() -> None:
    trace = _Trace(STORE)

    items = _items(_landed(_ref("SOURCE_METADATA", META_KEY), _ref("ATTACHMENT", SCAN_KEY)), trace)

    assert [i.item_key for i in items] == [SCAN_KEY]
    assert ("read", "meeting.json") not in trace.calls


def test_a_delete_review_reads_nothing() -> None:
    trace = _Trace(STORE)

    assert _items(_landed(change_type="DELETE_REVIEW"), trace) == []
    assert trace.calls == []


def test_a_missing_file_is_skipped_and_the_rest_kept(caplog: pytest.LogCaptureFixture) -> None:
    # The missing one first, so skipping it must go on rather than stop.
    trace = _Trace({k: v for k, v in STORE.items() if k != SCAN_KEY})

    items = _items(_landed(_ref("ATTACHMENT", SCAN_KEY), _ref("ATTACHMENT", SCAN_2_KEY)), trace)

    assert [i.item_key for i in items] == [SCAN_2_KEY]
    assert SCAN_KEY in caplog.text


def test_a_file_too_large_to_read_is_skipped_loudly(caplog: pytest.LogCaptureFixture) -> None:
    trace = _Trace(STORE)

    def scan_too_large(key: str) -> bytes:
        if key == SCAN_KEY:
            raise ObjectTooLarge(key)
        return trace.read(key)

    items = extraction_items(
        _landed(_ref("ATTACHMENT", SCAN_KEY), _ref("ATTACHMENT", SCAN_2_KEY)),
        scan_too_large,
        ocr=trace.ocr,
        asr=trace.asr,
    )

    assert [i.item_key for i in items] == [SCAN_2_KEY]
    assert any(r.levelno == logging.ERROR and SCAN_KEY in r.getMessage() for r in caplog.records)


def test_a_storage_outage_is_not_mistaken_for_a_missing_file() -> None:
    def storage_down(key: str) -> bytes:
        raise ConnectionError("MinIO unreachable")

    with pytest.raises(ConnectionError):
        extraction_items(_landed(_ref("ATTACHMENT", SCAN_KEY)), storage_down)


def test_a_transcript_replaces_only_the_recording_not_other_audio() -> None:
    trace = _Trace(STORE)

    items = _items(
        _landed(
            _ref("RECORDING", RECORDING_KEY),
            _ref("TRANSCRIPT", TRANSCRIPT_KEY),
            _ref("ATTACHMENT", VOICE_NOTE_KEY),
        ),
        trace,
    )

    assert [i.item_key for i in items] == [TRANSCRIPT_KEY, VOICE_NOTE_KEY]


def test_a_file_with_no_text_in_it_is_skipped() -> None:
    items = _items(_landed(_ref("BODY", BODY_KEY), _ref("ATTACHMENT", SCAN_KEY)), _Trace(STORE))

    assert [i.item_key for i in items] == [SCAN_KEY]


def _transcript_of(content: bytes, key: str = TRANSCRIPT_KEY) -> dict[str, Any]:
    return {
        "role": "TRANSCRIPT",
        "object_key": key,
        "content_hash": f"sha256:{hashlib.sha256(content).hexdigest()}",
        "modality": "TEXT",
    }


def test_a_chat_transcript_is_not_held_to_the_webvtt_rules() -> None:
    # Bridge gives a Slack or WhatsApp session's text the TRANSCRIPT role; a line
    # starting with NOTE, or a reply that is just a number, is a message there.
    chat = b"NOTE: send Dana the updated quote by Thursday\n2\n"
    key = f"SLACK/{TENANT}/2026-10-20/thread.txt"

    (item,) = _items(_landed(_transcript_of(chat, key), source_type="SLACK"), _Trace({key: chat}))

    assert item.text == chat.decode()


def test_a_webvtt_with_named_cues_and_no_speech_leaves_the_recording_to_be_transcribed() -> None:
    # Cue identifiers may be any text, not only numbers; they are still not speech.
    named_cues = b"\xef\xbb\xbfWEBVTT\n\nintro\n00:00:01.000 --> 00:00:04.000\n\n"
    store = STORE | {TRANSCRIPT_KEY: named_cues}

    items = _items(
        _landed(_ref("RECORDING", RECORDING_KEY), _transcript_of(named_cues)), _Trace(store)
    )

    assert [i.text for i in items] == ["speech of meeting.m4a"]
