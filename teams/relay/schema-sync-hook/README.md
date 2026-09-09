# schema-sync-hook (DRAFT — lives under teams/relay/, not yet merged into ArgoCD)

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

## Confirmed dependency: Schema Registry doesn't exist in the real cluster yet

Checked every real infra branch (`avigail/argocd-bootstrap`, `chaya-relay`) —
Schema Registry has no chart, no Application, no Service anywhere. It only
exists in `teams/relay/docker-compose.yml` for local dev. Per the team lead,
it **will** be deployed for real eventually — this chart is preparation for
that, not usable until Schema Registry actually exists somewhere reachable.

## Why not a PreSync hook (the earlier version of this chart used one)

ArgoCD hooks only run within their *own* Application's sync — and relay is
deployed as part of the shared `resonance-flow` Application (all teams'
services, one Helm release). A hook here would never actually block relay's
rollout.

**What actually works, and is already used in this repo:** `sync-wave`. Real
example — `argocd/apps/kafka.yaml` has `sync-wave: "-1"`, `resonance-flow.yaml`
has `"0"`, so kafka's Application must finish and be healthy before
resonance-flow starts syncing. `argocd-application.yaml` here follows the same
pattern instead of a hook.

Confirmed with Avigail directly: her CI never touches the real cluster's live
data — only spins up a throwaway local `kind` cluster for smoke tests. No
existing precedent to reuse there either.

## What's a temporary placeholder right now

- **Everything under `files/`** — `schemas/*.proto`, `ci/check-schema-compatibility.sh`
  / `register-schema.sh`, and `schema-subjects.tsv` — are **copies**, not the
  source of truth, even though they now sit right next to the real ones
  (`../src/main/proto/`, `../ci/`) — Helm's `.Files.Get` still can't read outside
  its own chart directory, no matter how close. **Fixed by automation, not
  manual discipline:**
  [`.github/workflows/relay-schema-sync.yaml`](../../../.github/workflows/relay-schema-sync.yaml)
  re-copies the real files in here on every push to `main` that touches them,
  and commits the result itself (same pattern as `service-ci.yaml`'s
  `update-manifest` job auto-committing image tag bumps). Nobody should ever
  hand-edit anything under `files/` — it's a generated mirror.
- **`argocd-application.yaml`** belongs in the real `argocd/apps/` folder
  (owned by whoever set up `avigail/argocd-bootstrap`), not here — kept
  alongside the chart for now since everything is standalone.
- Three of the four schemas in `../ci/schema-subjects.tsv`
  (`task-created-event.proto`, `raw-object-event.proto`, `github-task.proto`)
  are **still unconfirmed drafts** — Inbox/Bridge haven't signed off on them.
  They're included only to prove the list handles more than one schema, not as
  a signal they're ready to actually register.

## Open questions before this can go live

- Who deploys Schema Registry for real, and where (own chart? part of `kafka/`?)
- Once it exists, what sync-wave does it get, so this chart's wave can sit
  correctly between it and `resonance-flow`'s `"0"`
