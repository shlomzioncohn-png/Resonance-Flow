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
- **Postgres** - `inbox` and `mind`. pgvector's `vector.so` is overlaid from
  the official `pgvector/pgvector` image: Bitnami's own build crashes (SIGILL)
  building HNSW indexes on non-AVX-512 CPUs (#490). The Bitnami image is pinned
  by digest since the overlay must match its Postgres major and pgvector version.
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

## Component configuration (`config` / `secret`)

A `components.<name>.config` block is rendered verbatim to a `config.yaml`
file in a ConfigMap (`templates/configmap.yaml`), mounted at
`/etc/resonance-flow/config/config.yaml` (`CONFIG_FILE_PATH`). Spring Boot
services also get `SPRING_CONFIG_ADDITIONAL_LOCATION` pointed at it; other
services read the file themselves. So the `config` keys are the config-file
keys the service reads. (`inbox` is the exception - it needs a Spring-shaped
file, from `templates/service-inbox/configmap.yaml` instead.)

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

### MIND (`components.mind`)

Keys mirror MIND's `teams/mind/.env.example`; the file layout is a proposal
pending MIND's sign-off.

| `config.yaml` key | `.env.example` var | value | status |
|---|---|---|---|
| `object_storage.endpoint` | `OBJECT_STORAGE_ENDPOINT` | `http://resonance-flow-minio...:9000` | real (chart MinIO) |
| `kafka.bootstrap_servers` | (new - not in `.env.example` yet) | `resonance-flow-kafka...:9092` | real |
| `kafka.producer.task_creation_requested_topic` | (new) | `task.creation.requested` | agreed (inbox consumes it) |
| `vector_store.user` / `.db` | `VECTOR_STORE_USER` / `_DB` | `mind` | assumed (pgvector, per mind-relay-handoff) |
| `llm.cache_dir` | `MODEL_CACHE_DIR` | `./models` | from `.env.example` |
| `asr.whisper_model` | `WHISPER_MODEL` | `base` | default |
| `service.log_level` | `LOG_LEVEL` | `INFO` | default |
| `vector_store.url` | `VECTOR_STORE_URL` | - | **TBD** - pgvector not deployed anywhere yet |
| `llm.model` | `LLM_MODEL` | - | **TBD** - provider not chosen |
| `ocr.provider` / `asr.provider` | `OCR_PROVIDER` / `ASR_PROVIDER` | `local` | **TBD** - local vs managed |
| `object_storage.bucket` | `OBJECT_STORAGE_BUCKET` | - | **TBD** - Bridge's raw-file bucket |

`secrets.yaml` keys (`components.mind.secret`):

| `secrets.yaml` key | `.env.example` var | status |
|---|---|---|
| `llm.api_key` | `LLM_API_KEY` | pending |
| `vector_store.password` | `VECTOR_STORE_PASSWORD` | pending |
| `object_storage.access_key` | `OBJECT_STORAGE_ACCESS_KEY` | pending |
| `object_storage.secret_key` | `OBJECT_STORAGE_SECRET_KEY` | pending |

See issue #278.
