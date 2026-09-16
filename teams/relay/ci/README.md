# Relay schema CI

Kafka event schemas (`../src/main/proto/*.proto`) are a contract between
producers and consumers across teams. A change that breaks compatibility must
be caught **before it merges**, not after it is already on the target branch.

## Two stages, same check, different "when"

| | Pre-merge (CI) | Post-merge (CD) |
|---|---|---|
| Runs | on every PR touching `src/main/proto/**` | after merge to `main`, via ArgoCD |
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
([`service-ci.yaml`](../../../.github/workflows/service-ci.yaml)), which spin up
a kind cluster per run. A Schema Registry has no Helm chart yet, and needs no
Kubernetes, so a plain service container is enough here.

## Files

| File | Role |
|---|---|
| `schema-subjects.tsv` | the one list of `proto file → registry subject`, for both CI and the CD hook. **Add a new schema here, nowhere else.** |
| `check-schema-compatibility.sh` | dry-run compatibility check for one schema against `$SCHEMA_REGISTRY_URL`. Also used verbatim by the cluster hook. |
| `register-schema.sh` | registers one schema version. Used by the cluster hook (post-merge) and by `seed-registry.sh` (to load the base-branch versions). |
| `seed-registry.sh <git-ref>` | for each schema in the tsv, loads its version from `<git-ref>` into the registry, and sets the global compatibility level. `<git-ref>` is any branch/commit — CI passes the PR's base, locally you pass whichever branch you're merging into. |
| `check-all-schemas.sh` | runs `check-schema-compatibility.sh` for every schema in the tsv; fails if any is incompatible. |

## Compatibility level

`FULL` — both `BACKWARD` (a new schema can read data written under the old one)
and `FORWARD` (an old schema can read data written under the new one). Producers
and consumers here evolve their schemas independently, so a change has to be
safe in both directions. Set via the `COMPATIBILITY_LEVEL` env var in the
workflow; Apicurio enforces nothing by default, so `seed-registry.sh` sets it
explicitly on every run.

## Running it locally

```bash
# throwaway registry, same image as CI and prod
docker run -d --name schema-registry -p 8081:8080 apicurio/apicurio-registry-mem:2.5.11.Final
export SCHEMA_REGISTRY_URL=http://localhost:8081/apis/ccompat/v6

# pass the branch you intend to merge into — origin/main, origin/relay-team, ...
bash seed-registry.sh origin/relay-team
bash check-all-schemas.sh

docker rm -f schema-registry
```

## One list for both stages

`schema-subjects.tsv` is the only schema list. The CD hook doesn't keep its own:
[`relay-schema-sync.yaml`](../../../.github/workflows/relay-schema-sync.yaml)
copies this file into the chart as `files/schema-subjects.tsv`, and
[`../schema-sync-hook/templates/configmap.yaml`](../schema-sync-hook/templates/configmap.yaml)
renders the schema list from it.
