# Proto files in this directory are Inbox's, copied verbatim

`structured-task-event.proto`, `task-created-event.proto`, and `task-creation-requested-event.proto`
are byte-identical copies of the same files under `teams/inbox/src/main/proto/`. Bridge is a
producer on `bridge.structured.tasks`, consumed by Inbox's existing `StructuredTaskEvent`
Protobuf schema — Inbox already owns and has deployed this contract (see `teams/inbox/CLAUDE.md`
§10 and `StructuredTaskConsumer`); Bridge does not author its own schema for this topic.

Do not edit these files here. Any field, enum, or message change belongs in Inbox's copies first,
then gets re-copied here byte-for-byte — see the drift check that enforces this.

**Why generated code under `com.rnd.inbox.event`, not a Bridge package:** each file's
`option java_package = "com.rnd.inbox.event";` is part of the byte-identical copy, so compiling
these here produces Java classes in that same package inside Bridge's own build output. This is
intentional, not a stray cross-module dependency — it's the direct consequence of "byte-identical"
including the file's own package declaration. `task-created-event.proto` and
`task-creation-requested-event.proto` also each define one Inbox-only message
(`TaskCreatedEvent`, `TaskCreationRequestedEvent`) that Bridge never constructs — they come along
for free because they live in the same file as the enums/messages Bridge's producer actually needs
(`SourceType`, `TaskTier`, `TaskRoleContext`, `TaskStatus`, `Assignee`, `AssigneeType`,
`AssignmentRole`), and keeping the file byte-identical means not splitting them out.
