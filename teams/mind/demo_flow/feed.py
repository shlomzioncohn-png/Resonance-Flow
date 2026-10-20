"""Feed the #359 demo scenario into the pipeline the way Bridge does.

    python -m demo_flow.feed                       # natural order, this week
    python -m demo_flow.feed --order slack-first   # Slack before the meeting
    python -m demo_flow.feed --dry-run             # build + validate, no infra

For each item (meeting, Slack thread, email with its scanned attachment) it
lands the item's objects in MinIO, then publishes one RawObjectEvent pointing
at them to `bridge.raw.landed` -- the real path the consumer reads, not a
function call. What it lands and publishes is what Bridge's own lanes produce
for the same input (see README.md, "What the feed sends"), so the consumer
cannot tell the two apart:

- meeting: the media lane's Zoom intake (MediaIntakeService.landMeeting) -- a
  RECORDING object plus the Zoom meeting report as SOURCE_METADATA, which
  gives participants, source_timestamp and source_ref; no transcript sidecar
  (so ASR runs);
- Slack: the chat lane (ChatSourceRecordMapper) -- one TRANSCRIPT object,
  `sender: text` per line;
- email: the fixture email lane (FixtureEmailSourceReader) -- the record as
  compact JSON for BODY, plus one ATTACHMENT object per attachment.

Every run mints fresh correlation ids and object keys, so its events never
collide with an earlier run's (the event dedup key is `correlation_id:revision`).
That holds for Kafka and MinIO only: the pipeline's duplicate detection matches
a rerun's tasks against the first run's stored tasks, so a full-pipeline rerun
needs a clean store (README.md, "Repeatability").
"""

from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import io
import json
import logging
import os
import sys
import time
import uuid
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from typing import Any

from google.protobuf.json_format import MessageToDict
from pydantic import ValidationError

from demo_flow import common
from mind.contracts import RawObjectEvent as RawObjectEventContract
from mind.messaging import raw_object_event_pb2 as pb

logger = logging.getLogger("demo_flow.feed")

# What Bridge stamps on every event today (raw/model/RawObjectEvent.java).
SCHEMA_VERSION = "1.0"
CHANGE_SIGNIFICANCE_SCORE = 100
REVISION = 0

# Bridge's content types for these inputs (MediaIntakeService.guessContentType,
# IntakeLandingService.resolveContentType).
MEETING_CONTENT_TYPE = "audio/mp4"  # .m4a
MEETING_METADATA_CONTENT_TYPE = "application/json"  # Zoom's .meeting.json
CHAT_CONTENT_TYPE = "text/plain; charset=utf-8"
EMAIL_BODY_CONTENT_TYPE = "application/json"

ORDERS: dict[str, tuple[str, ...]] = {
    "natural": ("meeting", "slack", "email"),
    "slack-first": ("slack", "meeting", "email"),
}
# Seconds between two items. "natural" leaves the meeting time to be transcribed
# before the Slack message arrives (~70 s of audio; ASR is ~0.1-0.3x real time);
# "slack-first" sends them back to back.
DEFAULT_GAP_S = {"natural": 90.0, "slack-first": 5.0}


@dataclass(frozen=True)
class LandedObject:
    key: str
    content: bytes
    content_type: str
    role: pb.Role
    modality: pb.Modality
    tags: dict[str, str]

    @property
    def content_hash(self) -> str:
        return "sha256:" + hashlib.sha256(self.content).hexdigest()


@dataclass(frozen=True)
class FeedItem:
    name: str
    objects: list[LandedObject]
    event: pb.RawObjectEvent

    @property
    def kafka_key(self) -> str:
        # Bridge's dedupKey(): correlationId + ":" + revision.
        return f"{self.event.correlation_id}:{self.event.revision}"


# ---------------------------------------------------------------- building


def _object_key(source_type: str, tenant_id: str, ingested_at: datetime, filename: str) -> str:
    # ObjectKeyBuilder / MediaIntakeService.buildObjectKey: the date is the landing date.
    return f"{source_type}/{tenant_id}/{ingested_at.astimezone(UTC):%Y-%m-%d}/{filename}"


def _instant(value: datetime) -> str:
    """java.time.Instant.toString() for a whole-second UTC time: 2026-10-05T09:35:00Z."""
    return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _tags(source_type: str, tenant_id: str, ingested_at: datetime) -> dict[str, str]:
    # storage/ObjectTags.of(...)
    return {
        "source_type": source_type,
        "tenant_id": tenant_id,
        "ingested_at": _instant(ingested_at),
        "scope_decision": "INCLUDED",
    }


