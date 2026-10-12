# Proto files in this directory

`task-created-event.proto` and `task-creation-requested-event.proto` used to be byte-identical
copies kept here, but since #568 both Chain and Inbox build from `shared/proto` instead of their
own copies, and Bridge now does the same (`proto.root` in `pom.xml`, the `compile-shared-proto`
execution) — they're deleted from this directory, not duplicated.

`structured-task-event.proto` is the one file still copied here, verbatim, from
`teams/inbox/src/main/proto/structured-task-event.proto` — Bridge is a producer on
`bridge.structured.tasks`, consumed by Inbox's existing `StructuredTaskEvent` Protobuf schema,
which Inbox already owns and has deployed (see `teams/inbox/CLAUDE.md` §10 and
`StructuredTaskConsumer`). This is temporary until `structured-task-event.proto` itself also
moves to `shared/proto` (#567/#618) — at that point this file and the drift check
(`StructuredTaskProtoDriftTest`) both go away too.

Do not edit `structured-task-event.proto` here. Any field, enum, or message change belongs in
Inbox's copy first, then gets re-copied here byte-for-byte — see
`StructuredTaskProtoDriftTest`, which enforces this.

**Why generated code under `com.rnd.inbox.event`, not a Bridge package:** `structured-task-event.proto`'s
`option java_package = "com.rnd.inbox.event";` is part of the byte-identical copy, so compiling it
here produces Java classes in that same package inside Bridge's own build output. This is
intentional, not a stray cross-module dependency — it's the direct consequence of "byte-identical"
including the file's own package declaration.
