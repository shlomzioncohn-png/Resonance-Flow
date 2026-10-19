# ingestion

Mind's entry point (#349): reads what Bridge lands and turns it into text for
extraction. Bridge stores each item's files in MinIO and publishes one
`RawObjectEvent` on `bridge.raw.landed` carrying pointers, not content. This
package fetches the files, checks them, and turns each into text. It does not
interpret content — that is `extraction/` and `multimodal/`.

```
bridge.raw.landed ─► to_raw_object_event ─► classify_arrival ─► handle_raw_object_event ─► to_texts ─► on_texts
  (protobuf)          (pydantic contract)    (processed yet?)      │ reads MinIO              │ OCR/ASR    (extraction)
                                                                   ▼                          ▼
                                                           list[NormalizedRecord]     list[(record, text)]
                                                                                              │
                                                                              mark_processed ◄┘
```

| file | what it does |
|---|---|
| `landed_consumer_runner.py` | the process: Kafka consumer, real MinIO, `asr.warm_up()` |
| `landed_consumer.py` | `LandedHandler`: one message through every step below |
| `raw_object_event_proto.py` | the protobuf message → the `RawObjectEvent` contract |
| `seen_revisions.py` | whether an item was already processed, and at which revision |
| `raw_object_event_handler.py` | the event → one `NormalizedRecord` per file it points to |
| `minio_reader.py` | reads one file from MinIO |
| `normalized_record.py` | `NormalizedRecord`: one file, read and checked |
| `to_text.py` | the event's records → text, via R4's OCR and ASR |

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
3. **The files** (`handle_raw_object_event`). Nothing is read for an
   `EXCLUDED` item (Bridge judged it not work, and it is often private) or a
   `DELETE_REVIEW`. `SOURCE_METADATA` describes the item rather than being its
   content and is skipped. Every other file is read from MinIO and checked
   against its `content_hash`.
4. **Text** (`to_texts`), by `modality`: `TEXT` is decoded as UTF-8,
   `SCANNED_DOC` goes to R4's `ocr.extract_text`, `AUDIO` to `asr.transcribe`.
   When the event carries a `TRANSCRIPT`, its `RECORDING` is not transcribed;
   other audio in the event (an attached voice note) still is. A file with no
   text in it is skipped.
5. **Hand-off** (`on_texts(event, texts)`), then the item is marked processed.
   Not before: a crash in between processes the item again, and Inbox
   de-duplicates task requests on `correlation_id`.

## Skip or retry

`ProtobufConsumer` commits a message's offset only once the handler returns,
so each failure is one of two kinds:

| what happened | what happens |
|---|---|
| the contract rejects the message | skipped, logged at ERROR |
| `tenant_id` is not a UUID | skipped, logged at ERROR |
| duplicate, late, or updated revision | skipped, logged at INFO |
| a file is missing, or does not match its hash | that file skipped (WARNING), the rest goes on |
| OCR/ASR: the file is the problem (DATA), or our bug (BUG) | that file skipped (WARNING), the rest goes on |
| MinIO unreachable, or any MinIO error but a missing key | raised: the process stops, the message comes again |
| OCR/ASR could not run here (ENVIRONMENT) | `MediaUnavailable` raised, same |
| the hand-off fails | raised, same |

A skip is for what a retry cannot fix: the same message would fail the same
way on every redelivery and block everything behind it. ERROR, not WARNING,
because a contract change on Bridge's side sends every message there, and the
log is the only place it shows.

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
Kafka and the Schema Registry come from `messaging/config.py`.

Until extraction is wired in, `on_texts` logs each text (its item, file,
length and first characters), which also shows the stage in a demo run.

## Not yet in

- **The hand-off to extraction (#350).** `on_texts` only logs today.
- **Bridge updates.** A newer revision is skipped. Acting on it means finding
  and updating the tasks the earlier revision produced, a feature of its own.
  It also means an item first `EXCLUDED`, then re-classified `INCLUDED` in a
  later revision, is not processed.
- **Per-file retry.** An item with one bad file is still marked processed, so
  that file is never retried. Suggested: track files, not items, so only the
  failed one is tried again.
- **A persistent revision store.** `InMemoryRevisionStore` forgets everything
  on restart; a persistent one only needs `RevisionStore`'s two methods.
- **A dead-letter queue.** OCR/ASR `BUG` failures should be parked, not
  dropped; there is no such queue yet, so they are skipped and logged.
- **An unreadable transcript.** When the event's transcript cannot be read,
  its recording is still not transcribed, and the meeting yields no text. Rare
  (the transcript comes from Bridge or Zoom, not a user upload); logged.
- **Credentials in the cluster.** Helm mounts them as files under
  `/etc/resonance-flow/secrets/object-storage/`; this reads environment
  variables only.
- **Long items and Kafka's poll interval.** One transcription is capped at
  240 s (`TRANSCRIBE_TIMEOUT_S`), under Kafka's default 5 minutes between
  polls. An event with two audio files to transcribe can exceed it, and Kafka
  would then hand the message on as if the consumer had died. The interval is
  not configurable per consumer in `messaging/config.py` today.

## Open with Bridge and Inbox

- **`tenant_id`.** Bridge's Slack and media lanes send `kamatech` /
  `demo-tenant`. Mind passes the tenant to Inbox, which requires the UUID of a
  tenant it has (`@ValidUuid`, then a lookup), so every such item is skipped
  here with an ERROR. Bridge's CRM lane already uses a UUID-shaped
  placeholder; asked the same of the other lanes, and Inbox which tenant to
  use for the demo.

## Running the tests

```
pytest tests/ingestion -q
```

No Kafka, MinIO, Whisper or tesseract needed: the handler, the reader and
`to_texts` take their collaborators as arguments, and the tests pass fakes.
