# What Bridge sends from Slack — edits and deletions

Companion to `docs/plans/slack-message-deleted.md` (the implementation plan) and ROADMAP.md §3.1
(status). This is the contract-facing summary: what a consumer of `bridge.structured.tasks`
events actually sees for a Slack-sourced chat session, now that edits and deletions are handled.

## Replacement semantics (not new — Slack is just a new user of it)

Every event for a given logical chat session (Slack time-gap group or thread) shares the same
`correlationId` across its whole lifetime. A later revision — any edit, partial deletion, or full
deletion — carries the **same `correlationId`**, a **bumped `revision`**. The consuming side treats
this as a full replacement of the prior transcript for that `correlationId`, never an append or a
duplicate. This is the same already-agreed contract Calendar (`RealCalendarSourceReader`'s
cancellation path) and Drive (`trashed` files) already rely on for their own `DELETE_REVIEW`
events — Slack's deletion handling is an application of an existing agreement, not a new one.

## `DELETE_REVIEW` for an emptied group

When a deletion (one message, or the last remaining one) leaves a chat session's `GroupState` with
zero messages, Bridge publishes exactly one event for that `correlationId`:

- `changeType = DELETE_REVIEW`
- `revision` bumped past whatever was last published/claimed for this `correlationId`
- `objects = []` — empty, always. The logical item no longer exists; there is nothing to land a
  ref for, and the `DELETE_REVIEW` record's own sparse/filler `rawContent` is never landed or
  referenced. Enforced in two places, both source-agnostic (no Slack-specific branching):
  - `UnstructuredPayloadMapper.map()` — the batch/thread-reconcile path (`UnstructuredIntakeLane`).
  - `SlackSessionPublisher.publishSession()` — the non-thread, timer-driven path, which bypasses
    `UnstructuredIntakeLane` entirely (see its own class javadoc) and therefore needed this same
    check added independently; found and fixed while building this feature, since it had never
    been exercised with a real (non-mocked) `DELETE_REVIEW` payload before.
  - `RawObjectValidator.validate()` also had to stop unconditionally rejecting an empty `objects`
    list — same scope: a pre-existing gap discovered while building this feature, not introduced
    by it. Without this fix, no `DELETE_REVIEW` event (Slack's or Calendar's/Drive's existing
    ones) could ever have actually reached Kafka through this validator.

**Deleting one message out of a multi-message session is NOT automatically a `DELETE_REVIEW`.**
`correlationId` represents the whole session, not one message — removing one message while others
remain is a content-shrinking edit, published as `UPSERT` at a bumped revision with the deleted
message simply absent from the transcript. `DELETE_REVIEW` only once the session itself has zero
messages left.

## Timing

- **Thread groups** (Slack threads) publish immediately on every reconcile, same as today —
  including a deletion that empties the thread. No debounce.
- **Non-thread (time-gap) groups**: once a group `maybePublished` (`published() || revision() > 0 ||
  claimedRevision() != null`), any further change — edit, partial deletion, or full deletion —
  publishes after `correctionDelay` (`bridge.intake.chat.correction-delay-seconds`, default 60s)
  instead of the full `sessionGapMinutes` debounce, collapsing a burst of rapid corrections into a
  single publish. A group that has never been published/claimed/revised stays on the ordinary
  `latestActivity + sessionGapMinutes` debounce, unchanged from before this feature.

## Known limitations (see ROADMAP.md §3.1 for the full list)

- A channel group already `confirmPublished` *before* a reply caused thread migration is
  permanently unreachable for correction (no back-reference from the new thread group to the
  original's `correlationId`).
- A deletion/tombstone arriving before the message it targets was ever indexed is a no-op; a
  later delayed arrival of that message is ingested normally, with no record it was already
  deleted.
