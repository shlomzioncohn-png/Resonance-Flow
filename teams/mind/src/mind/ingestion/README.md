# ingestion

Mind's entry point (#349): reads what Bridge lands and turns it into text for
extraction. Also loads AMI's human transcripts for the extraction eval (see
"AMI transcripts" at the end). Bridge stores each item's files in MinIO and publishes one
`RawObjectEvent` on `bridge.raw.landed` carrying pointers, not content. This
package fetches the files one at a time, checks them, turns each into text,
and joins the texts into one `ExtractionItem` per item (text, task source type,
`correlation_id`). It does not interpret content — that is `extraction/` and
`multimodal/`.

```
bridge.raw.landed ─► to_raw_object_event ─► classify_arrival ─► extraction_items ─────────► on_items
  (protobuf)          (pydantic contract)    (processed yet?)     │ per file, one at a time:   (extraction)
                                                                  │ read MinIO, check hash,       │
                                                                  │ text (OCR/ASR), let go        ▼
                                                                  │ then join the texts      mark_processed
                                                                  ▼
                                                     one ExtractionItem per item
```

| file | what it does |
|---|---|
| `landed_consumer_runner.py` | the process: Kafka consumer, real MinIO, `asr.warm_up()` |
| `landed_consumer.py` | `LandedHandler`: one message through every step below |
| `raw_object_event_proto.py` | the protobuf message → the `RawObjectEvent` contract |
| `seen_revisions.py` | whether an item was already processed, and at which revision |
| `extraction_items.py` | the event → one `ExtractionItem` for the item, read one file at a time |
| `event_objects.py` | the reader contract and per-file checks (tenant, `content_hash`) |
| `minio_reader.py` | reads one file from MinIO |
| `to_text.py` | one file's bytes → text, via R4's OCR and ASR |

## How a message travels

1. **Protobuf to the contract** (`to_raw_object_event`). Bridge publishes in
   Protobuf through the Schema Registry, enum values prefixed
   (`ROLE_BODY`); the contract has plain names (`BODY`). The converter only
   handles the wire's quirks — prefixes, and proto3's lack of null (an empty
   string, an empty list or an unset field becomes `None`; a `revision` of 0
   stays 0). Whether the message is valid is the contract's decision.
2. **Processed yet?** (`classify_arrival`). An item is its `correlation_id`
   and is processed once, at the first revision that arrives. The same
   revision again is a duplicate, an older one arrived late, a newer one is
   Bridge updating the item. All three are skipped and logged.
3. **Files to items** (`extraction_items`), one file at a time: read from
   MinIO, checked against its `content_hash`, turned into text, and let go
   before the next is read, so memory holds one file, not the event. Nothing
   is read for an `EXCLUDED` item (Bridge judged it not work, and it is often
   private) or a `DELETE_REVIEW`; `SOURCE_METADATA` describes the item rather
   than being its content and is skipped. Text by `modality` (`to_text.text_of`):
   `TEXT` decoded as UTF-8, `SCANNED_DOC` to R4's `ocr.extract_text`, `AUDIO`
   to `asr.transcribe`. Transcripts go first: the `RECORDING` is skipped, and
   never read, only when a transcript gave speech; a missing, broken or empty
   one (a WEBVTT with timings and no words) leaves the recording to be
   transcribed. Other audio (an attached voice note) is always transcribed. A
   file with no text in it is skipped.

   The texts left are joined into **one** `ExtractionItem` for the item, in
   the event's order (the transcript is read first, but keeps its place), keyed
   by `correlation_id` and carrying the task's source type (ZOOM /
   BIGBLUEBUTTON → MEETING until Inbox's values reach shared/proto). When the
   event landed more than one content file, each part goes under a heading of
   what it is and how it became text — `[Body]`, `[Transcript]`,
   `[Attachment: form.jpg, scanned]`, `[Recording, transcribed]` — even if only
   one part is left; an item of one file goes as it is. No readable file, no
   item.

   One item, not one per file, because the item is the unit of work: an email
   and its scanned form are one request, a deadline in one and the work in the
   other make one task, not two half-tasks. Per file, the same request in a
   body and its attachment would also become two tasks, and R3's
   `find_duplicate` cannot catch that: it compares against tasks already in the
   index, and these two are created together. Extraction cuts only an item
   past `MAX_CONTENT_CHARS` (100,000, `extraction/prompts.py`), one cap for
   every source, so a long body does not cost the attachment after it.
