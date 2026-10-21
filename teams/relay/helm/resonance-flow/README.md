# resonance-flow

Shared Helm chart for every team's service. One release, one namespace
(`resonance-flow`) - each team owns a `components.<team>` block in
[`values.yaml`](values.yaml) (image, probes, resources, env), kept up to
date by that team's own CI. ArgoCD watches this chart and keeps the
cluster in sync with it; see
[`teams/relay/argocd/README.md`](../../argocd/README.md) for
how that's wired up.

Note: this chart is shared across all teams - coordinate chart/template
changes with its owner rather than editing it unilaterally.

## Local image pull authentication

Images live in private GHCR packages
(`ghcr.io/kamatechorg/resonance-flow/*`). To pull them on your own
cluster (e.g. a local `kind` cluster), you need pull credentials -
Kubernetes doesn't have any by default.

**Each developer creates their own personal GitHub PAT**, scoped to
`read:packages`, and passes it to the chart. The chart creates the
`Secret` for you from these `imagePullSecret.*` values:

```bash
helm upgrade --install resonance-flow ./resonance-flow \
  --set imagePullSecret.create=true \
  --set imagePullSecret.username=<your-github-username> \
  --set imagePullSecret.password=<your-PAT>
```

Or put them in a local values file (already covered by the repo's
`.gitignore` if named `*.local.yaml` / `values.local.yaml`):

```yaml
# values.local.yaml - not committed
imagePullSecret:
  create: true
  username: <your-github-username>
  password: <your-PAT>
```

```bash
helm upgrade --install resonance-flow ./resonance-flow -f values.local.yaml
```

**Never commit a real PAT** - not in `values.yaml`, not in a
per-team `values-<team>.yaml`, not anywhere tracked by git.

### Cloud / production deployment

Not solved yet - a personal PAT isn't appropriate for a shared/production
cluster. That's a separate, deliberately deferred problem (a
non-personal credential + an automated way to provision it); it isn't
part of this local-auth mechanism and shouldn't block using it for
dev/test.

## Waiting for dependencies (Kafka, Postgres, MinIO)

If your component needs an infra dependency reachable before its main
container starts, set `dependencies` (globally or under your
`components.<name>`) as a list of `{name, port, host?}` entries, and the
chart adds a `wait-for-<name>` initContainer that blocks until that
dependency's `host:port` is TCP-reachable.

By default the host isn't a literal - it's computed at render time as
`<release-name>-<name>` (`resonance-flow.dependencyHost` in `_helpers.tpl`),
since our own Kafka/Postgres/MinIO are Bitnami subcharts that name their
Service that way regardless of what this release is called. If a component
depends on one of these and its `enabled` flag is `false`, its pod blocks in
`Init:0/1` forever waiting for a Service that was never created.

Set `host` explicitly to point at an external dependency instead (a managed
Postgres/Kafka/etc, not one of our own subcharts) - it overrides the computed
host entirely.

Currently wired up:

- **Kafka** - every component depends on it, so it's set globally
  (`dependencies` in `values.yaml`).
