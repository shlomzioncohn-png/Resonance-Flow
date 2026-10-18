# dependencies

Resolving a task's dependency mentions into real targets and publishing
`task.dependencies.proposed` to Chain (#355).

| file | what it does |
|---|---|
| `event.py` | builds the `DependencyProposed` payload from resolved targets |
| `kafka_sink.py` | `DependencyProposed` -> `DependencyProposedEvent`, published to `task.dependencies.proposed` |
| `publisher.py` | `DependencyPublisher`: resolves mentions (#245), tracks what's already sent, publishes only new edges |

## The hand-off interface

`DependencyPublisher.publish_dependencies(*, task_id, tenant_id, dependency_mentions)`
is the only thing #355 exposes. It takes all three explicitly rather than reading
them off #353's `MatchedReply`/`PendingRequest`, because neither carries what
#355 needs:

- `dependency_mentions` -- free text, #245's input shape -- isn't stored anywhere
  yet. It needs to be captured during extraction's existing LLM call (a new
  `dependency_mentions: list[str]` field on `ExtractedFields`) and carried on
  `PendingRequest` until the `task.created` reply arrives, the same way
  `revision`/`task_index` already are.
- `tenant_id` isn't on `MatchedReply` at all today, and #355 needs it to build
  the event -- `task.dependencies.proposed`'s own contract requires it.

Neither of those belongs to #355 to add -- `PendingRequest`, `MatchedReply`,
`send()` and `flow.send()` are #353's, owned by Yonit. This module waits for
both fields rather than reaching into her files for them.

## Wiring it in, once both fields exist

The piece not yet written is the few-line adapter that plugs this into
`TaskCreationFlow(on_matched=...)`:

```python
async def on_task_created(publisher: DependencyPublisher, matched: MatchedReply) -> None:
    await publisher.publish_dependencies(
        task_id=matched.task_id,
        tenant_id=matched.tenant_id,                               # needs MatchedReply to carry it
        dependency_mentions=matched.request.dependency_mentions,    # needs PendingRequest to carry it
    )
```

Until then, `publish_dependencies` has no caller in production. It is fully
built and tested (`tests/test_dependencies_publish.py`,
`tests/test_dependencies_kafka_sink.py`) against mentions supplied directly,
standing in for what the adapter above will eventually pass through -- just
not wired to a real `task.created` event yet.

## An unresolved target isn't dropped

If a mention's target has no task_id yet (the issue's step 4),
`publish_dependencies` publishes whatever else resolved and holds the rest --
neither dropped nor force-published. Calling it again later with the same
`dependency_mentions` (once the adapter above exists and fires again for *any*
`task.created`) picks up what's now resolved; the per-task published-edge
tracking means an edge already sent this process is never sent twice. That
tracking is in-memory only -- it doesn't survive a restart, so the same
mentions publish the same edges again afterwards, relying on Chain treating a
re-proposed edge (same `depends_on_task_id`, same `task_id`) as a no-op
rather than a hard duplicate. When, or whether, to call again is the
caller's decision -- not built here.

## Running the tests

```
pytest tests/test_dependencies_publish.py tests/test_dependencies_kafka_sink.py -v
```

No broker: the sink is an in-memory list, and `resolve_dependency_targets`
(#245) is mocked -- its own behavior is `tests/test_dependency_resolution.py`'s
job, not this module's.