def _shift_instant(value: str, shift: timedelta) -> str:
    return _instant(datetime.fromisoformat(value) + shift)


def _event(
    source_type: pb.SourceType,
    tenant_id: str,
    ingested_at: datetime,
    objects: list[LandedObject],
    participants: list[str],
    source_timestamp: datetime | None,
    *,
    correlation_id: str | None = None,
    source_ref: str | None = None,
) -> pb.RawObjectEvent:
    event = pb.RawObjectEvent(
        schema_version=SCHEMA_VERSION,
        source_type=source_type,
        tenant_id=tenant_id,
        scope_decision=pb.SCOPE_DECISION_INCLUDED,
        participants=participants,
        correlation_id=correlation_id or str(uuid.uuid4()),
        revision=REVISION,
        change_type=pb.CHANGE_TYPE_UPSERT,
        change_significance_score=CHANGE_SIGNIFICANCE_SCORE,
        objects=[
            pb.RawObjectRef(
                role=obj.role,
                object_key=obj.key,
                content_hash=obj.content_hash,
                content_type=obj.content_type,
                size_bytes=len(obj.content),
                modality=obj.modality,
            )
            for obj in objects
        ],
    )
    event.ingested_at.FromDatetime(ingested_at)
    if source_timestamp is not None:
        event.source_timestamp.FromDatetime(source_timestamp)
    if source_ref is not None:
        event.source_ref = source_ref
    return event


def build_meeting(
    audio: bytes, metadata: dict[str, Any], tenant_id: str, shift: timedelta
) -> FeedItem:
    ingested_at = common.MEETING_INGESTED_AT + shift
    # MediaIntakeService names every object of a meeting after its correlation id.
    correlation_id = str(uuid.uuid4())

    # Each run is a new meeting: its own Zoom uuid, and the report's times moved
    # with the rest of the timeline.
    report = copy.deepcopy(metadata)
    report["uuid"] = base64.b64encode(uuid.uuid4().bytes).decode("ascii")
    for field in ("start_time", "end_time"):
        report[field] = _shift_instant(report[field], shift)
    for participant in report["participants"]:
        for field in ("join_time", "leave_time"):
            participant[field] = _shift_instant(participant[field], shift)
    report_bytes = (json.dumps(report, indent=2, ensure_ascii=False) + "\n").encode("utf-8")

    tags = _tags("ZOOM", tenant_id, ingested_at)
    objects = [
        LandedObject(
            key=_object_key("ZOOM", tenant_id, ingested_at, f"{correlation_id}.m4a"),
            content=audio,
            content_type=MEETING_CONTENT_TYPE,
            role=pb.ROLE_RECORDING,
            modality=pb.MODALITY_AUDIO,
            tags=tags,
        ),
        LandedObject(
            key=_object_key("ZOOM", tenant_id, ingested_at, f"{correlation_id}.json"),
            content=report_bytes,
            content_type=MEETING_METADATA_CONTENT_TYPE,
            role=pb.ROLE_SOURCE_METADATA,
            modality=pb.MODALITY_TEXT,
            tags=tags,
        ),
    ]
    # MediaIntakeOrchestrator: participants are the report's names, the meeting's
    # start is source_timestamp, the Zoom uuid is source_ref.
    event = _event(
        pb.SOURCE_TYPE_ZOOM,
        tenant_id,
        ingested_at,
        objects,
        [p["name"] for p in report["participants"]],
        datetime.fromisoformat(report["start_time"]),
        correlation_id=correlation_id,
        source_ref=report["uuid"],
    )
    return FeedItem("meeting", objects, event)


def build_slack(messages: list[dict[str, Any]], shift: timedelta) -> FeedItem:
    tenant_id = messages[0]["tenantId"]
    ingested_at = common.SLACK_INGESTED_AT + shift
    # ChatSourceRecordMapper: one "sender: text" line per message, senders in
    # order of first appearance as participants, the first message's time.
    transcript = "\n".join(f"{m['user']}: {m['text']}" for m in messages)
    participants = list(dict.fromkeys(m["user"] for m in messages))
    first_sent = datetime.fromtimestamp(round(float(messages[0]["ts"]) * 1000) / 1000, UTC)
    obj = LandedObject(
        key=_object_key("SLACK", tenant_id, ingested_at, f"{uuid.uuid4()}.txt"),
        content=transcript.encode("utf-8"),
        content_type=CHAT_CONTENT_TYPE,
        role=pb.ROLE_TRANSCRIPT,
        modality=pb.MODALITY_TEXT,
        tags=_tags("SLACK", tenant_id, ingested_at),
    )
    event = _event(
        pb.SOURCE_TYPE_SLACK, tenant_id, ingested_at, [obj], participants, first_sent + shift
    )
    return FeedItem("slack", [obj], event)


