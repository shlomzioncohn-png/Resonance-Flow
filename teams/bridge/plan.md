# Unstructured-intake lane — plan

Design notes for Malki's lane (Gmail / Drive / Calendar / Slack / WhatsApp intake). Referenced
throughout the code (`FixtureSourceLoader`, `ChatSessionGrouper`, `AllowlistScopingFilter`,
`MinioObjectStorageClient`, `RawObjectEvent`) — this file is the source of truth those comments
point to.

## Current state (fixtures)

```
fixtures/unstructured/*.json|*.txt
        │  (ClassPathResource / ResourcePatternResolver)
        ▼
FixtureSourceLoader.load(RawSourceType)     ← the ONLY fixture-coupled class
        │  per-source parsing → List<SourceRecord>
        │  (Slack/WhatsApp go through ChatMessage → ChatSessionGrouper first)
        ▼
UnstructuredIntakeService.run(sourceType)    ← generic orchestrator, per record:
        ├─ AllowlistScopingFilter.decide()      → ScopeDecision (stub: always INCLUDED)
        ├─ ObjectKeyBuilder.build()              → object key
        ├─ IntakeLandingService.land()           → MinIO (real)
        └─ PointerEventPublisher.publish()       → RawObjectEvent → Kafka/Redpanda (real)
```

Triggered only by `POST /api/bridge/intake/{sourceType}/run` (`UnstructuredIntakeController`) —
synchronous, on demand, no scheduling. Everything from `UnstructuredIntakeService` downward is
already source-agnostic and talks to real infra (MinIO, Redpanda) — the fixture coupling lives
entirely inside `FixtureSourceLoader`.

## Target design: real sources

**Decision: keep the single orchestrator.** `UnstructuredIntakeService.run(RawSourceType)`
keeps its shape. We are *not* splitting into one `AbstractIntakeLane` per source the way the CRM
lane (`origin/ruth/crm-deal-mapper`) does — that pattern fits CRM because it's one source; this
lane serves 6 `RawSourceType`s behind one runtime-selected path deliberately, and staying with
one orchestrator keeps scoping/landing/publishing logic in one place instead of duplicated 5
times.

**The seam**: `FixtureSourceLoader` splits into a thin dispatcher (keeps the
`load(RawSourceType) -> List<SourceRecord>` contract) plus one reader interface per source
(`EmailSourceReader`, `DriveSourceReader`, `CalendarSourceReader`, `SlackSourceReader`,
`WhatsAppSourceReader`), each with:
- a `Fixture...` implementation — today's parsing logic, moved as-is from `FixtureSourceLoader`'s
  private methods (`loadEmail`, `loadDrive`, `loadCalendar`, `loadSlackMessages`,
  `loadWhatsAppMessages`), no behavior change.
- a `Real...` implementation, added later, one source at a time — calls the actual API and
  returns the same `SourceRecord`/`ChatMessage` shapes. No downstream code changes: `SourceRecord`
  and `ChatMessage` are already generic enough to carry real API data unchanged.

Which implementation is active is a per-source config flag
(`bridge.intake.<source>.mode: fixture|real` in `application.yml`), not a single global
fixture/real switch — the README already documents that fixture realism was chosen per source,
not uniformly, so sources go real independently as each is ready.

### Real reader specifics, per source

| Source | API | Pagination / incremental sync | Notes |
|---|---|---|---|
| Gmail | Gmail API, `users.messages.list`/`get` | `pageToken`; `historyId` for incremental | OAuth2 (user consent or service account + domain-wide delegation) |
| Calendar | Google Calendar API, `events.list` | `syncToken` | same OAuth as Gmail |
| Drive | Google Drive API, `files.list`/`changes.list` | `pageToken` / `startPageToken` | same OAuth as Gmail |
| Slack | Slack Web API, `conversations.history` | cursor-based | OAuth bot token |
| WhatsApp | **no official export API** for personal WhatsApp | n/a | only path today is a manual `.txt` export per chat — this source likely stays fixture/file-drop-based even after the others go real |

### WhatsApp: how the file actually gets to the server

