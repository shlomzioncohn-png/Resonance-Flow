# ArgoCD bootstrap

This folder is the relay team's ArgoCD setup for this repo. It does not
install ArgoCD itself - it assumes an ArgoCD instance (with the `argocd`
namespace and CRDs) is already running on the target cluster.

It replaces an earlier setup that lived under `teams/relay/k8s/argocd/`
and discovered per-service manifests at `teams/<team>/<service>/k8s`.
That model no longer matches how services are actually deployed: every
team's service is now a `components.<team>` entry in the single shared
[`resonance-flow`](../helm/resonance-flow) Helm chart's `values.yaml`,
kept up to date by each team's own CD (e.g.
[`mind-ci.yaml`](../../../.github/workflows/mind-ci.yaml),
[`bridge-ci.yaml`](../../../.github/workflows/bridge-ci.yaml), both built
on the reusable
[`service-update-manifest.yaml`](../../../.github/workflows/service-update-manifest.yaml)), which
bumps the image tag and opens a PR to `main`. ArgoCD's job is just
to watch that chart and keep the cluster in sync with it - a classic
push-based GitOps flow, no image updater needed.

`Kafka` itself is also no longer a standalone chart/Application - it's a
Bitnami subchart declared as a `dependency` in the chart's own
`Chart.yaml` (gated on `kafka.enabled`), rendered into the *same* release
and namespace as every component. There is deliberately no separate
`apps/kafka.yaml` anymore.

Everything here runs under ArgoCD's built-in `default` AppProject - no
dedicated project is needed for this repo's scope.