- **Postgres** - `inbox`, `mind`, and `chain` (Chain with its own database and
  user - see [Chain's DB credentials](#chains-db-credentials-componentschain);
  Inbox with its own user - see [Inbox's DB credentials](#inboxs-db-credentials-componentsinbox)).
  pgvector's `vector.so` is overlaid from the official `pgvector/pgvector` image:
  Bitnami's own build crashes (SIGILL) building HNSW indexes on non-AVX-512 CPUs
  (#490). The Bitnami image is pinned by digest since the overlay must match its
  Postgres major and pgvector version.
- **MinIO** - `bridge` and `mind` use it.

A `dependencies` list set in `values-<service>.yaml` **replaces** the one in
`values.yaml` (Helm merges maps, not lists) - repeat every entry, don't just add.

CI's `setup-service-infra` port-forwards this Postgres to `localhost:5432` and
exports `PG*` / `TEST_DATABASE_URL` (superuser, `resonance_flow`) to the test step
of **every** service whose `values-<service>.yaml` enables `postgresql`.

```yaml
components:
  inbox:
    dependencies:
      - name: postgresql
        port: 5432
      # - name: postgresql
      #   host: my-managed-instance.rds.amazonaws.com
      #   port: 5432
```

For anything the TCP-reachability check can't express, drop a raw
container spec into `initContainers` (globally or per-component) instead
- it's appended after the generated `dependencies` containers.

## Public entry point (`ingress`)

Every component's Service is `ClusterIP`. Browsers reach the cluster through one
Ingress (`templates/ingress.yaml`): a single host, routed by path prefix to a
component's Service, so the browser sees one origin (no CORS, one cert):

```text
https://<host>/      -> lens-frontend
https://<host>/api   -> lens-gateway
https://<host>/auth  -> lens-gateway
```

`ingress.enabled` is `false` by default, so teams' kind CI never renders it. The
shared cluster turns it on in [`environments/shared.yaml`](environments/shared.yaml),
which ArgoCD loads after `values.yaml` and CI never loads. That file sets
`ingress.host`, `ingress.className` (`traefik`, from
[`apps/traefik.yaml`](../../argocd/apps/traefik.yaml)) and `ingress.tlsSecretName`.
The chart doesn't install an ingress
controller or issue the certificate; cert-manager is wired in through
`ingress.annotations` (e.g. `cert-manager.io/cluster-issuer`). Traefik redirects
every plain-HTTP request to HTTPS (`ports.web.http.redirections` in `apps/traefik.yaml`),
since `/auth/*` carries LENS passwords. With `ingress.tlsSecretName` empty, HTTPS
still works but serves Traefik's self-signed default certificate - fine for local
testing only.

Paths are forwarded as-is (no rewrite), so lens-gateway receives `/api/...` and
`/auth/...`. Only browser-facing components belong in `ingress.paths`. A path
that routes to a disabled or unknown component fails the render.

The ingress controller reaches pods as a separate source, from its own namespace.
Don't add `networkPolicy` to a component listed in `ingress.paths` unless you
also allow the controller's namespace.

## Restricting callers (`networkPolicy`)

By default any pod in the cluster can connect to any other. A component that
trusts its caller declares `networkPolicy.allowFrom`, the list of components
whose pods may connect to its `http` port. `templates/networkpolicy.yaml` then
denies every other in-cluster source:

| Component | `allowFrom` | Why |
|---|---|---|
| `notification-delivery` | `lens-bff` | trusts `X-Org-Id` / `X-User-Id` as-is |
| `lens-bff` | `lens-gateway` | `/internal/auth/*` checks passwords |

kubelet probes and `kubectl port-forward` aren't affected, because traffic from
the pod's own node is always allowed. The rules only take effect if the cluster's
CNI enforces NetworkPolicy. On a CNI that doesn't, they render but block nothing.
The policy applies to the whole pod, not to individual paths.

## Metrics scraping (`metrics`)

Prometheus (installed by `teams/relay/argocd/apps/kube-prometheus-stack.yaml`)
scrapes a component once it opts in:

```yaml
components:
  bridge:
    metrics:
      enabled: true
      path: /prometheus        # default /actuator/prometheus
      interval: 30s            # default
      team: bridge             # default: components.bridge.team, else the component name
```

`templates/servicemonitor.yaml` then renders a ServiceMonitor for the component's
Service, on its `http` port. Every series gets two labels: `component` (the
`components.<name>` key) and `team` (`components.<name>.team`, else
`metrics.team`, else the component name), for per-team dashboards and alert
routing. Set `components.<name>.team` on a component that isn't scraped but whose
alerts should still reach its team (e.g. `lens-frontend`) - see "Alerting".

- **The app must expose the endpoint first.** For Spring Boot that's
  `io.micrometer:micrometer-registry-prometheus` plus `prometheus` in
  `management.endpoints.web.exposure.include`. A component that sets
  `management.endpoints.web.base-path: /` (bridge, chain) serves it at
  `/prometheus`, not `/actuator/prometheus`.
- **Skipped without the CRD.** The template only renders when the cluster has the
  `monitoring.coreos.com/v1` CRDs. Teams' kind CI clusters don't, so CI is
  unaffected. `helm template` skips it too, unless you pass
  `--api-versions monitoring.coreos.com/v1/ServiceMonitor`.
- **NetworkPolicy.** If the component also has `networkPolicy`, Prometheus pods
  in `monitoring.namespace` (default `monitoring`) are allowed in on its `http` port.
- **Kafka, PostgreSQL, MinIO.** Their exporters are on only in
  `environments/shared.yaml`, so not in CI. `templates/infra-servicemonitors.yaml`
  scrapes them, with the same CRD check, labelled `team: relay`. Don't turn on the
  subcharts' own `metrics.serviceMonitor.enabled`: theirs skip the CRD check, and
  on a fresh cluster the failed apply would block every team's sync.
- **Kafka consumer-group lag.** The `kafka-exporter` subchart
  (prometheus-kafka-exporter), also on only in `environments/shared.yaml`. It logs
  in with the `kafka.sasl.client` credentials through
  `templates/kafka-exporter-sasl-secret.yaml` - never set its `sasl.scram`
  username/password directly. Same as above: its own ServiceMonitor stays off,
  `infra-servicemonitors.yaml` scrapes it.

## Alerting

`templates/prometheusrules.yaml` renders one PrometheusRule,
`resonance-flow-alerts`, with the same CRD check as the ServiceMonitors (so not in
teams' kind CI; `helm template` needs
`--api-versions monitoring.coreos.com/v1/PrometheusRule`). Every alert carries
`severity` (`critical` / `warning`) and `team`, which Alertmanager routes on.

| Alert | Severity | Team | Fires when |
|---|---|---|---|
| `PodCrashLooping` | critical | owning team | a container in the namespace has been in CrashLoopBackOff for 5m |
| `PodOOMKilled` | warning | owning team | a container restarted in the last 10m after an OOMKill |
| `BridgeTasksConsumerLagHigh` | warning | `alerts.bridgeTasksLag.team` (inbox) | a consumer group's total lag on `bridge.structured.tasks` is above `alerts.bridgeTasksLag.threshold` for 5m |
| `KafkaBrokerDown` | critical | relay | the broker's JMX target has been missing or down for 2m |
| `KafkaOfflinePartitions` | critical | relay | any partition has had no leader for 1m |
| `KafkaUnderReplicatedPartitions` | warning | relay | any partition has been under-replicated for 5m |
| `PersistentVolumeFillingUp` | warning | relay | a Postgres or MinIO PVC has been over 85% full for 5m |

- **Pod alerts and `team`.** kube-state-metrics exports no pod labels, but each
  component's container is named after the component, so the template renders
  one rule per team matching `container=~"<its components>"`, with `team` set
  from `components.<name>.team` / `metrics.team` / the name (as for metrics).
  relay gets everything else in the namespace: its own components, the
  Kafka/Postgres/MinIO pods and sidecars, hook Jobs.
- **Kafka rules** need the exporters, which are on only in
  `environments/shared.yaml`: the lag rule `kafka-exporter`, the broker and
  partition rules `kafka.metrics.jmx`.
- **`KafkaUnderReplicatedPartitions` can't fire today.** Kafka runs a single
  broker with replication factor 1, so no partition can be under-replicated.
  The rule is there for when brokers are added.
- **`PersistentVolumeFillingUp` is inactive today.** `postgresql.primary.persistence`
  and `minio.persistence` are off, so there's no PVC and no
  `kubelet_volume_stats_*` series to match. It starts working once either is
  turned on - no rule change needed.
- **Default rules.** kube-prometheus-stack's own rules for components that
  aren't scraped here (etcd, controller-manager, scheduler, kube-proxy, Windows)
  are turned off in `teams/relay/argocd/apps/kube-prometheus-stack.yaml`. Its
  other defaults (e.g. `KubePodCrashLooping`) stay on cluster-wide, with no
  `team` label.

### Testing a rule by hand

On the shared cluster, after the rule is merged to `main`. Watch it go
pending → firing at `http://localhost:9090/alerts` (port-forward in
`teams/relay/argocd/README.md` "Monitoring"). For anything that changes a
resource ArgoCD manages (scaling inbox or Kafka), turn off auto-sync first -
on `root` too, or it restores `resonance-flow`'s sync policy:

```bash
kubectl -n argocd patch application root --type merge -p '{"spec":{"syncPolicy":{"automated":null}}}'
kubectl -n argocd patch application resonance-flow --type merge -p '{"spec":{"syncPolicy":{"automated":null}}}'
```

and turn it back on afterwards (patching `root` back restores `resonance-flow`).

- **`PodCrashLooping`** - `kubectl -n resonance-flow run crash-test --image=busybox --restart=Always -- sh -c 'exit 1'`
  (team relay; name the pod after a component, e.g. `bridge`, to test that
  team). Delete it afterwards.
- **`PodOOMKilled`** - a pod with a `16Mi` memory limit running
  `tail /dev/zero`.
- **`BridgeTasksConsumerLagHigh`** - scale `resonance-flow-inbox` to 0 and let
  bridge publish more than the threshold (or lower the threshold for the test).
- **`KafkaBrokerDown`** / **`KafkaOfflinePartitions`** - scale the
  `resonance-flow-kafka-controller` StatefulSet to 0. With one broker, only
  `KafkaBrokerDown` fires: the controller exposing `OfflinePartitionsCount` goes
  down with it.
- **`KafkaUnderReplicatedPartitions`** / **`PersistentVolumeFillingUp`** - not
  testable until there's a second broker / a PVC (see above).

## Component configuration (`config` / `secret`)

A `components.<name>.config` block is rendered verbatim to a `config.yaml`
file in a ConfigMap (`templates/configmap.yaml`), mounted at
`/etc/resonance-flow/config/config.yaml` (`CONFIG_FILE_PATH`). Spring Boot
services also get `SPRING_CONFIG_ADDITIONAL_LOCATION` pointed at it; other
services read the file themselves. So the `config` keys are the config-file
keys the service reads. (`inbox`, `chain` and `notification-delivery` are the
exception - they need a Spring-shaped file with values computed at render time
(Kafka/Postgres hosts, SASL config), so each renders its own ConfigMap from
`templates/services/<name>/configmap.yaml` (`notification-delivery`:
`templates/service-notification-delivery/configmap.yaml`) instead. Shared
pieces come from `_helpers.tpl`: `resonance-flow.configMapHeader`,
`resonance-flow.springKafkaBase` and `resonance-flow.springKafkaConsumerDefaults`
- use them for any new Spring component rather than copying the Kafka block.)

Secrets never go in `config` (a plain ConfigMap, readable by anyone with
access). A `components.<name>.secret` block renders a `Secret`, mounted as a
directory at `/etc/resonance-flow/secret/` — its `secrets.yaml` key becomes
`/etc/resonance-flow/secret/secrets.yaml` (`SECRETS_FILE_PATH`). The volume is
`optional: true`, so the pod starts before the Secret exists (the dir is then
empty — the loader must handle a missing file):

- `secret.create: false` (default) - the Secret is created and populated
  outside the chart (`kubectl` / sealed-secrets / external-secrets).
- `secret.create: true` - the chart renders it, values from
  `secret.data.*` (via `--set` for local testing, never committed).

### Chain's DB credentials (`components.chain`)

Chain gets its own Postgres database and user (`chain`), not Inbox's. The
password lives only in the `resonance-flow-chain-db-credentials` Secret (key
`password`). A pre-install/pre-upgrade hook Job
(`templates/services/chain/db-credentials-hook.yaml`, PreSync under ArgoCD)
creates it with a random password if it's missing, and never touches an existing
one - so no manual step, in CI or the cluster. The Secret itself isn't a Helm
resource: an offline render (ArgoCD) can't see the live password and would
regenerate it every sync. To rotate, delete the Secret and restart Postgres and
Chain after the next sync.

It's mounted as a file, never an env var: into Chain's pod (read via Spring
`configtree`) and, `optional: true`, into Postgres, whose init script
(`templates/services/chain/configmap.yaml`) creates the user/database from it.
Without the Secret, Postgres still starts and just skips Chain's database.
The init script runs only on a fresh Postgres volume.

### Inbox's DB credentials (`components.inbox`)

Same mechanism as Chain's, with the `resonance-flow-inbox-db-credentials` Secret.
Both hooks render from one template, `resonance-flow.dbCredentialsHook`
(`templates/_db-credentials-hook.tpl`); each component's `db-credentials-hook.yaml`
is a one-line include. MIND keeps its own hook, since it also creates its app
Secret. An empty password is replaced on the next sync - restart Postgres and the
component afterwards, since initdb only runs on a fresh volume. The difference: Inbox gets
its own `inbox` user, not its own database - the init script
(`templates/services/inbox/_db-init.tpl`) makes it the owner of
`postgresql.auth.database` and creates `pgcrypto` there as superuser. The
`postgres` superuser stays for CI's test step only.

### MIND (`components.mind`)

MIND's `config.yaml` is rendered by `templates/services/mind/configmap.yaml`, not by the
generic `templates/configmap.yaml` (which skips `mind`). Host and port values are computed
from `dependencies`, so they follow the release and namespace:

| `config.yaml` key | source | value |
|---|---|---|
| `vector_store.url` | `dependencies` (`postgresql`) | `host:5432` |
| `vector_store.user` / `.db` | `components.mind.config.vector_store` | `mind` / `mind` |
| `object_storage.endpoint` | `dependencies` (`minio`) | `http://host:9000` |
| `object_storage.bucket` | `components.mind.config.object_storage` | `bridge-raw` (same as Bridge) |
| `kafka.bootstrap_servers` | top-level `dependencies` (`kafka`) | `host:9092` |
| `kafka.schema_registry_url` | `dependencies` (`schema-registry`) | `http://host/apis/ccompat/v6` |
| `kafka.producer.task_creation_requested_topic` | `components.mind.config.kafka` | `task.creation.requested` |

### MIND's DB and app credentials (`components.mind`)

MIND's two Secrets are created by a pre-install/pre-upgrade hook Job
(`templates/services/mind/db-credentials-hook.yaml`), so no manual step is needed in CI or
the cluster:

- `resonance-flow-mind-db-credentials` (key `password`): a random password, mounted
  optionally into Postgres. The init script (`templates/services/mind/_db-init.tpl`,
  included from `templates/postgresql-initdb.yaml`) creates the `mind` role, database, and
  `vector` extension from it. It runs only on a fresh Postgres volume.
- `resonance-flow-mind-secret` (key `secrets.yaml`): MIND's app secret, with the same
  password. It also includes `kafka.sasl` (user and password from `kafka.sasl.client` in
  `values.yaml`) only when `components.mind.kafkaSasl` is `true`. That is the default, for
  shared clusters. `values/values-mind.yaml` sets it to `false`, because CI's Kafka broker
  runs PLAINTEXT and MIND fails the handshake if it sends SASL.

The hook creates the app Secret only when the DB Secret is newly created. If the DB Secret
already exists but the app Secret is missing, create the app Secret by hand with the same
password.

`components.mind.secret` has `create: false`, and the chart has no template for it. The app
Secret's `kafka.sasl` uses the dev credentials in `values.yaml`. A cluster with different
Kafka credentials must change them there, or recreate the Secret.

Still open: the MinIO credentials (`object_storage.access_key` / `secret_key`, empty), the
LLM key (`llm.api_key`, empty), and the LLM, OCR, and ASR providers (`local` for now).