4. **Hand-off** (`on_items(event, items)`, the list empty or of one), then the
   item is marked processed. The event travels next to the item: it carries
   the tenant, `source_ref` and `correlation_id` extraction needs. Marked only
   after the hand-off: a crash in between processes the item again, and Inbox
   de-duplicates task requests on `correlation_id`.

## Skip or retry

`ProtobufConsumer` commits a message's offset only once the handler returns,
so each failure is one of two kinds:

| what happened | what happens |
|---|---|
| the contract rejects the message, an enum number Mind's pb2 lacks, or a time out of range | `InvalidEvent`: skipped, logged at ERROR |
| an unknown quality or sensitivity number | not skipped: the contract's own rule applies (quality ignored, sensitivity HIGH) |
| `tenant_id` is not a UUID | `InvalidEvent`: skipped, logged at ERROR |
| duplicate, late, or updated revision | skipped, logged at INFO |
| a file is missing, or does not match its hash | that file skipped (WARNING), the rest goes on |
| a file is over `max_object_bytes` | that file skipped (ERROR), the rest goes on |
| OCR/ASR: the file is the problem (DATA) | that file skipped (WARNING), the rest goes on |
| OCR/ASR: the tool ran and failed (`*_FAILED`) | tried once more; if it fails again, that file skipped (ERROR) |
| OCR/ASR: our bug (BUG), or a reason with no decision yet | that file skipped (ERROR), the rest goes on |
| OCR/ASR: the tool is missing here (`*_UNAVAILABLE`) | `MediaUnavailable` raised: the process stops, the message comes again |
| MinIO unreachable, or any MinIO error but a missing key | raised, same |
| the hand-off fails | raised, same |
| any other error (a bug of ours) | raised, same: loud rather than lost |

A skip is for what a retry cannot fix: the same message would fail the same
way on every redelivery and block everything behind it. A retry here has no
bound (it is a restart on the same message), so only a failure the
environment, not the item, can cause is retried. ERROR, not WARNING, where a
skip may hide a problem of ours or the host's: a contract change on Bridge's
side sends every message there, and the log is the only place it shows.

Only `InvalidEvent` is skipped, never a bare `ValueError`: it is raised on
purpose where Bridge's input is checked, so a bug of ours is not taken for
Bridge's fault. Which OCR/ASR reason is retried is listed in `to_text.py`
(`RETRY_REASONS` / `SKIP_REASONS`); the reasons are R4's, and a test fails
until a new one is placed in exactly one of them. One that ships before the
test has run is skipped with an "undecided failure reason" ERROR.

Only `NoSuchKey` counts as a missing file, the same line Bridge's own
`MinioObjectStorageClient` draws. A wrong bucket or an outage would otherwise
be skipped file by file, and every item landed meanwhile lost.

## Running it

```
MIND_OBJECT_STORAGE__ACCESS_KEY=... MIND_OBJECT_STORAGE__SECRET_KEY=... make run-landed-consumer
```

`MIND_OBJECT_STORAGE__ENDPOINT` (default `http://localhost:9000`) and
`MIND_OBJECT_STORAGE__BUCKET` (default `bridge-raw`) match Bridge's local
defaults. The credentials have no default, so a missing one fails at start-up.
In the cluster nothing needs setting: endpoint and bucket come from the
chart's `config.yaml` (`object_storage`, at `$CONFIG_FILE_PATH`) and each
credential from its file under `/etc/resonance-flow/secrets/object-storage/`.
An environment variable still wins over both.
`MIND_OBJECT_STORAGE__MAX_OBJECT_BYTES` (default 100 MB) caps what is read: at
most one byte past it is fetched, whatever the event's `size_bytes` says, and
the read itself stops there even if the server ignores the Range header.
Kafka and the Schema Registry come from `messaging/config.py`; this consumer
alone raises `max.poll.interval.ms` to 30 minutes (`conf_overrides`). Since
#481 that means a `config.yaml` is required here too: set `CONFIG_FILE_PATH`
to a file whose `kafka.bootstrap_servers` / `kafka.schema_registry_url` point
at your broker, or the consumer stops at start-up with a `ConfigFileError`
(see `src/mind/api/README.md`, "Configuration (#481)"). The object-storage
settings above are unchanged by #481. No
database is needed: the runner reads `LandedConsumerSettings` (group id,
`MIND_LOG_LEVEL`), not `Settings`, which requires the vector-store DSN.