| File                     | Purpose                                                                 |
|--------------------------|--------------------------------------------------------------------------|
| `root-app.yaml`          | App-of-apps bootstrap `Application` - manages everything under `apps/` |
| `argocd-cm-patch.yaml`   | Restores the `argoproj.io/Application` health check ArgoCD dropped in 1.8 (with a fix for a "Missing" status edge case) - kept in case a future child `Application` needs sync-wave gating on another `Application`'s health; nothing under `apps/` currently uses more than one wave |
| `apps/resonance-flow.yaml` | `Application` syncing the [`resonance-flow`](../helm/resonance-flow) chart (all teams' services, including the Kafka/Postgres/MinIO subcharts), with [`environments/shared.yaml`](../helm/resonance-flow/environments/shared.yaml) layered on top for shared-cluster-only settings |
| `apps/traefik.yaml`      | `Application` installing the Traefik ingress controller (namespace `traefik`, ingress class `traefik`) that serves the chart's Ingress |
| `apps/kube-prometheus-stack.yaml` | `Application` installing cluster monitoring (Prometheus Operator, Prometheus, Alertmanager, node-exporter, kube-state-metrics) into namespace `monitoring` - see [Monitoring](#monitoring) |
| `apps/kafka-exporter.yaml` | `Application` installing `kafka-exporter` into namespace `monitoring`, for Kafka consumer-group lag - see [Monitoring](#monitoring) |

## Onboarding your team's service

There is nothing to add here. Once your team's CI writes a
`components.<your-team>` block into
[`resonance-flow/values.yaml`](../helm/resonance-flow/values.yaml)
(mirror `mind-ci.yaml` or `bridge-ci.yaml`), the next
`apps/resonance-flow.yaml` sync picks it up automatically as part of the
same Helm release.

Note: `resonance-flow/` is a chart shared across all teams - coordinate
chart/template changes with its owner rather than editing it unilaterally.

If you're pulling images from private GHCR packages on your own cluster
(e.g. local `kind`), see
[`resonance-flow/README.md`](../helm/resonance-flow/README.md#local-image-pull-authentication)
for how to wire up a personal PAT - the chart creates the pull secret
for you.

## Known limitation

All components render from the *same* Helm release into the *same*
namespace (`resonance-flow`) - the chart doesn't currently support a
per-component namespace override. This also means a broken value (bad
image tag, template error) in any one team's block can block sync for
every other team's service in the same `Application`. Splitting into
per-team releases/namespaces would fix both, but that's a chart-level
change for its owner to weigh in on, not something bundled into this
bootstrap.

## Registering this against a running ArgoCD instance

```bash
kubectl apply -f teams/relay/argocd/argocd-cm-patch.yaml
kubectl apply -f teams/relay/argocd/root-app.yaml
```

Then `argocd app list` should show `root` and `resonance-flow`;
`argocd app get resonance-flow` to check the umbrella chart's status.

If `teams-mind-api`/`teams-relay-*`-style Applications from the old
directory-generator setup were ever applied to a live cluster, delete
them explicitly (`argocd app delete <name>`) - they're orphaned now that
`teams/relay/k8s/argocd/` is gone from `main`.

## Monitoring

`apps/kube-prometheus-stack.yaml` installs the
[kube-prometheus-stack](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
chart as its own `Application` in its own `monitoring` namespace - not as a
subchart of `resonance-flow`, so a broken monitoring stack never blocks any
team's sync.

- **Ordering.** It's on sync-wave `3`, after `resonance-flow`. The chart's
  `templates/servicemonitor.yaml` only renders once the
  `monitoring.coreos.com/v1` CRDs exist, so on a fresh cluster
  `resonance-flow`'s first sync skips ServiceMonitors, and ArgoCD's next
  refresh adds them once this app has installed the CRDs. `traefik` and
  `kafka-exporter` are on wave `4`, after it: their charts render a
  ServiceMonitor without that check (Traefik's fails its render outright), and
  `argocd-cm-patch.yaml` gates each wave on the previous one's health.
- **What gets scraped.** Prometheus selects every ServiceMonitor, PodMonitor
  and PrometheusRule in the cluster, not only ones labelled for its own
  release. A team opts its component in with `components.<team>.metrics`
  - see the chart's [README](../helm/resonance-flow/README.md#metrics-scraping-metrics).
- **Infra targets.** Kafka (JMX), PostgreSQL (postgres-exporter) and MinIO
  are scraped through the chart's `templates/infra-servicemonitors.yaml`,
  their exporters turned on in `environments/shared.yaml` (so not in CI).
  Traefik exposes its own, per router and service. Kafka consumer-group lag
  (`kafka_consumergroup_lag`) comes from `apps/kafka-exporter.yaml`, which
  logs in with the `kafka.sasl.client` user - keep its password in sync.
- **Storage.** 15 days, capped at 9GB, on a 10Gi PVC from the cluster's
  default StorageClass.
- **Not scraped.** etcd, kube-scheduler, kube-controller-manager and
  kube-proxy are disabled - they aren't reachable from pod networking on
  kind or on managed clusters, and would only produce permanent
  `TargetDown` alerts.
- **Alerting.** Alertmanager runs with the chart's default rules but no
  receivers yet - alerts are visible in its UI only.

- **Grafana.** Disabled for now (the chart turns it on by default) - enabling
  and exposing it is a separate issue.

Nothing here has an Ingress. Reach Prometheus and Alertmanager with:

```bash
kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090
kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-alertmanager 9093:9093
```

## Scope

This is infrastructure for a persistent cluster with ArgoCD already
installed. It's independent of, and doesn't change, any team's own
GitHub Actions CI/CD, which continues to build/test/push images and bump
`resonance-flow/values.yaml` however it already does.

Note: GitHub Actions only discovers workflow files under
`.github/workflows/` at the **repo root** - a workflow placed inside a
team's own folder is silently never triggered. Each team should add its
workflow to the root `.github/workflows/` with a unique,
team/service-prefixed filename (e.g. `<team>-<service>-ci.yaml`), a
`paths:` filter scoped to its own `teams/<team>/<service>/**`, and a
job-level `working-directory` pointing at its service folder, so builds
don't run against (or get triggered by) other teams' changes.
