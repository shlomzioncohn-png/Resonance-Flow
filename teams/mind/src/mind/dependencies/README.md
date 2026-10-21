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

Both `tenant_id` and `dependency_mentions` live on `PendingRequest`, carried
from the request Mind itself sent (`flow.send()`'s own `request.tenant_id` and
`dependency_mentions` argument), rather than parsed off Inbox's `task.created`
event. `dependency_mentions` has no equivalent on the canonical task at all --
it's Mind's own pre-creation extraction output -- so `PendingRequest` is the
only place to recover it when `task.created` arrives. `tenant_id` is also on
that event, but reading it from `PendingRequest` instead means a malformed
`event.tenant_id` can never cost a task its dependency publishing.

## Wiring it in

`DependencyAdapter.on_task_created` (`adapter.py`) is the adapter, passed as
`TaskCreationFlow`'s `on_matched`:

```python
flow = TaskCreationFlow(..., on_matched=DependencyAdapter(publisher).on_task_created)
```

It reads `matched.tenant_id` and `matched.request.dependency_mentions` --
both on `PendingRequest` since #353, carried from Mind's own request rather
than parsed off `task.created` -- and calls `publish_dependencies`. See its
module docstring for the retry tracking described below.

## An unresolved target isn't dropped

If a mention's target has no task_id yet (the issue's step 4),
`publish_dependencies` publishes whatever else resolved, returns `False`,
and holds the rest -- neither dropped nor force-published.
`DependencyAdapter` acts on that return value: it remembers the task and
retries it on every subsequent `on_task_created`, since any newly created
task might be exactly what was missing, and drops it once
`publish_dependencies` reports nothing outstanding (`True`). The per-task
published-edge tracking dedups an edge only for as long as its record
survives -- within `_MAX_PUBLISHED_AGE`, and as long as it isn't evicted by
the `_MAX_PUBLISHED` cap -- not forever. It's in-memory only besides, so it
doesn't survive a restart either. After expiry, eviction, or a restart, the
same mentions publish the same edges again, relying on Chain treating a
re-proposed edge (same `depends_on_task_id`, same `task_id`) as a no-op
rather than a hard duplicate.

## Running the tests

```
pytest tests/test_dependencies_publish.py tests/test_dependencies_kafka_sink.py tests/test_dependencies_adapter.py -v
```

No broker: the sink is an in-memory list, and `resolve_dependency_targets`
(#245) is mocked -- its own behavior is `tests/test_dependency_resolution.py`'s
job, not this module's.
