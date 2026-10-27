# Team Bridge - Service

Foundation module for Team Bridge (source adapters & communication ingestion).

This is a minimal Spring Boot skeleton. No business logic or real APIs are implemented yet - the
goal of this stage is a clean, working Maven/Spring Boot project that later work can build on.

## Sub-roles (4 developers)

| Role | Owner | Owns |
|---|---|---|
| GitHub-adapter engineer | Hadas | GitHub ETL adapter end to end, including incremental sync |
| CRM-adapter engineer | Ruth | CRM ETL adapter end to end, including deal-stage sync |
| Unstructured-intake engineer | Malki | Gmail/Drive/Slack/WhatsApp/calendar intake, scoping enforcement |
| Meeting/doc/audio hand-off engineer | Esther | BigBlueButton/Zoom + scanned-document intake, hand-off contract with Mind |

## Stack

- Java 21
- Spring Boot 3.3.13 (Maven)
- Docker / Docker Compose

The Unstructured-intake and meeting/doc/audio lanes (below) now run against real local MinIO +
Redpanda via Docker Compose, sharing the same MinIO bucket/Kafka topic. The other 2 lanes
(GitHub, CRM) haven't wired their infra yet.

## Project structure

```
src/main/java/com/rnd/bridge/
├── BridgeApplication.java   # Spring Boot entry point
├── controller/               # shared/cross-lane (currently empty)
├── service/                  # shared/cross-lane (currently empty)
├── repository/
├── dto/
├── config/
├── core/                      # shared pipeline contract (SourceReader/PayloadMapper/…)
├── raw/                       # shared RawObjectEvent model + validator (both unstructured lanes)
├── storage/                   # shared MinIO client (both unstructured lanes)
├── scoping/                   # shared scoping filter (Malki's lane; media lane skips it)
├── intake/                    # Malki's lane — see "Unstructured-intake lane" below
└── media/                     # Esther's lane — see "Meeting/doc/audio hand-off" below
src/main/resources/application.yml
src/main/resources/fixtures/unstructured/   # synthetic Gmail/Drive/Slack/WhatsApp/Calendar data
src/test/java/com/rnd/bridge/
```

## Running locally with Maven

Requires a local Maven installation (`mvn`) and JDK 21.

```bash
mvn spring-boot:run
```

The app starts on http://localhost:8080

To just build the jar:

```bash
mvn clean package
java -jar target/bridge-0.1.0-SNAPSHOT.jar
```

## Running with Docker (no local Maven/JDK needed)

1. Copy the env file:

   ```bash
   cp .env.example .env
   ```

2. Build and run:

   ```bash
   docker compose up --build
   ```

The app will be available at http://localhost:8080 (or whatever `SERVER_PORT` is set to in `.env`).

To stop:

```bash
docker compose down
```

## Unstructured-intake lane (Malki)

Gmail · Drive · Slack · WhatsApp · Calendar → scoping decision → land tagged raw object in
MinIO → publish a pointer event to Kafka topic `bridge.raw.landed`. This lane never builds a
`Task` — Mind consumes the pointer later and extracts the eventual Task from the landed object.
No live source credentials are used yet; static fixtures under
`src/main/resources/fixtures/unstructured/` stand in for the 5 real sources.

### Running it locally

1. Start MinIO + Redpanda only (not the `bridge` container itself — run the app on the host
   for a faster dev loop):

   ```bash
   docker compose up -d minio redpanda
   ```

2. Run the app on the host (needs local Maven + JDK 21 — see "Running locally with Maven"
   above):

   ```bash
   mvn spring-boot:run
   ```

3. Trigger ingestion for a source:

   ```bash
   curl -X POST http://localhost:8080/api/bridge/intake/EMAIL/run
   ```

   Valid `{sourceType}` values: `EMAIL`, `SLACK`, `WHATSAPP`, `DRIVE_DOC`, `CALENDAR`. Returns
   the `RawObjectEvent` list that was landed + published.

4. Inspect results: MinIO console at http://localhost:9001 (user/pass: `minioadmin`/`minioadmin`
   by default, see `.env.example`) shows landed objects tagged `source_type`/`tenant_id`/
   `ingested_at`/`scope_decision`. Kafka topic `bridge.raw.landed` receives one pointer event
   per landed object.

Running the full stack in Docker (`docker compose up --build`, including the `bridge`
container itself) also works — the compose file wires MinIO/Kafka service-name addresses for
that case — but the host+Docker-infra split above is the faster loop for day-to-day work.

