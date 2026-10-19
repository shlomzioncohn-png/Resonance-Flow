# dependencies

Resolving a task's dependency mentions into real targets and publishing
`task.dependencies.proposed` to Chain (#355).

| file | what it does |
|---|---|
| `event.py` | builds the `DependencyProposed` payload from resolved targets |
| `kafka_sink.py` | `DependencyProposed` -> `DependencyProposedEvent`, published to `task.dependencies.proposed` |
| `publisher.py` | `DependencyPublisher`: resolves mentions (#245), tracks what's already sent, publishes only new edges |
| `adapter.py` | `DependencyAdapter`: the `on_matched` glue for `TaskCreationFlow`, plus retrying a target that isn't a task yet |

## The hand-off interface

`DependencyPublisher.publish_dependencies(*, task_id, tenant_id, dependency_mentions)`
is the only thing #355 exposes. It takes all three explicitly rather than reading
them off #353's `MatchedReply`/`PendingRequest` itself -- `adapter.py` is the one
that reads `matched.task_id`, `matched.tenant_id` and
`matched.request.dependency_mentions` and passes them through.

`tenant_id` is already on Inbox's `task.created` event (every task has one), so
`MatchedReply` reads it straight from there -- no state needed. `dependency_mentions`
is different: Inbox's schema has no such field, since it's Mind's own pre-creation
extraction output, not part of the canonical task. The only way to recover it when
`task.created` arrives is to have remembered it against the request's
`correlation_id` -- so it lives on `PendingRequest`, carried from `flow.send()`'s
own `dependency_mentions` argument the same way `revision`/`task_index` already were.

## Wiring it in

`DependencyAdapter.on_task_created` (`adapter.py`) is the adapter, passed as
`TaskCreationFlow`'s `on_matched`:

```python
flow = TaskCreationFlow(..., on_matched=DependencyAdapter(publisher).on_task_created)
```

It reads `matched.tenant_id` (straight off `task.created`) and
`matched.request.dependency_mentions` (on `PendingRequest` since #353) and
calls `publish_dependencies`. See its module docstring for the retry
tracking described below.

## An unresolved target isn't dropped

If a mention's target has no task_id yet (the issue's step 4),
`publish_dependencies` publishes whatever else resolved, returns `False`,
and holds the rest -- neither dropped nor force-published.
`DependencyAdapter` acts on that return value: it remembers the task and
retries it on every subsequent `on_task_created`, since any newly created
task might be exactly what was missing, and drops it once
`publish_dependencies` reports nothing outstanding (`True`). The per-task
published-edge tracking means an edge already sent this process is never
sent twice. That tracking is in-memory only -- it doesn't survive a
restart, so the same mentions publish the same edges again afterwards,
relying on Chain treating a re-proposed edge (same `depends_on_task_id`,
same `task_id`) as a no-op rather than a hard duplicate.

## Running the tests

```
pytest tests/test_dependencies_publish.py tests/test_dependencies_kafka_sink.py tests/test_dependencies_adapter.py -v
```

No broker: the sink is an in-memory list, and `resolve_dependency_targets`
(#245) is mocked -- its own behavior is `tests/test_dependency_resolution.py`'s
job, not this module's.