def _attachment_modality(mime_type: str | None) -> pb.Modality:
    # SourceRecordRefMapper.deriveModality
    if mime_type is None:
        return pb.MODALITY_TEXT
    if mime_type.startswith("audio/"):
        return pb.MODALITY_AUDIO
    if mime_type.startswith("image/") or mime_type == "application/pdf":
        return pb.MODALITY_SCANNED_DOC
    return pb.MODALITY_TEXT


def build_email(
    record: dict[str, Any], attachments: dict[str, bytes], shift: timedelta
) -> FeedItem:
    tenant_id = record["tenantId"]
    ingested_at = common.EMAIL_INGESTED_AT + shift
    sent_at = datetime.fromisoformat(record["date"]) + shift

    # The fixture record Bridge parses, with the run's date and each
    # attachment's bytes inline as base64 (gmail-sample.json's shape).
    landed_record = copy.deepcopy(record)
    landed_record["date"] = _instant(sent_at)
    for attachment in landed_record.get("attachments", []):
        content = attachments[attachment["filename"]]
        attachment["content"] = base64.b64encode(content).decode("ascii")
    # FixtureEmailSourceReader lands JsonNode.toString(): compact, field order kept.
    body = json.dumps(landed_record, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    tags = _tags("EMAIL", tenant_id, ingested_at)
    objects = [
        LandedObject(
            key=_object_key("EMAIL", tenant_id, ingested_at, f"{uuid.uuid4()}.json"),
            content=body,
            content_type=EMAIL_BODY_CONTENT_TYPE,
            role=pb.ROLE_BODY,
            modality=pb.MODALITY_TEXT,
            tags=tags,
        )
    ]
    for attachment in landed_record.get("attachments", []):
        filename = attachment["filename"]
        mime_type = attachment.get("mimeType")
        objects.append(
            LandedObject(
                key=_object_key("EMAIL", tenant_id, ingested_at, f"{uuid.uuid4()}-{filename}"),
                content=attachments[filename],
                content_type=mime_type or "application/octet-stream",
                role=pb.ROLE_ATTACHMENT,
                modality=_attachment_modality(mime_type),
                tags=tags,
            )
        )
    event = _event(pb.SOURCE_TYPE_EMAIL, tenant_id, ingested_at, objects, [record["from"]], sent_at)
    return FeedItem("email", objects, event)


def build_items(
    order: str,
    anchor: date,
    *,
    meeting_audio: bytes,
    meeting_metadata: dict[str, Any],
    slack_messages: list[dict[str, Any]],
    email_record: dict[str, Any],
    attachments: dict[str, bytes],
) -> list[FeedItem]:
    shift = common.week_shift(anchor)
    tenant_id = email_record["tenantId"]
    builders: dict[str, Callable[[], FeedItem]] = {
        "meeting": lambda: build_meeting(meeting_audio, meeting_metadata, tenant_id, shift),
        "slack": lambda: build_slack(slack_messages, shift),
        "email": lambda: build_email(email_record, attachments, shift),
    }
    return [builders[name]() for name in ORDERS[order]]


def load_items(order: str, anchor: date) -> list[FeedItem]:
    """build_items from the files under scenario/ and artifacts/."""
    missing = [p for p in (common.MEETING_AUDIO, common.FORM_IMAGE) if not p.exists()]
    if missing:
        names = ", ".join(str(p) for p in missing)
        raise SystemExit(f"missing {names} -- run `python -m demo_flow.build_artifacts` first")
    email_record = common.read_json(common.EMAIL)
    attachments = {
        a["filename"]: (common.ARTIFACTS_DIR / a["filename"]).read_bytes()
        for a in email_record.get("attachments", [])
    }
    return build_items(
        order,
        anchor,
        meeting_audio=common.MEETING_AUDIO.read_bytes(),
        meeting_metadata=common.read_json(common.MEETING_METADATA),
        slack_messages=common.read_json(common.SLACK_THREAD),
        email_record=email_record,
        attachments=attachments,
    )


# ---------------------------------------------------------------- validation


def event_as_dict(event: pb.RawObjectEvent) -> dict[str, Any]:
    return MessageToDict(
        event, preserving_proto_field_name=True, always_print_fields_with_no_presence=True
    )


# The proto prefixes every enum value with its enum's name (SOURCE_TYPE_ZOOM,
# ROLE_RECORDING); Mind's RawObjectEvent uses the bare value (ZOOM, RECORDING).
# The consumer has to strip them the same way before it validates.
_EVENT_ENUM_PREFIXES = {
    "source_type": "SOURCE_TYPE_",
    "scope_decision": "SCOPE_DECISION_",
    "change_type": "CHANGE_TYPE_",
}
_REF_ENUM_PREFIXES = {"role": "ROLE_", "modality": "MODALITY_"}


def mind_view(event: pb.RawObjectEvent) -> dict[str, Any]:
    """The event as Mind's RawObjectEvent model reads it: enum values unprefixed."""
    data = event_as_dict(event)
    for field, prefix in _EVENT_ENUM_PREFIXES.items():
        data[field] = data[field].removeprefix(prefix)
    for ref in data["objects"]:
        for field, prefix in _REF_ENUM_PREFIXES.items():
            ref[field] = ref[field].removeprefix(prefix)
    return data


def contract_problems(item: FeedItem) -> list[str]:
    """Check an item against the proto and against Mind's RawObjectEvent model.

    A broken proto round trip or a hash that doesn't match its object is a bug
    in this script, and raises. A mismatch with Mind's pydantic contract is a
    finding about that contract (the wire format is Bridge's), so it's returned
    for the caller to report, not raised.
    """
    wire = item.event.SerializeToString()
    if pb.RawObjectEvent.FromString(wire) != item.event:
        raise AssertionError(f"{item.name}: event does not survive a protobuf round trip")
    for ref, obj in zip(item.event.objects, item.objects, strict=True):
        if ref.content_hash != obj.content_hash or ref.size_bytes != len(obj.content):
            raise AssertionError(f"{item.name}: {ref.object_key} does not match its content")

    try:
        RawObjectEventContract.model_validate(mind_view(item.event))
    except ValidationError as error:
        return [
            f"{'.'.join(str(part) for part in detail['loc'])}: {detail['msg']}"
            for detail in error.errors(include_url=False)
        ]
    return []


# ---------------------------------------------------------------- feeding

Lander = Callable[[LandedObject], None]
Publisher = Callable[[FeedItem], None]


def feed(
    items: Sequence[FeedItem],
    land: Lander,
    publish: Publisher,
    gap_s: float,
    sleep: Callable[[float], None] = time.sleep,
) -> list[dict[str, Any]]:
    """Land then publish each item in order, `gap_s` apart. Returns what was sent."""
    sent = []
    for index, item in enumerate(items, start=1):
        if index > 1 and gap_s > 0:
            logger.info("waiting %.0f s before the next item", gap_s)
            sleep(gap_s)
        step = f"[{index}/{len(items)}] {item.name.upper():<7}"
        for obj in item.objects:
            land(obj)
            logger.info(
                "%s landed     %s (%s, %s, %s bytes)",
                step,
                obj.key,
                pb.Role.Name(obj.role),
                pb.Modality.Name(obj.modality),
                f"{len(obj.content):,}",
            )
        publish(item)
        logger.info(
            "%s published  %s key=%s",
            step,
            pb.SourceType.Name(item.event.source_type),
            item.kafka_key,
        )
        sent.append(
            {
                "name": item.name,
                "correlation_id": item.event.correlation_id,
                "kafka_key": item.kafka_key,
                "published_at": datetime.now(UTC).isoformat(),
                "event": event_as_dict(item.event),
            }
        )
    return sent


def _expected_deadlines(anchor: date) -> list[str]:
    expected = common.read_json(common.EXPECTED)
    return [
        f"{task['id']} {task['title']!r} owner={task['suggested_owner']} "
        f"due {task['deadline_weekday']} {common.weekday_in_week(anchor, task['deadline_weekday'])}"
        for task in expected["tasks"]
    ]


def _minio_lander(endpoint: str, access_key: str, secret_key: str, bucket: str) -> Lander:
    from minio import Minio
    from minio.commonconfig import Tags

    secure = endpoint.startswith("https://")
    host = endpoint.removeprefix("https://").removeprefix("http://")
    client = Minio(host, access_key=access_key, secret_key=secret_key, secure=secure)
    # Same as MinioObjectStorageClient: create the bucket if it isn't there yet.
    if not client.bucket_exists(bucket):
        client.make_bucket(bucket)
        logger.info("created MinIO bucket %s", bucket)

    def land(obj: LandedObject) -> None:
        tags = Tags(for_object=True)
        tags.update(obj.tags)
        client.put_object(
            bucket,
            obj.key,
            io.BytesIO(obj.content),
            len(obj.content),
            content_type=obj.content_type,
            tags=tags,
        )

    return land


def _kafka_publisher(bootstrap_servers: str, schema_registry_url: str, topic: str) -> Publisher:
    from confluent_kafka.admin import AdminClient
    from confluent_kafka.schema_registry import SchemaRegistryClient

    from mind.messaging import ProtobufProducer
    from mind.messaging.config import MessagingSettings

    # Fail fast with a clear message instead of a producer blocking on a dead broker.
    metadata = AdminClient({"bootstrap.servers": bootstrap_servers}).list_topics(timeout=10)
    if topic not in metadata.topics:
        logger.warning("topic %s does not exist yet; the broker has to auto-create it", topic)
    SchemaRegistryClient({"url": schema_registry_url}).get_subjects()

    producer = ProtobufProducer(
        MessagingSettings(
            bootstrap_servers=bootstrap_servers, schema_registry_url=schema_registry_url
        )
    )

    def publish(item: FeedItem) -> None:
        producer.publish(topic, item.event, key=item.kafka_key)

    return publish


def _parse_anchor(value: str) -> date:
    if value == "this-week":
        return common.most_recent_monday(datetime.now(UTC).date())
    return date.fromisoformat(value)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__.splitlines()[0], formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--order", choices=sorted(ORDERS), default="natural")
    parser.add_argument(
        "--gap", type=float, help="seconds between items (default: 90 natural, 5 slack-first)"
    )
    parser.add_argument(
        "--anchor",
        default="this-week",
        help="the Monday the scenario happens on, YYYY-MM-DD (default: this week's Monday)",
    )
    parser.add_argument("--dry-run", action="store_true", help="build and validate only")
    parser.add_argument(
        "--minio-endpoint", default=os.environ.get("MINIO_ENDPOINT", "http://localhost:9000")
    )
    parser.add_argument("--minio-bucket", default=os.environ.get("MINIO_BUCKET", "bridge-raw"))
    parser.add_argument(
        "--bootstrap-servers", default=os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    )
    parser.add_argument(
        "--schema-registry-url",
        default=os.environ.get("SCHEMA_REGISTRY_URL", "http://localhost:8085/apis/ccompat/v6"),
    )
    parser.add_argument(
        "--topic", default=os.environ.get("BRIDGE_RAW_LANDED_TOPIC", "bridge.raw.landed")
    )
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    anchor = _parse_anchor(args.anchor)
    gap_s = args.gap if args.gap is not None else DEFAULT_GAP_S[args.order]
    run_id = f"{datetime.now(UTC):%Y%m%dT%H%M%SZ}-{args.order}"

    items = load_items(args.order, anchor)
    logger.info(
        "run %s: order=%s gap=%.0fs scenario Monday=%s, %d items",
        run_id,
        args.order,
        gap_s,
        anchor,
        len(items),
    )
    for item in items:
        for problem in contract_problems(item):
            logger.warning("%s: Mind's RawObjectEvent contract rejects it: %s", item.name, problem)

    if args.dry_run:
        for item in items:
            print(
                json.dumps(
                    {
                        "name": item.name,
                        "kafka_key": item.kafka_key,
                        "event": event_as_dict(item.event),
                    },
                    indent=2,
                )
            )
        logger.info("dry run: nothing landed or published")
        return 0

    land = _minio_lander(
        args.minio_endpoint,
        os.environ.get("MINIO_ACCESS_KEY", "minioadmin"),
        os.environ.get("MINIO_SECRET_KEY", "minioadmin"),
        args.minio_bucket,
    )
    publish = _kafka_publisher(args.bootstrap_servers, args.schema_registry_url, args.topic)
    sent = feed(items, land, publish, gap_s)

    common.RUNS_DIR.mkdir(exist_ok=True)
    manifest = common.RUNS_DIR / f"{run_id}.json"
    manifest.write_text(
        json.dumps(
            {
                "run_id": run_id,
                "order": args.order,
                "gap_s": gap_s,
                "scenario_monday": str(anchor),
                "topic": args.topic,
                "items": sent,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    logger.info("all %d items landed and published; run manifest: %s", len(sent), manifest)
    logger.info("expected (expected.json) for this run:")
    for line in _expected_deadlines(anchor):
        logger.info("  %s", line)
    logger.info("  T1 is mentioned in the meeting AND in Slack: one task, not two")
    logger.info("  T1 depends on T2 (task.dependencies.proposed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
