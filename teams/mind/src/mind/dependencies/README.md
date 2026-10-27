# dependencies

Resolving a task's dependency mentions into real targets and publishing
`task.dependencies.proposed` to Chain (#355).

| file | what it does |
|---|---|
| `event.py` | builds the `DependencyProposed` payload from resolved targets |
| `kafka_sink.py` | `DependencyProposed` -> `DependencyProposedEvent`, published to `task.dependencies.proposed` |
| `publisher.py` | `DependencyPublisher`: resolves mentions (#245) once, publishes an edge for each that resolves |
| `adapter.py` | `DependencyAdapter`: the `on_matched` glue for `TaskCreationFlow` |

## Scope: one shot, no retry

`publish_dependencies` resolves a task's `dependency_mentions` once, the
moment `task.created` confirms the task exists, and publishes an edge for
whatever resolves right then. A mention that doesn't resolve yet (its
target isn't a task yet) is logged and not retried -- nothing in this
package holds state between calls to pick it up later. That's a real,
known limitation (a target created after this one call, or created
directly in Inbox rather than via Mind, never gets its edge), not an
oversight: an in-memory retry store can't survive a restart or reliably
reach a target created outside Mind's own traffic either way, so a correct
version needs its own durable design (e.g. a Postgres-backed store,
retried from wherever a task gets indexed) -- tracked as a follow-up, not
attempted here. #355 itself only promises "publish what's known when
task.created arrives".

## The hand-off interface

`DependencyPublisher.publish_dependencies(*, task_id, tenant_id, dependency_mentions)`
is the only thing #355 exposes. It takes all three explicitly rather than reading
them off #353's `MatchedReply`/`PendingRequest` itself -- `adapter.py` is the one
that reads `matched.task_id`, `matched.tenant_id` and
`matched.request.dependency_mentions` and passes them through.

Both `tenant_id` and `dependency_mentions` live on `PendingRequest`, carried
from the request Mind itself sent (`flow.send()`'s own `request.tenant_id` and
`dependency_mentions` argument), rather than parsed off Inbox's `task.created`
event. `dependency_mentions` has no equivalent on the canonical task at all --
it's Mind's own pre-creation extraction output -- so `PendingRequest` is the
only place to recover it when `task.created` arrives. `tenant_id` is also on
that event, but reading it from `PendingRequest` instead means a malformed or
mismatched `event.tenant_id` can never cost a task its dependency publishing
-- `reply.py` still compares the two and logs a warning on a mismatch (a
signal something is wrong elsewhere), but that comparison never gates
`on_matched`.

## Wiring it in

`DependencyAdapter.on_task_created` (`adapter.py`) is the adapter, passed as
`TaskCreationFlow`'s `on_matched`:

```python
flow = TaskCreationFlow(..., on_matched=DependencyAdapter(publisher).on_task_created)
```

It reads `matched.tenant_id` and `matched.request.dependency_mentions` --
both on `PendingRequest` since #353, carried from Mind's own request rather
than parsed off `task.created` -- and calls `publish_dependencies` once.

## No local tracking, relying on Chain -- with real caveats

One event per resolved mention, `event_id = uuid5(task_id:target)` (#353's
pattern: a stable id derived from the edge itself). A redelivered
`task.created` (Kafka's at-least-once delivery) that reprocesses from
scratch -- i.e. before `pending.discard` ran for it, so `reply.py` still
finds it and calls `on_matched` again -- re-resolves the same mentions and
republishes the same event for any edge already sent, relying on Chain's
own dedup (`teams/Chain/README.md`, "Idempotency": `task.dependencies.proposed`
deduped by `event_id`). Two caveats, not glossed over:
- If the redelivery happens *after* `pending.discard` already ran (a crash
  between a successful `on_matched` and the `task.created` offset commit),
  `reply.py` finds no pending request and skips `on_matched` entirely on
  that redelivery -- a mention that failed to resolve the first time is
  **not** retried in that case, only in the "reprocessed from scratch" case
  above.
- Chain's own dedup is `InMemoryProcessedEventStore`-backed today --
  single-instance, lost on restart (Chain's own README says so plainly).
  A duplicate send landing after a Chain restart, or against a second
  instance, is not actually deduped. This design accepts that; it isn't
  idempotent against every failure mode, only against the common one.

## Confidence on a proposed edge (#476)

`DependencyEdge.confidence` already existed (#244) before this issue --
reviewed against #476's reasoning, not rebuilt. It used to be the
mention's raw cosine similarity; now it's that similarity's distance
above `Settings.dependency_match_threshold`, from the same
`confidence_from_similarity()` the other two inferences (duplicate,
related-task) use. See `embeddings/README.md`, "Confidence" for the
mapping and why a raw similarity is the wrong thing to send.

## Running the tests

```
pytest tests/test_dependencies_publish.py tests/test_dependencies_kafka_sink.py tests/test_dependencies_adapter.py -v
```

No broker: the sink is an in-memory list, and `resolve_dependency_targets`
(#245) is mocked -- its own behavior is `tests/test_dependency_resolution.py`'s
job, not this module's.
