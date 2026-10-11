# Relay schema CI

Kafka event schemas (`../../../shared/proto/*.proto`) are a contract between
producers and consumers across teams. A change that breaks compatibility must
be caught **before it merges**, not after it is already on the target branch.

## Two stages, same check, different "when"

| | Pre-merge (CI) | Post-merge (CD) |
|---|---|---|
| Runs | on every PR touching `shared/proto/**` | after merge to `main`, via ArgoCD |
| Where | GitHub Actions — [`relay-schema-ci.yaml`](../../../.github/workflows/relay-schema-ci.yaml) | in-cluster Job — [`../schema-sync-hook`](../schema-sync-hook) |
| Schema Registry | a throwaway container in the runner (`services:`), same Apicurio image as prod | the real, live registry in the cluster |
| Compared against | the schema versions on **whatever branch the PR targets** (its base branch, pulled from Git — not hardcoded to `main`) | whatever is currently registered live |
| On success | PR is mergeable | new schema version is **registered** |
| On failure | PR is blocked | Job fails, sync-wave stops |

CI never has a live cluster, so it can't ask the real registry "is this
compatible?". Instead it recreates the answer: stand up an ephemeral registry,
load the *previous* schema versions from Git into it, then run the exact same
dry-run check the cluster hook runs. Same tool, same semantics, just earlier.

The ephemeral-infra-in-CI idea is borrowed from the team's smoke tests
([`setup-service-infra`](../../../.github/actions/setup-service-infra)), which spin up
a kind cluster per run. A Schema Registry has no Helm chart yet, and needs no
Kubernetes, so a plain service container is enough here.

## Files

| File | Role |
|---|---|
| `schema-subjects.tsv` | the one list of `proto file → registry subject`, plus the `# compatibility:` level, for both CI and the CD hook. **Add a new schema here, nowhere else.** |
| `_load-schema-policy.sh` | sourced by the two scripts below; loads the list + level from `<git-ref>` (the PR's base), never from the PR. |
| `check-schema-compatibility.sh` | dry-run compatibility check for one schema against `$SCHEMA_REGISTRY_URL`. Also used verbatim by the cluster hook. |
| `register-schema.sh` | registers one schema version. Used by the cluster hook (post-merge) and by `seed-registry.sh` (to load the base-branch versions). |
| `seed-registry.sh <git-ref>` | for each schema on `<git-ref>`, loads its version into the registry, and sets the compatibility level from `<git-ref>`. `<git-ref>` is any branch/commit — CI passes the PR's base, locally you pass whichever branch you're merging into. |
| `check-all-schemas.sh <git-ref>` | runs `check-schema-compatibility.sh` for every schema on `<git-ref>` (the .proto content checked is this checkout's); fails if any is incompatible. |

## Compatibility level, and where the rules come from

The level is `FULL` — both `BACKWARD` (a new schema can read data written under
the old one) and `FORWARD` (an old schema can read data written under the new
one). Producers and consumers here evolve their schemas independently, so a
change has to be safe in both directions. Apicurio enforces nothing by default,
so both stages set it on every run: `seed-registry.sh` in CI, and the cluster
hook's `run-all.sh` before its check loop.

It is set by one line in `schema-subjects.tsv`:

```
# compatibility: FULL
```

**Both the level and the schema list are read from the PR's base branch**, never
from the PR itself — the two scripts pull them with `git show <base-ref>:...`. So
a PR cannot weaken its own gate: lowering the level or dropping a schema from the
list in the same PR has no effect on how that PR is judged. (The workflow file
still runs from the PR — it is kept thin, with the enforced rules in the scripts
and the tsv.)

### `# reset: <subject>` — pre-production escape hatch

For a change the registry rejects that we accept because nothing is in production
yet (e.g. making a proto3 field `optional`). With the line on the base branch,
`seed-registry.sh` doesn't seed that subject, so the PR's version is checked as a
new subject, and the hook's `run-all.sh` hard-deletes the subject before
registering. Land it in its own PR before the breaking one, and remove it once
the cluster has synced — otherwise every sync re-creates the subject with a new id.

## What schema changes are allowed

Two layers decide this:

1. **Protobuf wire rules** (inherent, not configurable): a field's number is its
   identity. Safe — add a field with a brand-new number, remove a field (its
   number is then retired forever), add an enum value, add a message. Breaking —
   change an existing field's type, reuse a field number, change a field's
   number.
2. **The registry's `FULL` policy** (our choice): on top of the wire rules, a
   change must keep both read directions working. For proto3 this mostly lines up
   with the wire rules above.

Not covered by either: conditional rules like "field A is required only when
field B = X". Protobuf and the registry cannot express these — they have to be
validated in application code.

## Running it locally

```bash
# throwaway registry, same image as CI and prod
docker run -d --name schema-registry -p 8081:8080 apicurio/apicurio-registry-mem:2.5.11.Final
export SCHEMA_REGISTRY_URL=http://localhost:8081/apis/ccompat/v6

# pass the branch you intend to merge into — origin/main, origin/relay-team, ...
bash seed-registry.sh origin/relay-team
bash check-all-schemas.sh origin/relay-team

docker rm -f schema-registry
```

## One list for both stages

`schema-subjects.tsv` is the only schema list. The CD hook doesn't keep its own:
[`relay-schema-sync.yaml`](../../../.github/workflows/relay-schema-sync.yaml)
copies this file into the chart as `files/schema-subjects.tsv`, and
[`../schema-sync-hook/templates/configmap.yaml`](../schema-sync-hook/templates/configmap.yaml)
renders the schema list from it.
