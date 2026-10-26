# demo_flow: the #359 end-to-end demo

[#359](https://github.com/KamaTechOrg/Resonance-Flow/issues/359): one realistic scenario, a
meeting recording, a Slack thread and an email with a scanned form, all about the same piece
of work, run through the real pipeline path and checked against an answer written down
before any run.

```bash
python -m demo_flow.run                        # natural order (make demo-flow)
python -m demo_flow.run --order slack-first    # Slack before the meeting (make demo-flow-slack-first)
```

One command brings up a clean stack, starts Mind, lands the three items in MinIO with one
pointer event each on `bridge.raw.landed`, and prints PASS/FAIL per acceptance criterion.
Nothing is called from Python directly: every item goes through Kafka, MinIO, Mind's
consumer, Inbox and back. [Latest results](#latest-results): **not all criteria pass yet**,
and the reasons are findings with owners, below.

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

From `teams/mind`, with the venv active and `pip install -e ".[dev]"`. On Windows there is
no `make`: run the `python -m` commands.

**Once, before the first run:**

1. **Docker** running (Docker Desktop on Windows).
2. **`demo_flow/stack/secrets.yaml`**: copy `secrets.example.yaml` next to it and put an LLM
   key in `llm.gemini.api_key`. It is gitignored. Use a **paid-tier** key: on the free tier
   a full run hits the daily quota (429) and 503s part-way, and items are lost.
3. **`tesseract`** installed (on Windows the run finds `C:\Program Files\Tesseract-OCR` on
   its own).
4. **Model weights** under `models/` (`models/README.md`): Whisper `base.en` and the
   embedding model `bge-small-en-v1.5`. Both load from there, never from the Hugging Face
   Hub, which the dev network's filter blocks (418).

`python -m demo_flow.run` checks all four in its first stage and stops with a sentence
naming what is missing.

**What a run does**, one `STAGE` line each (`run.py`):

| Stage | What happens |
|---|---|
| 1 preflight | Docker, the LLM key, tesseract, the embedding model |
| 2 stack | `docker compose down -v`, then up (`stack/docker-compose.yml`): Redpanda, Apicurio registry, MinIO, Inbox built from `teams/inbox`, pgvector. Empty on every run |
| 3 topics, schemas, migrations | the topics every party reads; every schema registered by **Relay's own scripts** (`stack/seed-schemas.sh` builds Relay's `schema-canonicalize` jar and runs `teams/relay/ci/seed-registry.sh`, so every schema is registered in its canonical form, as in the cluster; the first run fetches the jar's Maven dependencies into the `mind-demo-m2` volume, which later runs keep); Mind's vector-store migrations |
| 4 Mind | the landed runner (`mind.ingestion.landed_consumer_runner`) and Mind's API on port 8800, which runs the score publisher (#920), as processes on this machine, their output prefixed `[mind-landed]` / `[mind-api]` |
| 5 feed | `demo_flow.feed` lands the three items and publishes their events, in the order asked for |
| 6 result | `verify.py` reads `task.creation.requested`, `task.created`, `task.score.proposed` and `task.dependencies.proposed` until everything expected has come out or nothing has moved for 120 s, then prints one line per criterion |

A run takes 6 to 8 minutes. The stack is left up afterwards (Redpanda Console
<http://localhost:8090>, MinIO <http://localhost:9091>, `minioadmin`/`minioadmin`);
`python -m demo_flow.run --down-only` takes it down. Each run keeps its logs and result in
`runs/<run-id>/` (gitignored): `mind-landed.log`, `mind-api.log`, `result.txt`, next to
the feed's manifest `runs/<run-id>.json`.

A Mind process that stops on an error is started again, up to 3 times, as the cluster's
`restart: on-failure` would: since #919 an LLM outage stops the landed runner on purpose, so
its message is redelivered rather than lost.

`python -m demo_flow.run --help` lists the options (`--order`, `--gap`, `--settle`,
`--timeout`, `--down`).

### How the result is checked

`verify.py` traces each creation request to its source item by `correlation_id` (Mind derives
it from Bridge's and the task's number, `task_index_for`) and to T1-T3 by its title. Then:

| Criterion | Passes when |
|---|---|
| three tasks come out, the expected ones | exactly T1, T2 and T3 were requested, nothing else |
| Inbox created every task Mind requested | each request has a `task.created` with its `correlation_id` |
| T1 is one task, not 2 | one request for T1, **and** for every other mention the landed runner's log has a duplicate line whose **title is T1's**, merged into that same Inbox task. A merged duplicate sends nothing, so one request alone is not proof (a mention whose extraction failed sends nothing either), and a line alone is not proof either: it says *some* task of the item was merged. Another expected task merged into T1 (T2 above the 0.761 threshold) fails it as a false duplicate. The title shows in the line because the feed classifies the synthetic items as not sensitive; a withheld title is "not proven" |
| T1 depends on T2 | a `task.dependencies.proposed` from T1's Inbox id to T2's |
| tasks carry the fields | tier, urgency, `role_context` on each; a suggested owner and effort where `expected.json` has one. The checker also asks for the deadline, which #359 does not list: read a deadline-only FAIL as information |
| every created task gets a score | a `task.score.proposed` for each created task |

### Building and checking the inputs alone

```bash
make demo-artifacts                 # builds artifacts/meeting.m4a and artifacts/supplier_form.jpg
make demo-check-media               # real OCR + ASR on them, on their own; fails on a missing phrase
make demo-feed ARGS="--dry-run"     # build + validate the three events; no infra needed
```

- **`make demo-artifacts`:** the form is drawn with Pillow and builds on any OS, giving the
  same image every time. The audio is read by the Windows speech synthesizer, so it builds
  **on Windows only**. Anywhere else, use the committed copy, or record
  `scenario/meeting_script.txt` yourself in any format FFmpeg reads.
- **`make demo-check-media`:** needs `tesseract` and the Whisper `base.en` weights.

## Latest results

2026-10-25, branch `michal-brecher/mind-demo-flow-359` merged with `main` at `7f8e5b40`
(#690 task lists, #834 dependency publisher, #841 tenant-scoped index, #920 score publisher
in the API, #953/#994 canonical schemas, #968 one item per Bridge item), paid Gemini key,
`gemini-3.6-flash`. Runs `20261025T145408Z-natural` and `20261025T150200Z-slack-first`. All
six stages run in both, from a git worktree too.

| Criterion | natural | slack-first |
|---|---|---|
| three tasks come out, the expected ones | FAIL: 4 requested, 3 created | **PASS**: T1, T2, T3 |
| Inbox created every task Mind requested | FAIL: the meeting's T1 | **PASS** |
| T1 is one task, not 2 (meeting + Slack) | FAIL: 2 requests, one from each | **PASS**: sent from Slack; the meeting's 'Send Northwind the price quote' matched it (similarity 0.932) |
| T1 depends on T2 | FAIL: no message | FAIL: no message |
| tasks carry the fields | FAIL: no owner, no deadline; T2 no `role_context` | FAIL: same |
| every created task gets a score | **PASS** (3) | **PASS** (3) |

**What each stage did** (natural):

| Stage | What happened | Component |
|---|---|---|
| landed | three items, five objects in MinIO, three events on `bridge.raw.landed` | the feed (as Bridge) |
| transcribed | 67.5 s of audio in 14 s (`rtf=0.205`), 776 chars | R4's ASR, Whisper `base.en` |
| OCR | the scanned form in 1.6 s, 540 chars | R4's OCR, tesseract |
| extracted | the meeting: **T2 and T1** (#690); Slack: T1; the email, its body and the form read as one item (#968, 1,050 chars): T3 | extraction (#690), classification and effort (#350-#352) |
| deduplicated | slack-first: the meeting's T1 matched Slack's (0.957), not sent | R3's duplicate detection (#471, #841) |
| created | `task.creation.requested` to Inbox, `task.created` back, 1.4-5.4 s each | Mind's creation flow (#353, #919), Inbox |
| scored | `task.score.proposed` for each created task | the score publisher (#357; since #920 inside Mind's API) |
| dependency | **none published**: see findings 1 and 2 | #834/#355 |

T2 came out with the expected effort, 240 minutes (expected 180-300).

**Not shown end to end yet: the T1 -> T2 dependency (#834).** Two seams in Mind kept it from
ever being published, both fixed in this branch (see "Fixed in this branch"): the runner did
not pass #834's adapter, and the pipeline did not hand the extracted `dependency_mentions` to
the flow, so the adapter would have read an empty list for every task. With both fixed, the
mentions reach the adapter (`tests/ingestion/test_creation_pipeline.py`), but in neither run
did the task carrying the mention reach Inbox and come back: in natural the meeting's T1 was
dropped by Inbox (finding 1), in slack-first it was merged into Slack's T1 and its mention
went with it (finding 2).

## What the feed sends

The feed stands in for Bridge, so it rebuilds exactly what Bridge's own lanes land
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
| `classification` | `SENSITIVITY_NONE`, `DECIDED_BY_RULE` (`feed.DEMO_CLASSIFICATION`): the content is synthetic. Bridge's classifier is off by default; this is what its rules would say, and it lets Mind's duplicate line quote the task's title |

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
  `feed.mind_view`). The consumer does the same, because Mind's model uses the bare values. A rejection is logged as a warning, not raised: the wire format is Bridge's, and
  a mismatch is a finding about the contract. All three events pass today.

### Feed options

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

Each run starts on an empty stack (`down -v`, then up): an empty broker, registry, bucket,
Inbox database and vector store. So a run never meets the last run's tasks, and the same
command gives the same flow every time. Content comes out of an LLM, so titles and
classifications may differ between runs; the checker matches tasks by the words that name
the work, not by exact titles.

## Findings

From the runs above. Each names its owner and is reported to them rather than worked around
in Mind. Numbered by how much they block the demo.

### Open, blocking the expected answer

1. **Inbox keeps one task per `source_ref`, and drops the rest without an answer (Inbox,
   #353 contract).** `TaskService.createTask` returns the existing task when one already has
   the same `(tenant, source_type, source_ref)` (`findExisting`,
   `uq_tasks_org_source_type_source_ref`), and publishes no `task.created`. Since #690 Mind
   sends one request per task, each with its own `correlation_id` but all with the source
   item's `source_ref`, so every task after the first from a meeting or an email is lost.
   In the natural run it took the meeting's T1, so Slack's T1 found nothing to match and was
   created as a second T1, and T1's dependency mention went with it. Focused repro, no LLM,
   30 s, on a running stack: `python -m demo_flow.repro_inbox_source_ref` (two requests, one
   `source_ref`: one task created, the other never answered). Mind does not work around it:
   `source_ref` is what Inbox's UI builds the source link from. Suggested: key Mind's
   requests on `correlation_id`, which is unique per task.
2. **A merged duplicate's dependency mentions are lost (R3, #471/#355).** A task found to be
   a duplicate is not sent, and nothing carries its `dependency_mentions` to the task it
   matched. In slack-first, Slack's T1 ("get our pricing proposal over") was created first;
   the meeting's T1, the one that says "once I have the updated cost sheet", was merged into
   it, and the T1 -> T2 edge with it. So slack-first cannot show the dependency even once
   finding 1 is fixed.
3. **No suggested owner (#132).** No request carries `suggested_owner_user_id`. Dana and Omer
   are also not Inbox users, so even with #132 there is no one to map them to: the demo needs
   users seeded in Inbox.
4. **No deadline on any task (week 8).** The extraction prompt says "Never output priority,
   owner or deadline. Other stages handle those", and no stage does. "By Thursday" is in
   every source. #359 does not list the deadline, so this is a gap for week 8, not a FAIL of
   #359.

### Open, other

5. **Two tasks close together are not checked against each other (Mind).** The duplicate
   check only sees tasks Inbox has already confirmed, so two mentions of one task that
   reach it seconds apart are both sent. Since #968 an email's body and attachment are one
   item and yield one T3, so the demo no longer shows it within one event; it remains for
   items landed close together. The ingestion README's "Duplicates close together".
6. **T2 never gets a `role_context` (R2, classification).** In both runs the model returned
   tier, urgency and effort for "Finish the updated cost sheet" but no `role_context`.
   Repeatable on this scenario.
7. **The email body is the fixture JSON with the scan inline as base64 (Bridge).** 762,183
   characters of body for a 571 KB scan; in Gmail mode BODY is Gmail's whole `Message`
   instead. Since #968 Mind reads the body as Bridge lands it (`ingestion/email_body.py`):
   the email reaches extraction as 1,050 characters, body and form together.
8. **A retry redoes the media work (Mind, known).** After an LLM outage stops the runner, the
   redelivered meeting is transcribed again: 43-50 CPU seconds per restart, three times in
   one run on the free tier.
9. **The free Gemini tier cannot carry a run (environment).** 503s and the daily quota (429)
   stopped extraction in four runs on 2026-10-22. With the paid key, none.
10. **The give-up log line counts schema attempts, not calls (R2).** "giving up ... after 0
    attempt(s)" after three provider calls.
11. **`index_consumer_runner.py` cannot open the vector store on Windows (Mind).** Same
    `new_event_loop()` as the fix below; not part of the demo, so left as it is.
12. **Bridge's local MinIO doesn't start (Bridge).** `teams/bridge/docker-compose.yml` uses
    `minio/minio:latest`, which Docker Hub refuses; the demo stack uses `bitnamilegacy/minio`,
    as the Helm chart does. Bridge's README also gives the console port as 9001; the compose
    file maps 9091.
13. **Relay's registered copy of `raw-object-event.proto` predated #823, and Bridge's own
    Protobuf publish failed against its local registry with 40403 (Bridge/Relay).** Measured
    2026-10-19, not re-checked since; the demo registers from `shared/proto` with Relay's
    scripts and is not affected.
14. **Bridge's media lane sends a tenant that is not a UUID (Bridge).**
    `BRIDGE_MEDIA_TENANT_ID=demo-tenant` in `teams/bridge/.env.example`; Mind skips such
    events. The feed uses Inbox's System tenant.

### Fixed in this branch

- **The landed runner could not open the vector store on Windows.** psycopg's async mode
  rejects Windows' default `ProactorEventLoop`; the runner now opens `SelectorEventLoop`s, as
  `tests/conftest.py` does. No change on Linux.
- **No dependency was ever published end to end.** Two seams, both in the creation pipeline
  (#919). #834 merged `DependencyAdapter`, but the landed runner still passed its placeholder
  no-op `on_matched`; it now passes the adapter, as `dependencies/README.md` shows. And
  `CreationPipeline.handle` sent each request without the task's `dependency_mentions`
  (extraction's `ExtractedFields`, which travel beside the contract, not in it), so the
  pending entry the adapter reads always held an empty list; it now passes them to `send()`.

- **The duplicate check passed on a line that did not say which task was merged.** The
  log withheld the title, and the checker took any task of the item merged into T1 as
  proof, so a T2 merged into T1 above the 0.761 threshold would have passed (found in
  review). The feed now classifies the synthetic items as not sensitive, so the line quotes
  the title, and the checker requires T1's title and fails a false duplicate.
- **The demo's own tooling on Windows and in a worktree.** Registering the schemas cloned
  the repository inside a container, which fails from a git worktree (its `.git` names the
  main repository by a host path); and a line Mind printed that the console's code page
  could not show stopped the thread that writes the run's log.
- **#920 moved the score publisher into Mind's API.** The demo now starts the API (port
  8800) and waits for its `/health`.

### Resolved since the inputs were written (2026-10-19)

- **No `bridge.raw.landed` consumer (#349, #919).** Mind's landed runner consumes it and runs
  the creation pipeline.
- **The enum prefixes.** The consumer strips them (`raw_object_event_proto.py`).
- **The feed registered its own schema version.** Since #899 Mind's producers only look
  schemas up; the demo registers them centrally with Relay's scripts.
- **Not repeatable.** Every run starts on an empty stack.
- **Inbox did not start (duplicate V15 migration).** Fixed on `main` by #944.
- **The tenant was not a UUID.** #946: Inbox's System tenant.
- **One task per text (#690).** The meeting yields T1 and T2.

### Week 8

Gaps this demo shows that are capabilities, not repairs: deadline extraction (finding 4),
owner resolution with seeded users (3), carrying a merged duplicate's dependency mentions
(2), duplicates within one event (5), and a demo-scoped reset so the demo can run on a shared
stack instead of a fresh one.

## Files

| Path | In Git | What |
|---|---|---|
| `scenario/` | yes | sources: meeting script, Zoom meeting report, Slack thread, email, form text |
| `expected.json` | yes | the expected answer |
| `run.py` | yes | the one command: stack, Mind, feed, result |
| `verify.py` | yes | reads the output topics and the landed runner's log, checks them against `expected.json` |
| `repro_inbox_source_ref.py` | yes | finding 1, on its own |
| `stack/` | yes, except `secrets.yaml` | the demo stack's compose file, Mind's `config.yaml`, `secrets.example.yaml`, `seed-schemas.sh` |
| `common.py`, `build_artifacts.py`, `check_media.py`, `feed.py` | yes | the inputs' tooling |
| `artifacts/` | yes | `meeting.m4a` (326 KB), `supplier_form.jpg` (558 KB), built from `scenario/` |
| `runs/` | no (gitignored) | per run: the feed's manifest, the Mind processes' logs, `result.txt` |

The two built artifacts are committed because the audio can only be built on Windows, and
anyone running the demo on macOS or Linux needs it. Rebuild them with `make demo-artifacts`
after editing `scenario/`, then run `make demo-check-media`.
