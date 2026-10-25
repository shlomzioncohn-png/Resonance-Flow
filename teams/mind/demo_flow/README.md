# demo_flow: inputs for the #359 end-to-end demo

The input half of [#359](https://github.com/KamaTechOrg/Resonance-Flow/issues/359): four
synthetic, English-only source artifacts describing one piece of work, the expected answer
written down before any run, and a feed that puts them on the real pipeline path: objects
landed in MinIO, then one pointer event per item on `bridge.raw.landed`.

**What this is not:** it is not the consumer, and it does not check the output. Reading
`bridge.raw.landed`, then OCR/ASR, extraction, dedup, dependencies and publishing, is the
pipeline's job (#349 and on). `make demo-flow` belongs to whoever runs the whole pipeline.
It can call `make demo-feed` for its input.

## The scenario

Brightline Sensors is quoting Northwind Traders for 300 sensor units. Everything happens on
one Monday (see [Timeline](#timeline)).

| Item | File | What it says | What it exercises |
|---|---|---|---|
| Meeting recording (Zoom) | `artifacts/meeting.m4a`, built from `scenario/meeting_script.txt`, with its Zoom meeting report `scenario/meeting.meeting.json` | Omer will finish the updated cost sheet by Wednesday ("about half a day of work"). Once he has it, Dana will send Northwind the price quote by Thursday. The report lists Lior, Dana and Omer as participants. | **ASR**: no transcript sidecar is landed, so the consumer has to transcribe it |
| Slack thread | `scenario/slack_thread.json` | Dana: "I'll get our pricing proposal over to Northwind before end of day Thursday." | **Duplicate detection**: the meeting's quote commitment again, in different words |
| Email | `scenario/email.json` | Northwind procurement asks Dana to return the signed supplier registration form by Friday. | A text body and a scanned attachment in **one** event |
| Scanned form (email attachment) | `artifacts/supplier_form.jpg`, built from `scenario/supplier_form.txt` | Northwind's supplier registration form | **OCR** (`modality=SCANNED_DOC`, `role=ATTACHMENT`) |

### Expected answer

[`expected.json`](expected.json) is the answer, fixed before any run:

| Task | Owner | Due | From |
|---|---|---|---|
| **T1** Send Northwind the price quote | Dana | Thursday | meeting **and** Slack: **one task, not two** |
| **T2** Finish the updated cost sheet | Omer | Wednesday, effort about 4 h | meeting |
| **T3** Return the signed supplier registration form to Northwind | Dana | Friday | email + scanned form |

- **Dependency:** T1 depends on T2, published on `task.dependencies.proposed`. T1 does not
  depend on T3.
- **Not tasks:** the warehouse-move update, Lior's question in Slack (answered in the
  thread), and "Thanks, sounds good."
- **Other fields:** every task carries `tier`, `urgency`, `role_context` and a score. The
  content doesn't fix their values, so the expected answer is only that they are set.

`tests/test_demo_feed.py` checks that every quoted mention in `expected.json` actually
appears in its source file, so the answer and the inputs can't drift apart.

## How to run it

From `teams/mind`, with the venv active and `pip install -e ".[dev]"` (it brings `minio`):

```bash
make demo-artifacts                 # builds artifacts/meeting.m4a and artifacts/supplier_form.jpg
make demo-check-media               # real OCR + ASR on them, on their own; fails on a missing phrase
make demo-feed ARGS="--dry-run"     # build + validate the three events; no infra needed
```

The real feed needs Bridge's local MinIO, Redpanda and schema registry:

```bash
cd ../bridge && docker compose up -d redpanda redpanda-console schema-registry && cd ../mind
docker run -d --name demo-minio -p 9000:9000 -p 9091:9001 -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin bitnamilegacy/minio:latest   # see Findings, Open 6
make demo-feed                      # natural order: meeting, then Slack 90 s later, then email
make demo-feed-slack-first          # Slack, then the meeting 5 s later, then email
```

Each `make` target is one command. Running the feed again is safe for Kafka and MinIO, but
the full pipeline only gives the expected answer on a clean store. See
[Repeatability](#repeatability) before a second run.

- **`make demo-artifacts`:** the form is drawn with Pillow and builds on any OS, giving the
  same image every time. The audio is read by the Windows speech synthesizer, so it builds
  **on Windows only**. Anywhere else, use a copy someone built, or record
  `scenario/meeting_script.txt` yourself in any format FFmpeg reads.
- **`make demo-check-media`:** needs the `tesseract` binary and the Whisper `base.en` weights
  (`models/README.md`). Your `.env` must have `WHISPER_MODEL=base.en`, as in `.env.example`.
  Only pinned sizes load.

### Options

`python -m demo_flow.feed --help` lists them all. Pass them through `make` with
`ARGS="..."`.

| Option | Default | Notes |
|---|---|---|
| `--order natural\|slack-first` | `natural` | the order items arrive in |
| `--gap SECONDS` | 90 (natural), 5 (slack-first) | wait between two items |
| `--anchor YYYY-MM-DD` | this week's Monday | the Monday the scenario happens on |
| `--tenant-id UUID` | `DEMO_TENANT_ID`, or `00000000-0000-0000-0000-000000000001` | the tenant every event is sent for. See [Tenant](#tenant) |
| `--dry-run` | off | build and validate, print the events, send nothing |
| `--minio-endpoint`, `--minio-bucket` | `MINIO_ENDPOINT`, `MINIO_BUCKET`, or `http://localhost:9000`, `bridge-raw` | |
| `--bootstrap-servers` | `KAFKA_BOOTSTRAP_SERVERS` or `localhost:9092` | |
| `--schema-registry-url` | `SCHEMA_REGISTRY_URL` or `http://localhost:8085/apis/ccompat/v6` | |
| `--topic` | `BRIDGE_RAW_LANDED_TOPIC` or `bridge.raw.landed` | |

The defaults and environment variable names are Bridge's own (`teams/bridge/.env.example`).
MinIO credentials come from `MINIO_ACCESS_KEY` and `MINIO_SECRET_KEY` (default
`minioadmin`).

### Tenant

Every event, object key, object tag and email body uses one tenant, set in one place:
`--tenant-id`, else `DEMO_TENANT_ID`, else `common.DEFAULT_TENANT_ID`. The scenario files
carry no tenant of their own.

It has to pass two checks:

1. **Mind accepts only a UUID.** The consumer skips any other tenant at ERROR
   (`ingestion/event_objects.py`, `tenant_uuid`), so nothing reaches extraction.
2. **Inbox creates a task only for a tenant it already has.** `tasks.tenant_id` is a
   foreign key to `tenants.id`.

The default is Inbox's built-in "System" tenant, `00000000-0000-0000-0000-000000000001`
(`teams/inbox/src/main/resources/db/migration/V15__add_system_admin.sql`), because it is the
one tenant every Inbox database has, even an empty one. To run the demo under a tenant of
its own, create it in Inbox first and pass its id. A value that is not a UUID stops the feed
before anything is sent, and `--dry-run` reports it as a contract problem.

### Arrival order

- **`natural`:** the meeting lands first. The 90 s gap gives the ~70 s recording time to be
  transcribed (ASR runs at ~0.1–0.3× real time) before the Slack message arrives.
- **`slack-first`:** the case #359 says is most likely to break duplicate detection. The
  Slack mention of T1 arrives before the meeting has landed at all, let alone been
  transcribed.
- **`--gap 0` with `natural`:** Slack arrives while the meeting is still being transcribed.

The feed can't see when the consumer has finished processing an item. A gap is the only
control it has.

### Timeline

All times are in UTC on the scenario's Monday, 2026-10-05:

- **Meeting:** runs 09:00–09:30 (its Zoom report's `start_time` is `source_timestamp`),
  lands at 09:35.
- **Slack thread:** sent from 09:40, lands at 09:45.
- **Email:** sent at 11:15, lands at 11:16.

A run moves the whole timeline by whole weeks onto `--anchor`, which defaults to the
current week's Monday. "By Thursday" therefore always means the Thursday after the
meeting, and at the end of a run the feed logs the expected due date of each task.

`ingested_at` is the shifted landing time, not the wall clock. That keeps every item on the
same reference date. On a weekend the default anchor is the Monday just gone, so the
deadlines are already past: pass `--anchor` with the next Monday if that matters.

### Repeatability

**Transport level: safe to rerun.** Every run mints new correlation ids and new object keys,
the same way Bridge does. Meeting objects are named after the meeting's correlation id;
Slack and email objects get a random UUID each. Each run is also a new Zoom meeting, with
its own `uuid` and so its own `source_ref`. The event dedup key is `correlation_id:revision`,
so a second run's events are new events and are not skipped as replays.

**Pipeline level: not repeatable yet.** Mind's duplicate detection
(`embeddings/duplicate_detection.py`) compares each new task with every task already in
the vector store. A second run carries exactly the same content as the first, so its three
tasks match the first run's stored tasks and are treated as duplicates. The second run
produces **0 tasks** instead of the 3 in `expected.json`, which looks like a failure but is
duplicate detection working as designed.

Until the demo can clear its own data, run the full pipeline once per clean store. That
means a fresh vector store (and Inbox state) before every rehearsal and before Demo Day.
Clearing only the demo's data needs a demo-scoped reset. That is waiting on the tenant fix
in duplicate detection, and is not part of this feed yet.

Each real run also writes `runs/<run-id>.json`, which is gitignored. It maps every item to
its correlation id, Kafka key, object keys and the full event, so you can tell which run's
output you are looking at.

## What the feed sends

There is no consumer of `bridge.raw.landed` in Mind yet, so the only payload format to
match is the one Bridge produces. The feed rebuilds exactly what Bridge's own lanes land
and publish for the same input:

| Item | Bridge code it mirrors | Objects (role, modality, content type) | Event |
|---|---|---|---|
| meeting | `media/MediaIntakeService.landMeeting`, `MediaIntakeOrchestrator` (Zoom) | `ROLE_RECORDING` `MODALITY_AUDIO` `audio/mp4`, key `ZOOM/<tenant>/<date>/<correlation_id>.m4a`. Plus the Zoom report: `ROLE_SOURCE_METADATA` `MODALITY_TEXT` `application/json`, key `…/<correlation_id>.json` | `source_type=SOURCE_TYPE_ZOOM`, participants = the report's names, `source_timestamp` = its `start_time`, `source_ref` = its `uuid` |
| Slack | `intake/service/ChatSourceRecordMapper`, `SourceRecordRefMapper` | `ROLE_TRANSCRIPT` `MODALITY_TEXT` `text/plain; charset=utf-8`: one `sender: text` line per message. Key `SLACK/<tenant>/<date>/<uuid>.txt` | `SOURCE_TYPE_SLACK`, participants = the senders, `source_timestamp` = the first message |
| email | `intake/parser/email/FixtureEmailSourceReader` | `ROLE_BODY` `MODALITY_TEXT` `application/json`: the record as compact JSON, attachment base64 inline. Plus `ROLE_ATTACHMENT` `MODALITY_SCANNED_DOC` `image/jpeg`, key `<uuid>-supplier_form.jpg` | `SOURCE_TYPE_EMAIL`, participants = the sender, `source_timestamp` = `date` |

The Zoom report is Zoom's Report API shape, with the same field names as Bridge's own
fixture (`teams/bridge/src/main/resources/fixtures/media/zoom-meeting-metadata.sample.json`).
Each run lands it with a fresh `uuid` and its times moved onto the run's Monday.

Every event also carries Bridge's constants:

| Field | Value |
|---|---|
| `schema_version` | `"1.0"` |
| `revision` | `0` |
| `change_type` | `CHANGE_TYPE_UPSERT` |
| `change_significance_score` | `100` |
| `scope_decision` | `SCOPE_DECISION_INCLUDED` |
| `classification` | unset (Bridge's classifier is off by default) |

Every MinIO object gets the same four tags Bridge's `storage/ObjectTags` puts on it. Tags
and the first segment of an object key use Bridge's own enum name (`ZOOM`), without the
proto's prefix. The
Kafka key is Bridge's `dedupKey()`, `correlation_id:revision`. Events are serialized with
Mind's own `ProtobufProducer` from `shared/proto/raw-object-event.proto`.

Before sending anything, every run (dry or real) does three checks:

- each event survives a protobuf round trip;
- each object's hash and size match its content;
- each event is validated against Mind's pydantic `RawObjectEvent`, after the proto's enum
  prefixes are stripped (`SOURCE_TYPE_ZOOM` → `ZOOM`, `ROLE_BODY` → `BODY`; see
  `feed.mind_view`). The consumer will need the same mapping, because Mind's model uses the
  bare values. A rejection is logged as a warning, not raised: the wire format is Bridge's, and
  a mismatch is a finding about the contract. All three events pass today.

## What you see while it runs

```
... demo_flow.feed INFO run 20261019T074840Z-slack-first: order=slack-first gap=5s scenario Monday=2026-10-19 tenant=00000000-0000-0000-0000-000000000001, 3 items
... demo_flow.feed INFO [1/3] SLACK   landed     SLACK/00000000-0000-0000-0000-000000000001/2026-10-19/<uuid>.txt (ROLE_TRANSCRIPT, MODALITY_TEXT, 213 bytes)
... demo_flow.feed INFO [1/3] SLACK   published  SOURCE_TYPE_SLACK key=<correlation-id>:0
... demo_flow.feed INFO waiting 5 s before the next item
... demo_flow.feed INFO [2/3] MEETING landed     ZOOM/00000000-0000-0000-0000-000000000001/2026-10-19/<correlation-id>.m4a (ROLE_RECORDING, MODALITY_AUDIO, 334,022 bytes)
... demo_flow.feed INFO [2/3] MEETING landed     ZOOM/00000000-0000-0000-0000-000000000001/2026-10-19/<correlation-id>.json (ROLE_SOURCE_METADATA, MODALITY_TEXT, 1,748 bytes)
...
... demo_flow.feed INFO expected (expected.json) for this run:
... demo_flow.feed INFO   T1 'Send Northwind the price quote' owner=Dana due Thursday 2026-10-22
```

To see what landed, use the MinIO console at <http://localhost:9091> (Bridge's compose maps
it there, not to 9001) and Redpanda Console at <http://localhost:8090>, topic
`bridge.raw.landed`.

## Findings

Checked against `main` at `0275276e` (2026-10-19, after #823 merged Esther's
`esther/media-llm-classification`). Each finding names its owner. None is worked around in
the feed.

### Open

1. **Mind has no `bridge.raw.landed` consumer yet (#349).** Nothing on `main` reads the topic.
   The only handler, `ingestion/raw_object_event_handler.py` on `yonit/mind-dual-intake`,
   predates the per-item event: it reads `event.object_key`. So this feed has not been run
   through the pipeline, and nothing here shows the demo working end to end.
2. **The consumer has to strip the proto's enum prefixes (Mind).** Since #823 every enum value
   on the wire carries its enum's name (`SOURCE_TYPE_ZOOM`, `ROLE_RECORDING`,
   `SCOPE_DECISION_INCLUDED`, …). Mind's `contracts.RawObjectEvent` uses the bare values
   (`ZOOM`, `RECORDING`, `INCLUDED`), so a straight `MessageToDict` → `model_validate` rejects
   every event. With the prefixes stripped (`feed.mind_view`), all three events pass, the Zoom
   meeting included.
3. **Relay's registered copy of the schema predates #823 (Relay).** The schema-sync hook
   registers `teams/relay/schema-sync-hook/files/schemas/raw-object-event.proto` as
   `bridge.raw.landed-value`, and that file is still the old schema: `MEETING`/`DOC`,
   unprefixed enums, no `source_ref` and no `classification`. `shared/proto` changed in #823;
   the copy didn't. In the cluster the registered schema would not match what Bridge and this
   feed now publish.
4. **Bridge's own Protobuf publish fails against its local registry (Bridge/Relay).** On `main`
   (rebuilt and run 2026-10-19), with the compose file's registry
   (`REGISTRY_CCOMPAT_USE_CANONICAL_HASH=true`) and the schema registered from `shared/proto`
   as text, exactly as Relay's `register-schema.sh` does it (references `[]`,
   `google/protobuf/*` skipped):
   - `POST /api/bridge/media/run` → `failedFiles: ["meeting.m4a"]`
   - `POST /api/bridge/intake/EMAIL/run` → 500

   Both fail with `RestClientException: The given schema does not match any schema under the
   subject bridge.raw.landed-value; error code: 40403`, before the feed published anything.
   Bridge lands its objects and then fails to publish, so MinIO gets objects that no event
   points to.
5. **The feed registers its own schema version (Mind, Relay).** Bridge's producer never
   registers (`auto.register.schemas=false`); Relay registers centrally as `.proto` text. The
   feed publishes through Mind's `ProtobufProducer`, which auto-registers the schema as a
   base64 `FileDescriptorProto`. Measured 2026-10-19:
   - With Relay's text v1 in place, the feed's first publish added **v2**, the same schema in
     descriptor form (both match `shared/proto`). Later publishes reuse v2.
   - Lookup-only does not work with confluent-kafka 2.10.1. Plain lookup returns `40403`.
     `use.latest.version` fails with "Error parsing message with type
     'google.protobuf.FileDescriptorProto'", the limitation `producer.py`'s comment describes.

   Every Mind producer behaves this way, so whether Mind may register (and in which form) is a
   Relay/Mind decision.
6. **Bridge's local MinIO doesn't start (Bridge).** `teams/bridge/docker-compose.yml` still
   uses `minio/minio:latest`, and Docker Hub refuses it ("pull access denied … repository does
   not exist"); `quay.io/minio/minio` answers 401. MinIO stopped publishing free images. The
   shared Helm chart already uses `bitnamilegacy/minio`. To run the feed, start that image by
   hand with the compose file's ports and credentials (the command is under
   [How to run it](#how-to-run-it)).
7. **The email body differs by Bridge mode (Bridge).** In fixture mode, which is the default
   and what local Bridge runs, BODY is the simplified fixture JSON with every attachment
   inline as base64: 762 KB of body for a 571 KB scan, the scan carried twice. In real Gmail
   mode it is Gmail's whole `Message` resource (`RealEmailSourceReader`, `message.toString()`).
   A consumer that parses BODY has to handle two shapes. This feed sends the fixture shape.
8. **Bridge's README gives the wrong MinIO console port.** It says
   `http://localhost:9001`, but `teams/bridge/docker-compose.yml` maps it to **9091**.
9. **The demo can't be rerun through the full pipeline yet (Mind).** Duplicate detection
   matches a rerun's tasks against the previous run's stored tasks, so the second run yields
   0 tasks (see [Repeatability](#repeatability)). The planned fix is a reset that deletes the
   demo's data before a run, after the tenant fix in duplicate detection.
10. **Bridge's media lane sends a tenant that is not a UUID (Bridge).**
    `teams/bridge/.env.example` sets `BRIDGE_MEDIA_TENANT_ID=demo-tenant`, and Mind's consumer
    skips every event whose tenant is not a UUID (see [Tenant](#tenant)). So a meeting or
    scanned document that comes through Bridge itself, not through this feed, never reaches
    extraction. The feed uses a UUID tenant and is not affected.

### Resolved by #823

- **Meeting events were `MEETING`.** `shared/proto` and Bridge now send `SOURCE_TYPE_ZOOM` /
  `SOURCE_TYPE_BIGBLUEBUTTON`, which matches Mind's `RawSourceType` once the prefix is stripped
  (Open 2).
- **Meeting events had no participants, `source_timestamp` or `source_ref`.** The Zoom report
  sidecar now fills all three, and the feed sends it.
- **The media lane published JSON.** It now publishes Protobuf through the same serializer as
  the other lanes. Its landed objects match the feed's field for field: key
  `<correlation_id>.m4a` / `.json`, content types, sizes and tags (compared on 2026-10-19).
- **`schema_version` disagreed** with the proto's `"0.1"` comment. The comment is gone;
  Bridge and the feed both send `"1.0"`.

## Files

| Path | In Git | What |
|---|---|---|
| `scenario/` | yes | sources: meeting script, Zoom meeting report, Slack thread, email, form text |
| `expected.json` | yes | the expected answer |
| `common.py`, `build_artifacts.py`, `check_media.py`, `feed.py` | yes | the tooling |
| `artifacts/` | yes | `meeting.m4a` (326 KB), `supplier_form.jpg` (558 KB), built from `scenario/` |
| `runs/` | no (gitignored) | one manifest per real run |

The two built artifacts are committed because the audio can only be built on Windows, and
anyone running the demo on macOS or Linux needs it. Rebuild them with `make demo-artifacts`
after editing `scenario/`, then run `make demo-check-media`.
