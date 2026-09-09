# schema-sync-hook — environment

A self-contained environment that stands up the schema-CD path — a Schema
Registry, the [`schema-sync-hook`](../schema-sync-hook) chart, and the ArgoCD
wiring that orders them — on a `kind` cluster driven by a real ArgoCD.

It lives in the repo (not throwaway scaffolding). Today it is used to verify
three runtime behaviours that `helm lint` / `helm template` can't; it is also
the starting point for a real Schema Registry deployment (issue #4) and for a
shared schema-CD dev environment.

The three behaviours it checks:

1. **The Job re-runs on a schema change** (the `checksum/schemas` annotation +
   `Replace=true,Force=true`).
2. **It checks and registers against a live registry** (`run-all.sh` sets the
   compat level, then check-then-register per schema).
3. **A failed Job stalls the sync-wave** — nothing at wave `0` deploys until the
   hook (wave `-1`) is Healthy.

The Schema Registry here is a minimal in-memory Apicurio — not the production
one (issue #4).

## Layout

```
schema-sync-hook-env/
├── kind-config.yaml             the cluster
├── schema-registry/             plain manifests: Deployment + Service (Apicurio mem)
├── wave0-canary/                a ConfigMap that only appears if ArgoCD reached wave 0
└── argocd/
    ├── root.yaml                app-of-apps — creates and wave-orders the three below
    └── apps/
        ├── 00-schema-registry.yaml   wave -2
        ├── 10-schema-sync-hook.yaml  wave -1  (points at the chart)
        └── 20-wave0-canary.yaml      wave  0
```

## Prerequisites

`docker`, [`kind`](https://kind.sigs.k8s.io/docs/user/quick-start/#installation),
`kubectl`, the [`argocd` CLI](https://argo-cd.readthedocs.io/en/stable/cli_installation/),
and a **GitHub personal access token** with read access to this (private) repo.

---

## A. Point the manifests at a branch (optional)

The manifests track `relay-team`. To run the environment against a different
branch, first push it, then:

```bash
sed -i 's#relay-team#YOUR-BRANCH#g' \
  teams/relay/schema-sync-hook-env/argocd/root.yaml \
  teams/relay/schema-sync-hook-env/argocd/apps/*.yaml
git add -A && git commit -m "env: point at YOUR-BRANCH" && git push
```

ArgoCD reads the manifests from GitHub, not from your working tree — so whatever
you run **must be pushed**.

## B. Create the cluster

```bash
kind create cluster --config teams/relay/schema-sync-hook-env/kind-config.yaml
kubectl cluster-info --context kind-schema-sync-hook-env
```

## C. Install ArgoCD

```bash
# ArgoCD itself
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl -n argocd rollout status deploy/argocd-server --timeout=180s

# the health-check patch that makes the sync-waves actually gate
kubectl apply -f teams/relay/argocd/argocd-cm-patch.yaml
kubectl -n argocd rollout restart deploy/argocd-server
kubectl -n argocd rollout status deploy/argocd-server --timeout=120s
```

Log in with the CLI:

```bash
kubectl -n argocd port-forward svc/argocd-server 8080:443 &
PW=$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d)
argocd login localhost:8080 --username admin --password "$PW" --insecure
```

Give ArgoCD access to the private repo:

```bash
argocd repo add https://github.com/KamaTechOrg/Resonance-Flow.git \
  --username YOUR-GITHUB-USER --password YOUR-PAT
```

## D. Deploy

```bash
kubectl apply -f teams/relay/schema-sync-hook-env/argocd/root.yaml
argocd app sync env-root
watch argocd app list          # or: argocd app list
```

Expected order: `env-schema-registry` (wave -2) syncs and goes Healthy, then
`env-schema-sync-hook` (wave -1), then `env-wave0-canary` (wave 0).

---

## Check 1 — first run registers every schema

```bash
# the Job ran
kubectl -n schema-sync-hook get jobs,pods
JOB=$(kubectl -n schema-sync-hook get job -o name | head -1)
kubectl -n schema-sync-hook logs "$JOB"
#   expect: "Setting global compatibility level to FULL"
#           "Registered 'task-event-value' ..."  x4

# the registry actually holds them
kubectl -n schema-sync-hook port-forward svc/schema-registry 8081:8080 &
curl -s localhost:8081/apis/ccompat/v6/subjects
#   expect: ["task-event-value","task.created-value","bridge.raw.landed-value","bridge.github.tasks-value"]
curl -s localhost:8081/apis/ccompat/v6/config
#   expect: {"compatibilityLevel":"FULL"}
```

**Pass:** the Job is Complete, the 4 subjects are registered, config is `FULL`.

---

## Check 2 — a schema change re-runs the Job

```bash
# note the current hash
kubectl -n schema-sync-hook get job "$JOB" \
  -o jsonpath='{.spec.template.metadata.annotations.checksum/schemas}' ; echo
```

Make a **compatible** change and push it (both the source and the chart's mirror
copy — `relay-schema-sync.yaml` only refreshes the mirror on `main`):

```bash
# add a field to teams/relay/src/main/proto/task-event.proto, e.g.
#   string trace_id = 7;
cp teams/relay/src/main/proto/task-event.proto \
   teams/relay/schema-sync-hook/files/schemas/task-event.proto
git add -A && git commit -m "env: compatible schema change" && git push
```

```bash
argocd app sync env-schema-sync-hook          # or wait for auto-sync
kubectl -n schema-sync-hook get job
```

**Pass:** a **new** Job exists (old one deleted), its `checksum/schemas` differs
from the value you noted, its log shows the new schema registered as a new
version, and the check passed.

Revert the change afterwards:

```bash
git revert --no-edit HEAD && git push
```

---

## Check 3 — a failed Job stalls the wave

First confirm wave 0 is currently synced:

```bash
kubectl -n schema-sync-hook get configmap wave0-canary   # should exist
```

Push a **breaking** change:

```bash
# in teams/relay/src/main/proto/task-event.proto change
#   string task_id = 2;   ->   int64 task_id = 2;
cp teams/relay/src/main/proto/task-event.proto \
   teams/relay/schema-sync-hook/files/schemas/task-event.proto
git add -A && git commit -m "env: breaking schema change" && git push
```

The wave gate is deploy-time ordering, not a live circuit-breaker: `env-root`
only creates the wave-0 Application once wave -1 is Healthy. To see the gate,
force a fresh ordered deploy:

```bash
kubectl -n argocd delete application env-root env-schema-sync-hook env-wave0-canary
kubectl -n schema-sync-hook delete job --all
kubectl -n schema-sync-hook delete configmap wave0-canary --ignore-not-found
kubectl apply -f teams/relay/schema-sync-hook-env/argocd/root.yaml
```

```bash
argocd app get env-schema-sync-hook       # Health: Degraded
argocd app list                           # env-wave0-canary: not created
kubectl -n schema-sync-hook get pods       # the Job pod: Error / not Complete
kubectl -n schema-sync-hook get configmap wave0-canary   # NotFound
```

**Pass:** the hook Application is Degraded, `env-wave0-canary` is never created,
`wave0-canary` ConfigMap is absent, and the Job pod ended in error.

Revert:

```bash
git revert --no-edit HEAD && git push
kubectl apply -f teams/relay/schema-sync-hook-env/argocd/root.yaml
argocd app sync env-root
```

---

## E. Teardown

```bash
kind delete cluster --name schema-sync-hook-env
# stop any lingering port-forwards (kill %1 %2 ...)
```

If you ran step A against a different branch, revert those manifest edits too.

---

## Open questions (for the team)

- The `schema-registry/` manifests here are the *only* Schema Registry in the
  repo. Deciding the real one — own chart, part of `kafka/`, in-mem vs
  persistent — is issue #4.
- `teams/relay/schema-sync-hook/argocd-application.yaml` uses `sync-wave: "-1"`
  and `project: resonance-flow`. Once a real registry exists, its wave has to
  sit between the registry and `resonance-flow` (issue #4).
