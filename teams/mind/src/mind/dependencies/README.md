# dependencies

Resolving a task's dependency mentions into real targets and publishing
`task.dependencies.proposed` to Chain (#355).

| file | what it does |
|---|---|
| `event.py` | builds the `DependencyProposed` payload from resolved targets |
| `kafka_sink.py` | `DependencyProposed` -> `DependencyProposedEvent`, published to `task.dependencies.proposed` |
| `publisher.py` | `DependencyPublisher`: resolves mentions (#245), publishes an edge for each that resolves, tracks the rest as pending (#918) |
| `adapter.py` | `DependencyAdapter`: the `on_matched` glue for `TaskCreationFlow` |
| `pending.py` | `PendingDependencyStore`, and `PendingDependencies` in memory (#918) |
| `postgres_pending.py` | `PostgresPendingDependencyStore` -- the same store, durable (#918) |
| `retry.py` | `retry_pending_dependencies`: re-resolves every mention still pending for a tenant (#918) |

## Scope, then and now

`publish_dependencies` resolves a task's `dependency_mentions` the moment
`task.created` confirms the task exists, and publishes an edge for
whatever resolves right then -- unchanged since #355. What changed (#918,
on review): a mention that doesn't resolve yet is no longer just logged
and forgotten. When `DependencyPublisher` is given a `pending` store, the
mention is registered there, and `retry.py`'s `retry_pending_dependencies`
re-resolves everything still pending for a tenant from
`embeddings/index_consumer.py`'s `on_indexed` hook -- called for *every*
`task.created` once that task's own row is in the index, not only one
Mind itself requested. That is the actual point: a mention's target can
be a task Bridge or a human created directly in Inbox, which never goes
through `TaskCreationFlow` at all, so retrying only from `on_matched`
(the original, and still the *first* resolution attempt) would never
reach it.

Without a `pending` store (every call site before #918's runner wiring),
`publish_dependencies` is exactly #355's original one-shot behavior --
nothing here forces retry on a caller that doesn't want it.

See `embeddings/index_consumer.py`'s own docstring for why the hook lives
there and not in `task_creation/listener.py`: the index consumer is a
separate process with its own consumer group, and a retry that runs
before the target row exists finds nothing, same as the first attempt did.

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

## Sending an edge: relying on Chain's dedup -- with real caveats

This is about the *send* itself, independent of #918's pending tracking
above (which is about a mention that hasn't resolved at all yet, not
about a resolved edge being sent twice). One event per resolved mention,
`event_id = uuid5(task_id:target)` (#353's
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

## Open decisions (review, not settled in this PR)

Three items from review intentionally left open rather than decided
unilaterally -- flagging for the team lead's call, not fixed here:

- **Race condition between the two consumers.** A mention can be added to
  `pending` (`landed_consumer_runner.py`) *after* the index consumer has
  already scanned pending mentions for that tenant on the `task.created`
  that would have resolved it. It self-heals on the *next* `task.created`
  for that tenant -- but a quiet tenant with no further activity could
  wait indefinitely. Options: accept as a documented limitation (the
  common case self-heals fast), or add a periodic sweep independent of
  `task.created` traffic.
- **Unbounded `pending_dependencies` growth.** `retry_pending_dependencies`
  re-resolves *every* mention still pending for a tenant, synchronously,
  on *every* `task.created` for that tenant -- cost scales with backlog
  size, not with what actually changed. A tenant with a large, slow-to-
  resolve (or never-resolving) backlog pays that cost repeatedly, and
  risks exceeding `max.poll.interval.ms` on `index_consumer_runner.py`'s
  task.created consumer (which, unlike `landed_consumer_runner.py`, sets
  no override -- librdkafka's 300s default applies).
- **No periodic cleanup of either pending table.** Both already have a
  timestamp column (`pending_dependencies.created_at`,
  `pending_creation_requests.sent_at`) that nothing reads yet. A
  time-based sweep would directly bound the growth above, but choosing a
  retention window is a product call (how long do we keep retrying a
  dependency before giving up?), not a technical one -- not added
  speculatively here.

The real architectural fix for the retry-cost problem (review): store
each pending mention's own embedding and search in the *reverse*
direction (from the newly-indexed task back to pending mentions) instead
of re-embedding and re-searching the whole backlog every time. That's a
migration plus a new query path, not a quick addition -- a candidate
follow-up issue, not attempted here.

## Running the tests

```
pytest tests/test_dependencies_publish.py tests/test_dependencies_kafka_sink.py tests/test_dependencies_adapter.py tests/test_dependencies_pending.py tests/test_dependencies_retry.py -v
```

No broker: the sink is an in-memory list, and `resolve_dependency_targets`
(#245) is mocked -- its own behavior is `tests/test_dependency_resolution.py`'s
job, not this module's.

`tests/test_postgres_pending_dependency_store.py` and
`tests/test_dependency_retry_integration.py` need a real pgvector
instance (`MIND_VECTOR_STORE__DSN`) -- the former for `PostgresPendingDependencyStore`
itself, the latter for the full #918 scenario end to end (a mention left
pending, a real `task.created` for its real target, resolved through the
real embedding model and a real search).
