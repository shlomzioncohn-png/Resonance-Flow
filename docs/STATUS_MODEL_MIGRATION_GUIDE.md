# Task Status Model Migration Guide

## Overview

On 2026-09-22, the LENS team adopted the full 8-value task status model from Inbox, replacing the simplified 4-value model (READY/BLOCKED/IN_PROGRESS/COMPLETED) used in the BFF ↔ Frontend API layer.

**This document is for teams consuming the Employee Workspace API or BFF responses.**

---

## What Changed

### Before (Simplified Model)
The API exposed 4 status values:
- `READY` — task is approved and has no blockers
- `IN_PROGRESS` — task is actively being worked on
- `BLOCKED` — task is snoozed or otherwise paused
- `COMPLETED` — task is done, rejected, or cancelled

Information was lost: the API didn't distinguish between "new" vs "approved" vs "in review", and couldn't represent "snoozed" as a distinct state from "rejected".

### After (Full 8-Value Model)
The API now exposes all 8 canonical Inbox statuses:
- `NEW` — Task created, not yet reviewed or approved
- `IN_REVIEW` — Under review, decision pending
- `APPROVED` — Approved and ready to work on
- `IN_PROGRESS` — Currently being worked on
- `SNOOZED` — Explicitly paused by user or system
- `DONE` — Completed successfully
- `REJECTED` — Not approved, rejected after review
- `CANCELLED` — Cancelled before completion

Additionally, **`isAvailable` is now a first-class field** (Boolean), distinct from `status`:
- `isAvailable = true` means no technical blockers (Chain computed it)
- `isAvailable = false` means blocked by unresolved dependencies/constraints
- A task can be `status="APPROVED"` AND `isAvailable=false` (approved but blocked)

---

## Migration Path

### If You Were Checking `status === "READY"`

**Old code:**
```typescript
if (task.status === "READY") {
  // task is approved and unblocked
}
```

**New code:**
```typescript
if (task.isAvailable === true && ["NEW", "IN_REVIEW", "APPROVED"].includes(task.status)) {
  // task is unblocked and not yet started
}
// OR, if you just care about "available to start":
if (task.isAvailable === true) {
  // task is not blocked (regardless of approval state)
}
```

**Reasoning:**
- READY was a compound state: "approved" + "no blockers"
- Now these are separate: `status` tells you the workflow state, `isAvailable` tells you about blockers
- NEW/IN_REVIEW/APPROVED are the "pre-start" statuses; if you need ready-to-start, also check `isAvailable`

### If You Were Checking `status === "BLOCKED"`

**Old code:**
```typescript
if (task.status === "BLOCKED") {
  // task is blocked
}
```

**New code:**
```typescript
if (task.isAvailable === false) {
  // task is blocked (clearer and more direct)
}
```

**Reasoning:**
- The old BLOCKED status was a kludge: it mapped `snoozed` plus dependency blockers
- Now use `isAvailable` directly — it's the actual blocker indicator
- If you need the specific reason (snoozed vs. dependency vs. other), check `status` and `constraints`

### If You Were Checking `status === "IN_PROGRESS"`

**No change needed:**
```typescript
if (task.status === "IN_PROGRESS") {
  // still works as-is
}
```

### If You Were Checking `status === "COMPLETED"`

**Old code:**
```typescript
if (task.status === "COMPLETED") {
  // task is done, rejected, or cancelled (don't know which)
}
```

**New code:**
```typescript
if (["DONE", "REJECTED", "CANCELLED"].includes(task.status)) {
  // task is terminal (done, rejected, or cancelled)
}
// OR, if you need to distinguish:
if (task.status === "DONE") {
  // task is complete
} else if (task.status === "REJECTED") {
  // task was rejected in review
} else if (task.status === "CANCELLED") {
  // task was cancelled before completion
}
```

---

## API Contract Updates

### Status Filter Values (`GET /api/workspace/tasks?filter=`)

**Before:**
```
filter ∈ { all, in_progress, blocked, waiting_on_me }
```

**After:**
```
filter ∈ { all, new, in_review, approved, in_progress, snoozed, done, rejected, cancelled }
```

The old filter values (`blocked`, `waiting_on_me`) are **no longer valid**. If you were using them:

**Old code:**
```typescript
// Get all blocked tasks
listTasks({ filter: "blocked" })
```