Until extraction is wired in, `on_items` logs each item by its `correlation_id`
and length, never its content (Bridge may have classified it HIGH sensitivity),
which also shows the stage in a demo run.

**A run commits offsets.** The consumer group is `mind-landed-consumer`. Until
extraction is wired, a run marks items processed and commits their offsets
with nothing extracted, so a later run in the same group never sees them
again. For a test against the shared topic (the joint test with Bridge, a
demo rehearsal), use a group of its own, in the environment or in `.env`:

```
MIND_LANDED_CONSUMER__GROUP_ID=mind-landed-joint-test make run-landed-consumer
```

## Not yet in

- **The hand-off to extraction (#350).** `on_items` only logs today.
- **Bridge updates.** A newer revision is skipped. Acting on it means finding
  and updating the tasks the earlier revision produced, a feature of its own.
  It also means an item first `EXCLUDED`, then re-classified `INCLUDED` in a
  later revision, is not processed.
- **Per-file retry.** An item with one bad file is still marked processed, so
  that file is never retried. Suggested: track files, not items, so only the
  failed one is tried again.
- **A long item with tasks in more than one part.** Measured live
  (`gemini-3.6-flash`) on two AMI meetings joined into one item of 61k
  characters: alone they find 4/4 and 4/8 of the gold action items; joined,
  IS1008b first, 3/12 and 5/12. The loss is the first part's items that are
  only mentioned briefly (IS1008b's closing "individual actions" slide): with
  IB4003 first they are found again, 7/12 and 8/12. Not the parts paragraph
  in `system.md` (without it, 4/12 twice), and a "read every part to its end"
  line with numbered headings did not help (2/12, 5/12). A real item is
  rarely two task-dense parts, so it is left as is. If it shows up: extract
  each part on its own, with the rest of the item as context.
- **Which file failed extraction.** The item is extracted as one text, so a
  failed extraction names the item (`correlation_id`), not the file in it
  that caused the failure.
- **A persistent, shared revision store.** `InMemoryRevisionStore` forgets
  everything on restart, keeps only the 100,000 most recently processed items
  (flat memory for a consumer that runs for weeks), and belongs to one process.
  With more than one replica, or when a rebalance moves a partition mid-item,
  two instances can process the same item and hand it on twice: Inbox drops
  the second request (correlation_id), but extraction pays for it twice. A
  persistent store shared by all replicas only needs `RevisionStore`'s two
  methods.
- **Work redone after a retry.** When an event stops on a retry (MinIO down,
  `MediaUnavailable`), what was already OCR'd or transcribed for its earlier
  files is lost, and every restart does it again until the cause is fixed.
  Keeping partial results across restarts is not worth it yet.
- **A dead-letter queue.** OCR/ASR `BUG` failures should be parked, not
  dropped; there is no such queue yet, so they are skipped and logged.
- **An event that takes over 30 minutes.** OCR is capped at 200 s per file
  and ASR at 240 s, and the poll interval is raised to 30 minutes, but an
  event with many long media files (about five 10-page scans that each fail
  and are tried twice, or ten that do not) can still exceed
  it; Kafka then hands the message on as if the consumer had died, again and
  again. If it happens: cap the media files processed per event.
- **Large video.** A file over `max_object_bytes` (100 MB) is skipped, so a
  long Zoom video recording yields no text in production. Audio-only
  recordings of an hour fit. Raising the cap runs into the pod's memory
  (Helm: 512Mi), which Whisper's ~2 GB peak already exceeds; to raise with R4
  and Relay.
- **`size_bytes` is not used to skip early.** An object the event already
  reports as over `max_object_bytes` is still fetched up to the cap before it
  is skipped: up to 100 MB of download, once per such item. Skipping on
  `size_bytes` first needs the handler to know the reader's limit.
- **A message that cannot be deserialized** (not Bridge's schema, a broken
  frame) fails inside the shared `ProtobufConsumer.run`, before this handler,
  and stops every consumer of Mind on it the same way. To fix there, for all
  consumers: log and commit past it.
- **An S3 error on one key only.** `AccessDenied` on a single object cannot be
  told from wrong credentials for the whole bucket, so it is retried, and that
  one object stops the topic until it is readable or removed.
- **ASR missing stops everything.** `ASR_UNAVAILABLE` is retried so recordings
  are not lost, which means a pod without Whisper also stops the text and OCR
  items behind the first recording. An environment to fix, not a skip.

## Open in Mind

- **HIGH-sensitivity content.** Bridge's `classification` is converted but not
  read yet: every `INCLUDED` item's text is handed on, and once extraction is
  wired it reaches the external LLM. Whether HIGH-sensitivity content may be
  sent there, and what to do with `UNREADABLE` quality, are decisions not
  taken yet.

## Open with Bridge and Inbox

- **`tenant_id`.** Bridge's Slack and media lanes send `kamatech` /
  `demo-tenant`. Mind passes the tenant to Inbox, which requires the UUID of a
  tenant it has (`@ValidUuid`, then a lookup), so every such item is skipped
  here with an ERROR. Bridge's CRM lane already uses a UUID-shaped
  placeholder; asked the same of the other lanes. Inbox has no demo tenant
  yet: the only one is System, `00000000-0000-0000-0000-000000000001`, until
  they decide on a fixed demo UUID.

## Running the tests

```
pytest tests/ingestion -q
```

No Kafka, MinIO, Whisper or tesseract needed: the handler, the reader and
`extraction_items` take their collaborators as arguments, and the tests pass fakes.

See `MIND-01-System-Overview.md` (§2, §7) for the source list and the real-vs-synthetic data policy.

## AMI transcripts (`ami_transcripts.py`)

AMI meetings as readable text, for the extraction eval (#178) and prompt tuning (#690).

- **Reads** the meetings in `eval/datasets/ami.manifest.json`, each file checksum-verified
  against `ami.sha256`. From AMI's manual annotations, for every speaker:
  `words/<meeting>.<letter>.words.xml` (the words) and
  `segments/<meeting>.<letter>.segments.xml` (the transcriber's segments, each a coherent
  stretch of that speaker's words with a start time); and `corpusResources/meetings.xml`
  for the speakers' roles. The corpus is gitignored — unzip the manifest's package under
  `local-data/ami/`.
- **Output:** `AMITranscript(meeting_id, text)`, from `load_transcript(meeting_id)` or
  `load_all_transcripts()`. `meeting_id` is the `abssumm.xml` filename stem, same case
  (`IS1008b`) — the eval joins on it. `text` has one line per speaker turn (from IS1008d):

  ```
  Industrial Designer: I'm sorry to be late.
  Project Manager: Welcome back. Welcome back everybody.
  ```

  The segments of all speakers are sorted by start time, and consecutive segments of the
  same speaker are one line. Punctuation is kept; the non-word markers (`<vocalsound>`,
  `<disfmarker>`, `<gap>`) are dropped; fillers ("uh", "um") and truncated words stay, as
  transcribed. Speakers are named by role (Project Manager, Industrial Designer, User
  Interface Designer, Marketing Expert) when `meetings.xml` gives one — the AMI gold action
  items name people that way — and by letter (`A`–`D`) when it doesn't, as in the
  non-scenario meeting IB4003.

  **Overlapping speech:** the order is by segment, not by word, so a sentence stays whole
  when someone else speaks during it — a "Yeah" said mid-sentence comes on its own line
  after that sentence, not inside it. (Merging word by word instead cut about half the
  turns of a meeting into fragments: "I'm / Welcome / sorry / back.") No words are lost:
  every word of the words layer is in exactly one segment.
- **Why the human transcript, not ASR:** so the extraction eval measures extraction only —
  ASR errors don't show up as extraction errors. ASR quality is measured on its own (#480,
  `benchmarks/asr-ami-validation.md`).