### Slack real-time ingestion (Events API)

Alongside the polling trigger above, Slack messages can also be ingested in near-real-time via
`POST /api/bridge/slack/events` (`SlackEventsController`) — Slack's Events API webhook, covering
new messages, edits (`message_changed`), and threaded replies, with retry-safe publication and
idempotent attachment handling. See `plan.md`'s "Slack real-time ingestion" section for the full
design and current limitations. Exposing this endpoint to Slack (a public URL, signing secret,
event subscription setup) isn't documented here yet — not yet exercised against real Slack
traffic.

### Fixture format

Realism was chosen per source, not uniformly (see `plan.md` for the full reasoning): Gmail
stays a simplified JSON stand-in (real Gmail export is `.mbox` — parsing that is its own
connector-sized task). WhatsApp fixtures use the real export line format, one `.txt` file per
chat (`whatsapp-*-sample.txt` — chat identity comes from the filename, matching how WhatsApp
actually exports). Drive, Calendar, and Slack fixtures use trimmed real API/export field names
(Google Drive API v3 File resource, Google Calendar API v3 Event resource, Slack export message
object) since those are genuinely JSON in production.

Chat messages (Slack, WhatsApp) are grouped into **sessions** before landing — a run of
consecutive messages with no gap larger than `bridge.intake.chat.session-gap-minutes` (default
45) becomes one landed object + one pointer event, not one per message. See `plan.md` for why.

### Scoping

`ScopingFilterService` exists as its own class (a deliberate seam) but currently always returns
`INCLUDED` — per direct instruction, no work-related-only filtering runs yet. Swapping in real
filtering logic later should only touch this one class.

### RawObjectEvent schema

`RawObjectEvent` matches the official JSON Schema exactly (`schemaVersion`, `objectKey`,
`sourceType`, `tenantId`, `ingestedAt`, `scopeDecision`, `dedupKey`, `modality`, `traceId`,
plus optional `contentType`/`sizeBytes`/`sourceTimestamp`; `language` is omitted — real
language detection is out of scope for this skeleton). Two fields worth knowing:
- `dedupKey` is a real `sha256:<64 hex>` digest of `sourceType + tenantId + naturalId` —
  deterministic, so a retried publish produces the same key.
- `traceId` is one UUID per intake run (one `POST .../run` call), shared by every event that
  call produces — not one per individual event.

## Meeting/doc/audio hand-off (Esther)

Lands Zoom/BBB recordings and scanned documents in MinIO, tagged for Mind's ASR/OCR pipeline, and
publishes a pointer event per object to Kafka topic `bridge.raw.landed` — the same MinIO
bucket/Kafka topic the Unstructured-intake lane uses, via the shared `raw`/`storage`/`core`
packages above. Bridge never builds a `Task` for this content itself - Mind reads the pointer
event and extracts the eventual Task from the object.

