"""The #359 demo feed builds what Bridge would publish for the same input."""

import base64
import json
import re
import uuid
from datetime import UTC, date, datetime, timedelta

import pytest
from demo_flow import common
from demo_flow.feed import (
    ORDERS,
    FeedItem,
    LandedObject,
    build_items,
    contract_problems,
    feed,
    main,
    mind_view,
)

from mind.messaging import raw_object_event_pb2 as pb

_ANCHOR = date(2026, 10, 19)  # a Monday, two weeks after the scenario's own
_SHIFT = timedelta(weeks=2)
_TENANT = str(common.DEFAULT_TENANT_ID)
_KEY = re.compile(rf"^[A-Z_]+/{_TENANT}/\d{{4}}-\d{{2}}-\d{{2}}/[^/]+$")
_UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"


def _build(
    order: str = "natural", tenant_id: uuid.UUID = common.DEFAULT_TENANT_ID
) -> dict[str, FeedItem]:
    email = common.read_json(common.EMAIL)
    items = build_items(
        order,
        _ANCHOR,
        meeting_audio=b"fake-m4a-bytes",
        meeting_metadata=common.read_json(common.MEETING_METADATA),
        slack_messages=common.read_json(common.SLACK_THREAD),
        email_record=email,
        attachments={a["filename"]: b"fake-jpeg-bytes" for a in email["attachments"]},
        tenant_id=tenant_id,
    )
    return {item.name: item for item in items}


def build_items_for(order: str) -> list[FeedItem]:
    return list(_build(order).values())


def _ts(event_field) -> datetime:
    return event_field.ToDatetime(UTC)


def test_orders_put_slack_before_or_after_the_meeting():
    assert [i.name for i in build_items_for("natural")] == ["meeting", "slack", "email"]
    assert [i.name for i in build_items_for("slack-first")] == ["slack", "meeting", "email"]


def test_meeting_is_what_the_zoom_media_lane_publishes():
    item = _build()["meeting"]
    meeting = item.event
    assert meeting.source_type == pb.SOURCE_TYPE_ZOOM
    recording, report = meeting.objects
    assert (recording.role, recording.modality) == (pb.ROLE_RECORDING, pb.MODALITY_AUDIO)
    assert recording.content_type == "audio/mp4"
    assert (report.role, report.modality) == (pb.ROLE_SOURCE_METADATA, pb.MODALITY_TEXT)
    assert report.content_type == "application/json"
    # MediaIntakeService: every object of a meeting is named after its correlation id.
    prefix = f"ZOOM/{_TENANT}/2026-10-19/{meeting.correlation_id}"
    assert (recording.object_key, report.object_key) == (f"{prefix}.m4a", f"{prefix}.json")
    assert _ts(meeting.ingested_at) == common.MEETING_INGESTED_AT + _SHIFT


def test_meeting_report_fills_participants_start_and_source_ref():
    item = _build()["meeting"]
    meeting = item.event
    landed = json.loads(item.objects[1].content)
    assert list(meeting.participants) == ["Lior", "Dana", "Omer"]
    assert _ts(meeting.source_timestamp) == datetime(2026, 10, 19, 9, 0, tzinfo=UTC)
    assert meeting.source_ref == landed["uuid"]
    assert landed["start_time"] == "2026-10-19T09:00:00Z"
    assert [p["join_time"] for p in landed["participants"]] == [
        "2026-10-19T09:00:00Z",
        "2026-10-19T09:00:00Z",
        "2026-10-19T09:01:00Z",
    ]
    original = common.read_json(common.MEETING_METADATA)
    assert landed["uuid"] != original["uuid"]  # each run is a new meeting
    assert set(landed) == set(original)  # Zoom's own field names, nothing added


def test_slack_is_one_transcript_of_sender_lines():
    item = _build()["slack"]
    [obj] = item.objects
    assert obj.content.decode() == (
        "Lior: Dana, did Northwind confirm the unit count for the quote?\n"
        "Dana: Yes, 300 sensor units with installation. I'll get our pricing proposal over to "
        "Northwind before end of day Thursday.\n"
        "Lior: Thanks, sounds good."
    )
    [ref] = item.event.objects
    assert (ref.role, ref.modality) == (pb.ROLE_TRANSCRIPT, pb.MODALITY_TEXT)
    assert ref.content_type == "text/plain; charset=utf-8"
    assert re.fullmatch(rf"SLACK/{_TENANT}/2026-10-19/{_UUID}\.txt", ref.object_key)
    assert list(item.event.participants) == ["Lior", "Dana"]
    assert _ts(item.event.source_timestamp) == datetime(2026, 10, 19, 9, 40, tzinfo=UTC)


def test_email_body_is_the_fixture_record_with_its_attachment_inline():
    item = _build()["email"]
    body, attachment = item.objects
    landed = json.loads(body.content)
    assert landed["date"] == "2026-10-19T11:15:00Z"
    assert next(iter(landed)) == "tenantId"  # first, as in Bridge's fixture records
    assert landed["tenantId"] == _TENANT
    assert landed["attachments"] == [
        {
            "filename": "supplier_form.jpg",
            "mimeType": "image/jpeg",
            "content": base64.b64encode(b"fake-jpeg-bytes").decode(),
        }
    ]
    assert b": " not in body.content  # compact, like JsonNode.toString()
    assert (body.role, body.modality) == (pb.ROLE_BODY, pb.MODALITY_TEXT)
    assert body.content_type == "application/json"
    assert (attachment.role, attachment.modality) == (pb.ROLE_ATTACHMENT, pb.MODALITY_SCANNED_DOC)
    assert attachment.content_type == "image/jpeg"
    assert attachment.content == b"fake-jpeg-bytes"
    assert re.fullmatch(rf"EMAIL/{_TENANT}/2026-10-19/{_UUID}-supplier_form\.jpg", attachment.key)
    assert list(item.event.participants) == ["lisa.grant@northwind.example"]
    assert _ts(item.event.source_timestamp) == datetime(2026, 10, 19, 11, 15, tzinfo=UTC)