There is no automated way to trigger a WhatsApp export — a person has to open the chat, use
WhatsApp's own **Export Chat** feature (Settings/⋮ → More → Export Chat, with or without
media), and the phone shares the resulting `.txt` (and media files) out through its own share
sheet. "Going real" for WhatsApp therefore doesn't mean polling an API — `RealWhatsAppSourceReader`
(implemented) just reads whatever `.txt` files are sitting in a configured directory
(`bridge.intake.whatsapp.import-dir`), the same export line format the fixtures already use,
via the shared `WhatsAppExportLineParser`. How the file gets *into* that directory is a
separate, still-open question — options, cheapest first:
1. Someone copies the file onto the server manually (what's implemented now — no automation).
2. A cloud-synced folder (Drive/Dropbox client running on the server) the phone shares into.
3. A dedicated upload endpoint on Bridge itself (`POST .../whatsapp/upload`, multipart) — more
   code, but keeps delivery inside the system instead of depending on an external sync client.

### Trigger: scheduled polling, not webhooks

Real sources need something to actually call `run(sourceType)` periodically — the manual HTTP
trigger stays for ad hoc/debug use, but the default becomes a `@Scheduled` poll (interval
configurable, e.g. every 5–15 minutes) that calls the same `run(sourceType)` path per source.

Considered and rejected for now: push/webhooks (Gmail Push via Google Pub/Sub, Slack Events
API) — near-real-time, but needs a publicly reachable endpoint plus subscription/renewal
management, which is meaningfully more infrastructure than this stage needs. Revisit only if
polling latency becomes an actual problem.

### No intermediate datastore for payloads

Fetched records go straight from the API response into the existing
scoping → land (MinIO) → publish (Kafka) sequence, exactly like fixtures do today — MinIO is
already the durable landing zone for raw content and Kafka is already the pointer-event stream,
so there is no reason to add a staging database in between. This applies per record/batch within
one poll run, same as it does for one fixture-driven HTTP call today.

### Cursor persistence

Incremental sync needs one small piece of state per `(tenantId, sourceType)`: the last
`historyId`/`syncToken`/`pageToken`. This is not the payload — just a token — so it doesn't need
a new datastore: store it as a small JSON object in MinIO (e.g.
`_cursors/{sourceType}/{tenantId}.json`), read at the start of a poll run, written back at the
end. `core.ReadCursor` already has a `since: Instant` field reserved for exactly this
(`core/ReadCursor.java`); only `ReadCursor.full()` is used today.

Update the cursor **only after** every record in the run has successfully landed and published —
never before. If a run crashes partway through, the next run re-reads from the old cursor and
may reprocess a few records; this is fine (at-least-once, not exactly-once) because
`RawObjectEvent.dedupKey()` is already a deterministic content hash — downstream (Mind)
de-duplicates on it, so occasional reprocessing is harmless. This is what keeps cursor handling
simple: it doesn't need to be perfectly exactly-once, just monotonic and crash-safe on the
"update last" side.

Revisit if/when multiple app instances could run the same poll concurrently (would need a lock
around cursor read+update) — out of scope while this runs as a single instance.

## What does NOT change

`ChatSessionGrouper`, `AllowlistScopingFilter`, `ObjectKeyBuilder`, `IntakeLandingService`,
`PointerEventPublisher`, the `SourceRecord`/`ChatMessage`/`RawObjectEvent` DTOs, the MinIO/Kafka
infra wiring, and the controller's endpoint shape all stay as they are. The blast radius of
going real is contained to the loader/reader layer plus the new scheduler and cursor-storage
pieces described above.

## Adjacent, separately tracked gaps

Not part of this change, but worth knowing about before/while going real, since real traffic
will make them matter more:
- `AllowlistScopingFilter` is currently a stub that always returns `INCLUDED`; real filtering
  against `ScopingProperties.allowedDomains`/`allowedKeywords` is unbuilt.
- `RawObjectValidator` exists (`raw/RawObjectValidator.java`) but isn't wired into
  `UnstructuredIntakeService`/`PointerEventPublisher` yet.

## Rollout order

Go real one source at a time via the per-source config flag, easiest first:
1. **WhatsApp** — no OAuth needed, but also no live API; likely stays a manual file-drop even
   once "real" (real export file instead of a fixture file).
2. **Slack** — one OAuth bot token, no per-user consent flow.
3. **Gmail / Calendar / Drive** — need a Google OAuth app + consent/service-account setup, more
   credential-management work before the first real call can be made.