**Code:** `media/MediaIntakeService.java` (lands already-read bytes through the shared
`storage.ObjectStorageClient` and builds the matching `raw.model.RawObjectEvent` -
`landMeeting(...)` for a recording plus whichever of its transcript/meeting-metadata
sidecars were found, `landStandalone(...)` for a scanned doc), `media/MediaEventPublisher.java`
(validates via the shared `raw.RawObjectValidator`, then publishes), `media/MediaDedupIndex.java`
(content-hash dedup index stored in MinIO, see below), `media/MediaIntakeOrchestrator.java`
(manual test intake, see below - holds a declarative list of `{directory, sourceType,
modality}` mappings, so adding a new intake folder is a new list entry, not a new method),
`media/controller/MediaIntakeController.java` (triggers it). `source_type` for this lane is
`ZOOM`, `BIGBLUEBUTTON` or `SCANNED_DOC` - values on the shared `raw.model.RawSourceType`
enum, which names the source *platform* (so a consumer can build a link back from
`source_type` + `source_ref`; `RawSourceType.isMeeting()` is the one place that says which
values are meetings) (`SCANNED_DOC` isn't in Mind's confirmed contract yet - tracked in
[#188](https://github.com/KamaTechOrg/Resonance-Flow/issues/188)).

**Schema shape (as of issue [#31](https://github.com/KamaTechOrg/Resonance-Flow/issues/31)):**
one `RawObjectEvent` per logical recording/doc, not one per physical file.
`object_key`/`content_hash`/`content_type`/`size_bytes`/`modality` live on each entry of the
event's `objects[]` list (`raw.model.RawObjectRef`), tagged with a `role`
(`RECORDING`/`TRANSCRIPT`/`SOURCE_METADATA` for this lane; `BODY`/`ATTACHMENT` are Malki's).
`transcript_object_key` and the former `additional_metadata_object_key` are gone - a
transcript or meeting-metadata sidecar is just another entry in `objects[]` now, not a
pointer field. `correlation_id` is required and is this lane's Kafka dedup key (not a content
hash, since there's no longer one single hash on the event) - a fresh UUID per landed
recording, shared by every object's `object_key` so they land next to each other in MinIO.
`source_ref` is the platform's own unique identifier for this one meeting occurrence - Zoom's
`uuid`, or BBB's `recordID` - and `null` when the metadata sidecar has neither. Never
`meetingId`: Zoom reuses it across a recurring meeting and BBB across every session in the same
room, and the issue #31 contract requires `source_ref` to be unique within its `source_type`.
It's an identifier, not a link, distinct from any `object_key` (which only ever points into our
own MinIO copy). `revision`/`change_type`/`change_significance_score` are always
`0`/`UPSERT`/`100` on this lane - a Zoom/BBB recording or scanned doc is never revised once
landed, unlike Malki's lane where an edited email/message bumps `revision`.

### Running it locally

Same local MinIO + Redpanda as the Unstructured-intake lane above (`docker compose up -d minio
redpanda`), no separate infra to start. Drop files into your own local folders (paths configured
via `.env` - see `BRIDGE_MEDIA_*` in `.env.example`), then hit the endpoint:

Before the first publish after the registry container (re)starts, register the schema - the
local Apicurio registry is in-memory, and the producer never auto-registers. Register the
**canonicalized** file, as Relay does in the cluster; registering the raw `shared/proto` file
fails every publish with `40403`:

```bash
# from the repo root
(cd teams/relay/schema-canonicalize && mvn -q package)
java -jar teams/relay/schema-canonicalize/target/canonicalize.jar shared/proto raw-object-event.proto > /tmp/raw-object-event.proto
SCHEMA_REGISTRY_URL=http://localhost:8085/apis/ccompat/v6 \
  bash teams/relay/ci/register-schema.sh /tmp/raw-object-event.proto bridge.raw.landed-value
```

Every event carries `tenant_id` from `BRIDGE_TENANT_ID` (default `00000000-0000-0000-0000-000000000001`,
Inbox's System tenant). It must be a UUID of a tenant that exists in Inbox - Mind passes it
through to Inbox, and `RawObjectValidator` refuses to publish a non-UUID one.

If the model call fails with `PKIX path building failed` while `curl` to Google works, the JVM
doesn't trust a certificate your machine's HTTPS inspection inserts. Run with
`mvn spring-boot:run -Dspring-boot.run.jvmArguments=-Djavax.net.ssl.trustStoreType=Windows-ROOT`
(Windows) so Java uses the OS certificate store.

```bash
curl -X POST http://localhost:8080/api/bridge/media/run
```

| Env var | Uploads as |
|---|---|
| `BRIDGE_MEDIA_ZOOM_RECORDINGS_DIR` | `source_type=ZOOM`, `modality=AUDIO` |
| `BRIDGE_MEDIA_BBB_RECORDINGS_DIR` | `source_type=BIGBLUEBUTTON`, `modality=AUDIO` |
| `BRIDGE_MEDIA_SCANNED_DOCS_DIR` | `source_type=SCANNED_DOC`, `modality=SCANNED_DOC` |

Each platform has its own folder and its own `source_type`; both share the same meeting handling
(transcript and metadata sidecars, meeting categories).

To have the folders polled automatically instead, set `BRIDGE_MEDIA_SYNC_CRON_ENABLED=true`
(schedule `BRIDGE_MEDIA_SYNC_CRON_EXPRESSION`, default `0 */5 * * * ?`, every 5 minutes):
`MediaSyncJob` runs the same intake through the shared `SyncJob` mechanism (`SyncJobScheduler`),
like the CRM and Email/Calendar/Drive syncs, so a file dropped into a folder lands within one
interval. A manual run and a scheduled run never overlap - the second one waits.

Each successfully uploaded file is moved into an `uploaded/` subfolder of wherever it came from,
so re-triggering the endpoint only picks up genuinely new files instead of re-landing (and
re-publishing an event for) the same file every time.

### Junk filtering, dedup, and transcripts

Per file, before it lands:

1. **Junk filter** - an empty file, or one below `bridge.media.min-file-size-bytes` (default
   1024 bytes; `BRIDGE_MEDIA_MIN_FILE_SIZE_BYTES`), is moved to a `rejected/` subfolder instead
   of `uploaded/` - never landed, never published. This is a file-size proxy for "empty/corrupt
   recording", not real audio-duration parsing (that needs a codec-aware library this lane
   doesn't depend on) - tune the threshold based on what garbage actually shows up.
2. **Dedup** - `MediaDedupIndex` keeps a set of already-landed `content_hash` values per
   `(sourceType, tenantId)` as a small JSON file in MinIO (`_dedup/{sourceType}/{tenantId}.json`
   - same "MinIO as lightweight state store" approach `plan.md` uses for the Unstructured-intake
   lane's sync cursor). A file whose content hash is already in the index is moved to
   `uploaded/` but not re-landed or re-published.
3. **Transcript pairing** (meetings only) - a same-basename sidecar file (`.vtt`, `.srt`, or
   `.txt`) next to a recording is landed alongside it as a `role: TRANSCRIPT` entry in the
   same event's `objects[]` - it does not get its own `RawObjectEvent`. Both files move to
   `uploaded/` together.

   For real Zoom, that `.vtt` is what Zoom's own Cloud Recording API returns: `GET
   /meetings/{meetingId}/recordings` lists every asset for a meeting in one `recording_files[]`
   array, and the transcript is the entry with `file_type: "TRANSCRIPT"`,
   `file_extension: "VTT"`, `recording_type: "audio_transcript"` - see
   `fixtures/media/zoom-cloud-recording.sample.json` for the full shape (reference only, not
   read by any code yet - field names are the publicly documented API, not live-verified this
   session, so treat values as illustrative). Fetching it for real means downloading that
   entry's `download_url` (needs the OAuth token attached, or it 404s), and hits the same
   blocker as the Real* meeting-metadata reader below: this lane's manual-folder trigger has no
   `meetingId` to call the API with.

   The seam for that real call already exists: `media/meeting/zoom/ZoomCloudRecordingClient`
   (+ DTOs `ZoomRecordingFile`/`ZoomRecordingsResponse` matching the documented response shape)
   is ready to implement against, gated behind `bridge.media.zoom.mode: real`
   (`application.yml`/`.env`) - same convention as `bridge.intake.*.mode: real` elsewhere in
   this repo: `RealZoomCloudRecordingClient` is a throw-stub, not a working implementation, on
   purpose (see its javadoc for exactly what filling it in needs - Server-to-Server OAuth,
   the two Zoom hosts involved, etc.) - untested network/auth code that nothing ever calls is a
   false sense of "ready", so that part is written up rather than shipped unverified.
   `fetchRecordings` is scoped to return the whole response (every `recording_files[]` entry
   plus meeting-level fields like `password`/`share_url`/`total_size`), not just the transcript
   entry - `findTranscript` is a convenience default method on top of it - so a future
   "additional info" sidecar built from this endpoint won't be working off a pre-filtered
   subset once it's implemented.
4. **Meeting-metadata pairing** (meetings only) - a same-basename sidecar carrying the
   source platform's own meeting report is landed alongside the recording, in its native
   format (no conversion), as a `role: SOURCE_METADATA` entry in the same event's `objects[]`:
   `<basename>.meeting.json` in the Zoom folder, `<basename>.metadata.xml` in the BBB folder.
   Both fixtures are field-for-field copies of each platform's own **officially documented**
   API response shape (Zoom's Dashboard/Report API; BBB's `GET getRecordings` - verified
   live against https://docs.bigbluebutton.org/development/api/#get-getrecordings, not from
   memory), with fabricated example values where we have no live API access yet. It's parsed
   once (`media/meeting/MeetingMetadataReader` + fixture implementations) to fill the event's
   `participants`/`source_timestamp`/`source_ref` fields - the landed sidecar bytes themselves
   are untouched.

   Note: BBB's `getRecordings` only returns a participant **count** (`<participants>3</participants>`),
   never a per-person list - BBB has no public documented endpoint for that. The only place
   BBB exposes real per-participant join/leave is its "Learning Analytics Dashboard" data,
   reachable either through an **internal, undocumented** API call (`learningDashboard` -
   explicitly listed under BBB's own "Internal API calls", not the public developer surface)
   or a **server-to-server callback** (`meta_analytics-callback-url`, which BBB itself calls
   when a meeting ends) - neither is a regular request-response API call this lane's
   local-folder trigger could make. So `participants` stays genuinely empty for BBB
   recordings - not a parsing gap. Zoom's Report API, by contrast, does return real
   per-participant join/leave times in a normal API call, so Zoom's fixture carries them.

   Only fixture readers exist today (see `media/meeting/Fixture*MeetingMetadataReader`) - real
   Zoom API wiring needs a `meetingId` this lane's manual-folder trigger doesn't have yet, and
   real BBB per-participant data would need callback infrastructure this lane doesn't have.

### Classification (scope_decision)

After dedup and before landing, `media/classification/MediaClassificationService` decides each
item's `scope_decision` and attaches the optional `classification` object it was derived from
(category, file quality, sensitivity level, reason, and which model/prompt decided - see
`raw/model/Classification` and `shared/proto/raw-object-event.proto`). It only tags the item as
a whole; it never extracts tasks, people, dates or priority - that stays with Mind.

1. **Rules first** (free, deterministic, `decided_by: RULE`): a meeting shorter than
   `min-meeting-duration` (default 30s) → `TEST`; a meeting with a single participant (distinct
   people, so rejoins count once; unknown counts never trigger it) → `TEST`; a meeting under 3 minutes whose transcript has
   1–14 words → `TEST`; a document whose pages were *all* checked and are all
   blank → `BLANK`. An empty transcript is deliberately *not* a rule - it means transcription
   failed, not that the meeting was empty.
2. **Model second**, only for what the rules didn't settle (`decided_by: MODEL`): a meeting is
   judged from its topic, duration (in seconds under 2 minutes), distinct headcount and a
   transcript excerpt - never the audio, never participant names. A transcript longer than
   `transcript-excerpt-chars` is sent as its opening plus a slice from its middle, half each:
   the opening alone is often greetings and mic checks. A meeting with no spoken transcript
   text is never sent - it stays unclassified and `INCLUDED`. Any OpenAI-compatible endpoint
   works - Gemini by default; switching provider is a change to `bridge.ai.*` only.

   The prompts (`prompt-version` `v6`) tell the model that transcripts and scans may be Hebrew,
   English or both; give `TEST` concrete cues (mic checks, silence, background noise only); keep
   category separate from quality (noise alone is `TEST` + `POOR`, a noisy real meeting keeps
   its category); define each document category in one line; and ask for 0.8+ confidence before
   `TEST`, `SOCIAL` or `ADVERTISEMENT`, since those are excluded. Bump `prompt-version` on every
   prompt change so results stay comparable.

**Scanned documents** are examined once by `media/classification/document/DocumentScanner`, and
both steps above work from that one scan:

- **Formats** are an explicit list - the union of the `PagedDocumentReader` beans' extensions:
  PNG, JPEG, TIFF (incl. multi-page) via ImageIO, and PDF via Apache PDFBox. Anything
  else (DOC/DOCX, WEBP, BMP, GIF, ...) is never opened or sent - it lands `INCLUDED`, unclassified. A new
  format is one new `PagedDocumentReader`.
- **Pages** are rendered one at a time, in memory only, and checked for blankness locally; blank
  pages are skipped. The scan stops once `documents.pages-to-send` (2) content pages are found or
  `documents.max-pages-to-scan` (5) pages were checked - a 100-page PDF with content on page 1
  costs two renders.
- **Outcomes**: content found → those pages (with their page numbers) go to the model; every
  page checked and blank → `BLANK` by rule; the limit reached with only blank pages and more
  pages left → unclassified, `INCLUDED` (unchecked pages may have content); unsupported or
  corrupt/password-protected → unclassified, `INCLUDED`.
- The model never sees a blank page, so a `BLANK` verdict from it is recorded as `OTHER` - a
  document with content can never be `EXCLUDED` as blank.
3. **Fails open**: disabled, model error, rate limit or a malformed reply → `INCLUDED`, no
   `classification`. An item is `EXCLUDED` only when its category is in
   `excluded-meeting-categories`/`excluded-document-categories` **and** its confidence reaches
   `min-confidence-to-exclude` (0.6). `EXCLUDED` items are still landed and published - just
   tagged, so mistakes can be reviewed.

To try it, in `.env`:

```bash
BRIDGE_MEDIA_CLASSIFICATION_ENABLED=true
BRIDGE_MEDIA_CLASSIFIER_API_KEY=<your Gemini key>   # omit to run the rules only
```

Each decision is logged (`MediaClassificationService`: category, confidence, reason), and shows up
in the published event's `scope_decision`/`classification` and in the landed objects'
`scope_decision` MinIO tag.

Live check of rules `v4` / prompt `v6` against Gemini (2026-10-26, synthetic recordings with
hand-written transcripts and Zoom sidecars, real scans):

| Item | Verdict |
|---|---|
| 2-minute meeting, 7 transcribed words | `EXCLUDED` by RULE - `TEST` |
| 20-minute meeting, one participant | `EXCLUDED` by RULE - `TEST` |
| 6-minute mic check ("אחת, אחת", background noise), two participants | `EXCLUDED` by MODEL - `TEST` (0.98) |
| Sprint planning that opens with "שומעים אותי?" | `INCLUDED` - `PLANNING` (0.95) |
| Vendor invoice / internal memo | `INVOICE` (1.0) / `MEMO` (0.98) |
| Kids' activity page / e-ticket | `OTHER` (0.95) / `OTHER` (0.85) - not `ADVERTISEMENT` |

Every model call counts against the free tier's daily quota (20 requests/day for Gemini), so keep
live test runs small.

### `SOURCE_METADATA` format (for consumers)

The `role: SOURCE_METADATA` object is the platform's own meeting report, landed byte-for-byte -
Bridge never converts or edits it. Its format depends on `source_type`, and its `content_type`
says which one. Consumers aren't required to read it: everything Bridge derives from it is already
on the event (`participants`, `source_timestamp`, `source_ref`).

| `source_type` | `content_type` | Shape |
|---|---|---|
| `ZOOM` | `application/json` | Zoom Report API `GET /report/meetings/{meetingId}`, merged with its participants endpoint |
| `BIGBLUEBUTTON` | `application/xml` | One `<recording>` element of BBB's `GET getRecordings` response |

**`ZOOM`** - JSON object (sample: `fixtures/media/zoom-meeting-metadata.sample.json`):

| Field | Meaning |
|---|---|
| `id` | Zoom meeting number - reused by a recurring meeting, so not unique |
| `uuid` | Unique id of this meeting occurrence → `source_ref` |
| `topic` | Meeting title |
| `user_email`, `user_name` | Host |
| `dept` | Host's department |
| `start_time`, `end_time` | ISO-8601 UTC → `start_time` is `source_timestamp` |
| `duration`, `total_minutes` | Meeting length / summed participant minutes |
| `participants_count`, `type` | Head count; Zoom meeting type code |
| `custom_keys[]`, `tracking_fields[]` | Account-defined key/value pairs |
| `participants[]` | Per participant: `id`, `user_id`, `participant_user_id`, `name` (→ `participants`), `user_email`, `join_time`, `leave_time`, `duration`, `status`, `failover`, `customer_key`, `registrant_id`, `bo_mtg_id` |

**`BIGBLUEBUTTON`** - XML (sample: `fixtures/media/bbb-metadata.sample.xml`):

| Element | Meaning |
|---|---|
| `recordID` | Unique id of this recording → `source_ref` |
| `meetingID` | Room id - reused by every session in the room, so not unique |
| `internalMeetingID` | BBB's internal id for the session |
| `name` | Meeting/room name |
| `startTime`, `endTime` | Epoch milliseconds → `startTime` is `source_timestamp` |
| `participants` | A head **count** only - BBB's public API has no per-person list, so `participants` on the event stays empty for BBB |
| `isBreakout`, `published`, `state` | Recording flags/status |
| `metadata` | Meeting metadata set by the integration (e.g. `meetingName`) |
| `playback/format[]` | Playback links per format (`type`, `url`, `processingTime`, `length`) |

**Known gaps (tracked in issue #45):**
- Test samples aren't yet drawn from the real public corpora named in the spec (AMI/ICSI,
  RVL-CDIP/FUNSD) - current samples are placeholder files.
- Still local-only: this lane proves against the local MinIO/Redpanda started via this repo's
  own `docker-compose.yml`, not a separately-hosted shared instance - see issue #45 for whether
  a separate shared environment is still needed now that local infra is shared across lanes.

## Tests

```bash
mvn test
```
