# RawObjectEvent `source_ref` / `objects` — Mind's written response

This is the doc several code comments and ROADMAP.md already reference as "Mind's written
response" — it didn't exist as a file yet; created now to stop pointing at a dead link, and to
record Mind's actual answers so they don't have to be re-asked.

## `DELETE_REVIEW` must carry an empty `objects` list

Confirmed by Mind: this "matches our calendar lane's existing behavior" — `DELETE_REVIEW` means
the logical item no longer exists, so there is nothing to land a ref for, and the record's own
sparse/filler content must never be landed or referenced. Enforced generically (by `changeType`,
no per-source branching) in `UnstructuredPayloadMapper` and `SlackSessionPublisher`; validated by
`RawObjectValidator` (which otherwise requires a non-empty `objects` list for every other
`changeType`).

## `source_ref` — identifier, not identity; may change between revisions

Mind's answer, verbatim in substance: **identity is `correlation_id`, not `source_ref`.**
`source_ref` is only a user-facing link back to the source system (building the actual link from
`source_type` + `source_ref` is the UI's job — Bridge never does this). Mind always reads
`source_ref` off the **latest revision** of a given `correlation_id`, never pins it to whatever
value an earlier revision carried. Consequence: **`source_ref` is explicitly allowed to change
between revisions of the same logical item** — this is not a bug or an edge case to guard
against, it's the designed contract.

Concretely, for Slack: when a group's first/root message is deleted and another message becomes
the new first message, the next revision's `source_ref` re-points at that surviving message's own
`ts`, rather than either freezing the original (now-gone) `ts` or trying to keep some other value
stable across the edit. **A link to a message that still exists is strictly preferable to a dead
link to a deleted one.** When a group empties out entirely (its `DELETE_REVIEW`), `source_ref` is
`null` — there is no surviving message left to point at, and per the rule above there's no
obligation to keep a stale value around either.

This confirms `ChatSourceRecordMapper.slackSourceRef`'s existing behavior (re-derived from
`allMessages.get(0)` on every call, never cached/pinned at group creation) is correct as-is — no
code change was needed once this was confirmed with Mind.

## Per-source format (as actually produced by each reader)

| Source | `source_ref` | Format | Null when |
|---|---|---|---|
| Calendar | `event.getId()` | Google Calendar event id | never (always present, including on cancellation) |
| Drive | `file.getId()` | Google Drive file id | never (always present, including on trashed) |
| Email | the message's `Message-ID` header | RFC822 `Message-ID`, e.g. `<CAF123@mail.example.com>` | header absent |
| Slack | `chatId + ":" + ts` | `channel_id:ts`, e.g. `C123:1699999999.000100` | the group is empty (`DELETE_REVIEW` after the last message was deleted) |
| WhatsApp / fixtures | — | — | always null — no stable per-session id exists for these sources |

Matches `shared/proto/raw-object-event.proto`'s own field comment (`source_ref`, field 13) and
Mind's own contract (`teams/mind/src/mind/contracts/raw_object_event.py` /
`teams/mind/src/mind/contracts/README.md`), which mirror this exact description.

## Known follow-up, not Bridge's to fix

`teams/relay/schema-sync-hook/files/schemas/raw-object-event.proto` (the deployed/synced mirror
of `shared/proto/raw-object-event.proto`) is stale — missing `source_ref` and `classification`
entirely. Per the shared-proto ownership convention (see `teams/bridge/CLAUDE.md`), the schema
lives once in `shared/proto/`, Relay-owned; whoever runs the schema-sync-hook needs to re-sync it.
