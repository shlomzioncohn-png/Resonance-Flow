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

Wired into ArgoCD as [`../argocd/apps/schema-sync-hook.yaml`](../argocd/apps/schema-sync-hook.yaml).

## Where the Schema Registry runs

It's the `components.schema-registry` block in the shared
[`resonance-flow`](../helm/resonance-flow) chart, in the `resonance-flow` namespace, so
`values.yaml`'s `schemaRegistryUrl` uses its cross-namespace FQDN. It's only reachable from
inside the cluster; for local runs see [`../ci/README.md`](../ci/README.md#running-it-locally).

## Why its own Application, not a PreSync hook

ArgoCD hooks only run within their *own* Application's sync, and the registry lives in the
`resonance-flow` Application. So this chart is a separate Application at `sync-wave: "1"`,
after `resonance-flow`'s `"0"` (gated on health via
[`../argocd/argocd-cm-patch.yaml`](../argocd/argocd-cm-patch.yaml)).

The services are in that same wave `0`, so they're already deployed when this Job runs. A
failed Job stops only the later waves (Traefik, wave `2`); it doesn't hold back a service
rollout.

## `files/` is a generated mirror

Everything under `files/` (`schemas/*.proto`, `ci/check-schema-compatibility.sh`,
`ci/register-schema.sh`, `ci/_schema-references.sh`, `schema-subjects.tsv`) is a copy, because
Helm's `.Files.Get` can't read outside the chart directory. The sources are
[`../../../shared/proto/`](../../../shared/proto) and [`../ci/`](../ci).
[`relay-schema-sync.yaml`](../../../.github/workflows/relay-schema-sync.yaml) regenerates them
on every push to `main` that touches the sources and opens a PR (branch `ci/relay-schema-sync`)
with the result. Never edit anything under `files/` by hand.
