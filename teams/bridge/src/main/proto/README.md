# Proto files in this directory

Every registered schema Bridge produces or imports — `task-created-event.proto`,
`task-creation-requested-event.proto`, `raw-object-event.proto` and `structured-task-event.proto`
(`bridge.structured.tasks`, #567) — is built from `shared/proto` (`proto.root` in `pom.xml`, the
`compile-shared-proto` execution). Don't copy them back here.

`github-task.proto` is the one file still here; it hasn't moved to `shared/proto` yet (#566).

**Why generated code under `com.rnd.inbox.event`, not a Bridge package:** `structured-task-event.proto`
declares `option java_package = "com.rnd.inbox.event";`, so its Java classes land in that package
inside Bridge's build output too. That's the shared contract's package, not a stray dependency on
Inbox's module.