@pytest.mark.parametrize("name", ["meeting", "slack", "email"])
def test_every_event_carries_bridges_constants_and_matches_its_objects(name):
    item = _build()[name]
    event = item.event
    assert event.schema_version == "1.0"
    assert (event.revision, event.change_type, event.change_significance_score) == (
        0,
        pb.CHANGE_TYPE_UPSERT,
        100,
    )
    assert event.scope_decision == pb.SCOPE_DECISION_INCLUDED
    assert not event.HasField("classification")  # Bridge's classifier is off by default
    assert item.kafka_key == f"{event.correlation_id}:0"
    for ref, obj in zip(event.objects, item.objects, strict=True):
        assert _KEY.fullmatch(ref.object_key)
        assert ref.object_key == obj.key
        assert ref.content_hash == obj.content_hash
        assert ref.size_bytes == len(obj.content)
        # ObjectTags use Bridge's own enum name, without the proto's prefix.
        source_type = pb.SourceType.Name(event.source_type).removeprefix("SOURCE_TYPE_")
        assert obj.tags["source_type"] == source_type
        assert ref.object_key.startswith(f"{source_type}/")
        assert obj.tags["ingested_at"] == _ts(event.ingested_at).strftime("%Y-%m-%dT%H:%M:%SZ")


def test_each_run_gets_fresh_correlation_ids_and_object_keys():
    first, second = _build(), _build()
    for name in ORDERS["natural"]:
        assert first[name].event.correlation_id != second[name].event.correlation_id
        assert first[name].objects[0].key != second[name].objects[0].key


@pytest.mark.parametrize("name", ["meeting", "slack", "email"])
def test_every_event_passes_minds_contract_once_enum_prefixes_are_stripped(name):
    assert contract_problems(_build()[name]) == []


def test_the_tenant_comes_from_one_place_and_reaches_every_event_object_and_tag():
    tenant = uuid.UUID("3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90")
    for item in _build(tenant_id=tenant).values():
        assert item.event.tenant_id == str(tenant)
        for obj in item.objects:
            assert obj.key.split("/")[1] == str(tenant)
            assert obj.tags["tenant_id"] == str(tenant)


def test_a_tenant_that_is_not_a_uuid_is_reported_as_a_contract_problem():
    # Mind's consumer skips such an event, so the dry run must say so.
    item = _build()["slack"]
    item.event.tenant_id = "demo-tenant"
    assert contract_problems(item) == ["RawObjectEvent.tenant_id is not a UUID: 'demo-tenant'"]


def test_feed_refuses_a_tenant_id_that_is_not_a_uuid(monkeypatch, capsys):
    with pytest.raises(SystemExit):
        main(["--dry-run", "--tenant-id", "demo-tenant"])
    assert "--tenant-id" in capsys.readouterr().err
    monkeypatch.setenv("DEMO_TENANT_ID", "demo-tenant")
    with pytest.raises(SystemExit):
        main(["--dry-run"])


def test_mind_view_strips_the_proto_enum_prefixes():
    view = mind_view(_build()["meeting"].event)
    assert (view["source_type"], view["scope_decision"], view["change_type"]) == (
        "ZOOM",
        "INCLUDED",
        "UPSERT",
    )
    assert [(o["role"], o["modality"]) for o in view["objects"]] == [
        ("RECORDING", "AUDIO"),
        ("SOURCE_METADATA", "TEXT"),
    ]


def test_anchor_must_be_a_monday():
    with pytest.raises(ValueError, match="Monday"):
        common.week_shift(date(2026, 10, 20))
    assert common.week_shift(_ANCHOR) == _SHIFT
    assert common.most_recent_monday(date(2026, 10, 18)) == date(2026, 10, 12)
    assert common.weekday_in_week(_ANCHOR, "Thursday") == date(2026, 10, 22)


def test_feed_lands_every_object_before_publishing_and_waits_between_items():
    calls: list[str] = []
    sleeps: list[float] = []

    def land(obj: LandedObject) -> None:
        calls.append(f"land {obj.key.split('/')[0]}")

    def publish(item: FeedItem) -> None:
        calls.append(f"publish {item.name}")

    sent = feed(build_items_for("slack-first"), land, publish, gap_s=5, sleep=sleeps.append)

    assert calls == [
        "land SLACK",
        "publish slack",
        "land ZOOM",
        "land ZOOM",
        "publish meeting",
        "land EMAIL",
        "land EMAIL",
        "publish email",
    ]
    assert sleeps == [5, 5]
    assert [s["name"] for s in sent] == ["slack", "meeting", "email"]


def test_scenario_files_agree_with_the_expected_answer():
    expected = common.read_json(common.EXPECTED)
    script = " ".join(text for _, text in common.read_meeting_script())
    slack = " ".join(m["text"] for m in common.read_json(common.SLACK_THREAD))
    email = common.read_json(common.EMAIL)["body"].replace("\n", " ")
    form = " ".join(common.FORM_TEXT.read_text(encoding="utf-8").split())
    sources = {"meeting": script, "slack": slack, "email": email, "email_attachment": form}
    for task in expected["tasks"]:
        for mention in task["mentions"]:
            assert mention["text"] in sources[mention["source"]], (task["id"], mention)
    email_record = common.read_json(common.EMAIL)
    assert [a["filename"] for a in email_record["attachments"]] == [common.FORM_IMAGE.name]