**New code:**
```typescript
// Get all unavailable tasks (better filter: is_available=false in body, or use GraphQL)
// OR, manually filter client-side:
const allTasks = await listTasks({ filter: "all" })
const blocked = allTasks.items.filter(t => !t.isAvailable)

// OR use specific status filter:
listTasks({ filter: "snoozed" })  // explicitly paused
// (but this doesn't include dependency blockers)
```

### Status Update Endpoint (`PATCH /api/workspace/tasks/{taskId}/status`)

**Before:** `status` ∈ { READY, IN_PROGRESS, BLOCKED, COMPLETED }

**After:** `status` ∈ { NEW, IN_REVIEW, APPROVED, IN_PROGRESS, SNOOZED, DONE, REJECTED, CANCELLED }

Your client will now validate against all 8 values. If you have hardcoded status update logic:

**Old code:**
```typescript
updateTaskStatus(taskId, "BLOCKED")  // snoozed = blocked
```

**New code:**
```typescript
updateTaskStatus(taskId, "SNOOZED")  // use the actual status
```

---

## Data Model Changes

### TaskDTO Interface (Frontend)

New/changed fields:

| Field | Type | Notes |
|---|---|---|
| `status` | String | Now one of 8 values (NEW, IN_REVIEW, ..., CANCELLED) |
| `inboxStatus` | String | Synonym for `status`, kept for backward compatibility |
| `isAvailable` | Boolean | New field: indicates no blockers (independent of status) |

### TaskListFilter Type (Frontend)

```typescript
// Before:
export type TaskListFilter = 'all' | 'in_progress' | 'blocked' | 'waiting_on_me'

// After:
export type TaskListFilter = 'all' | 'new' | 'in_review' | 'approved' | 'in_progress' | 'snoozed' | 'done' | 'rejected' | 'cancelled'
```

### TaskStatus Type (Frontend)

```typescript
// Before:
export type TaskStatus = 'READY' | 'IN_PROGRESS' | 'BLOCKED' | 'COMPLETED'

// After:
export type TaskStatus = 'NEW' | 'IN_REVIEW' | 'APPROVED' | 'IN_PROGRESS' | 'SNOOZED' | 'DONE' | 'REJECTED' | 'CANCELLED'
```

---

## Common Patterns

### "Is this task ready to work on?"

**Best practice (now):**
```typescript
const readyToStart = task.isAvailable === true && task.status === "APPROVED"
```

(Before, this was just `status === "READY"`, but now availability and approval are separate concerns.)

### "Is this task terminal (closed)?"

```typescript
const isClosed = ["DONE", "REJECTED", "CANCELLED"].includes(task.status)
```

### "Does this task need action from me?"

```typescript
const needsMyAction = !task.isAvailable && task.status !== "DONE"
// i.e., blocked but not yet completed
```

### "Can I change the status of this task?"

All statuses can be transitioned:
- User can explicitly move to SNOOZED (paused)
- User can move IN_PROGRESS → DONE (mark as complete)
- System can move to REJECTED (failed review)
- System can move to CANCELLED (out of scope)

(The old model hid these transitions behind the 4-status simplification.)

---

## Backward Compatibility

**There is no backward compatibility mode.** If your code depends on the old 4-status model:

1. **Update your status checks** (use the patterns above)
2. **Update your filter queries** (use the new filter values)
3. **Test thoroughly** (especially UI that shows status labels)

---

## Questions & Support

- **API contract details:** See `docs/employee-workspace-api-contract.md` §5.0 ("Status Model Change")
- **Data schema:** See `docs/Inbox_Data_Schema_Specification.md` (Inbox owns the 8-status enum)
- **Code examples:** See `teams/lens/bff/src/main/java/com/rnd/bff/dto/TaskDTO.java` (javadoc with migration guidance)

---

## Deployment Notes

- **No database migration required:** Status values are stored as-is in Inbox; the BFF now passes them through directly instead of mapping them
- **Frontend must be updated:** Old 4-status code will receive 8-status values and may break
- **Gateway changes are minimal:** Just documentation updates; all filtering/validation lives in BFF
- **Chain is unaffected:** Chain has its own 3-status model (PENDING/ACTIVE/DONE) and doesn't import Inbox's TaskStatus

---

## Timeline

- **2026-09-22:** Status model change deployed
- **All systems:** Running on 8-status model end-to-end
- **Migration period:** Ongoing (no cutoff date; older clients simply stop working if not updated)

