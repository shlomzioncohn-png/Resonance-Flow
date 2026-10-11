# schema-sync-hook

A Job that runs inside the cluster, checks every schema in
`../ci/schema-subjects.tsv` against the **live** Schema Registry, and registers
it only if compatible — using the **same two scripts** already used elsewhere
(`check-schema-compatibility.sh`, `register-schema.sh`), copied verbatim, not
reinvented.

The schema list is **not** configured in this chart. `../ci/schema-subjects.tsv`
is the single source of truth, shared with the pre-merge CI check
([`../ci/README.md`](../ci/README.md)); `relay-schema-sync.yaml` copies it in as
`files/schema-subjects.tsv` and `templates/configmap.yaml` renders both the
`schemas.tsv` list and the per-schema ConfigMap from it.

Subjects marked `# reset: <subject>` in that tsv are hard-deleted (soft, then
`?permanent=true`) before the check/register loop — see
[`../ci/README.md`](../ci/README.md#reset-subject--pre-production-escape-hatch).

Wired into ArgoCD as [`../argocd/apps/schema-sync-hook.yaml`](../argocd/apps/schema-sync-hook.yaml)
(moved there 2026-09-09 so `root-app.yaml` actually picks it up — it used to sit
in this directory as an inert, unwatched file).

## Schema Registry is now deployed — as part of `resonance-flow`, not standalone

Resolved in c3908262: Schema Registry is deployed as a `components.schema-registry`
block in the shared [`resonance-flow`](../helm/resonance-flow) Helm chart —
same pattern every other team's service uses, in the same release/namespace —
not as its own chart or Application. `values.yaml`'s `schemaRegistryUrl` here
points at it via its cross-namespace FQDN accordingly. It's still only reachable
from inside the cluster; `teams/relay/notification_delivery/docker-compose.yml`'s copy remains the
one used for local dev.

## Why not a PreSync hook (the earlier version of this chart used one)

ArgoCD hooks only run within their *own* Application's sync — and relay is
deployed as part of the shared `resonance-flow` Application (all teams'
services, one Helm release). A hook here would never actually block relay's
rollout.

**What actually works, and is already used in this repo:** `sync-wave`. Real
example — `argocd/apps/resonance-flow.yaml` has `sync-wave: "0"`; this chart's
own `argocd/apps/schema-sync-hook.yaml` uses `"1"`, so resonance-flow (which
now renders Schema Registry itself, see above) must finish and be healthy
before this Job's Application starts syncing. (There used to be a separate,
earlier-waved `apps/kafka.yaml` this pattern was modeled on — it no longer
exists; Kafka is now a subchart dependency of `resonance-flow` itself, see
`../argocd/README.md`.)

Confirmed with Avigail directly: her CI never touches the real cluster's live
data — only spins up a throwaway local `kind` cluster for smoke tests.

## What's a temporary placeholder right now

- **Everything under `files/`** — `schemas/*.proto`, `ci/check-schema-compatibility.sh`
  / `register-schema.sh`, and `schema-subjects.tsv` — are **copies**, not the
  source of truth, even though they now sit right next to the real ones
  (`../../../shared/proto/`, `../ci/`) — Helm's `.Files.Get` still can't read outside
  its own chart directory, no matter how close. **Fixed by automation, not
  manual discipline:**
  [`.github/workflows/relay-schema-sync.yaml`](../../../.github/workflows/relay-schema-sync.yaml)
  re-copies the real files in here on every push to `main` that touches them,
  and commits the result itself (same pattern as `service-update-manifest.yaml`
  auto-committing image tag bumps). Nobody should ever
  hand-edit anything under `files/` — it's a generated mirror.
- Three of the six schemas in `../ci/schema-subjects.tsv`
  (`task-created-event.proto`, `raw-object-event.proto`, `github-task.proto`)
  are **still unconfirmed drafts** — Inbox/Bridge haven't signed off on them.
  (The other three — `task-event.proto` and the two added 2026-09-09,
  `task-updated-event.proto` / `task-completed-event.proto` — are mechanical
  mirrors of already-existing Java payload classes, not independent drafts,
  but haven't gone through an explicit sign-off round either.) Being in this
  list is what proves it handles more than one schema, not a signal any of
  them are ready to actually register against a live FULL-compatibility
  registry.

## What's left before this goes live

- ~~Who deploys Schema Registry for real, and where~~ — **resolved** (c3908262):
  as `components.schema-registry` inside `resonance-flow` itself.
- ~~Once it exists, what sync-wave does this chart get~~ — **resolved**:
  `"1"`, after `resonance-flow`'s `"0"` (see `argocd/apps/schema-sync-hook.yaml`).
- Still open: none of the schemas above have an unambiguous sign-off record —
  worth closing out before flipping this Job on for real, since registering
  against a live FULL-compatibility registry is effectively one-way (see the
  compatibility notes in `task-created-event.proto`'s own header).
