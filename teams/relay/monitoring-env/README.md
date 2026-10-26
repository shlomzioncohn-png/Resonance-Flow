# Local Grafana

There's no shared cluster yet, so `up.sh` runs relay's monitoring on your
machine: a [kind](https://kind.sigs.k8s.io/) cluster with Prometheus and Grafana
(kube-prometheus-stack, with the same values as
[`../argocd/apps/kube-prometheus-stack.yaml`](../argocd/apps/kube-prometheus-stack.yaml))
and the `resonance-flow` chart's dashboards. No Traefik or Ingress - Grafana is
reached with `kubectl port-forward`.

It's for checking dashboards and alert rules, not for running a team's service.

## Run

Needs Docker (about 6GB of memory free), `kind`, `kubectl`, `helm` and `yq`. From
the repo root, in Git Bash on Windows or any shell on macOS/Linux:

```bash
teams/relay/monitoring-env/up.sh
```

When it's done, open **http://localhost:3000** and log in with **admin / admin**.
The script keeps the port-forward open until Ctrl+C. To open Grafana again later
without re-running it:

```bash
kubectl --context kind-rf-monitoring -n monitoring port-forward svc/monitoring-grafana 3000:80
```

The first run downloads every image and can take a long time on a slow
connection. Later runs reuse the cluster and only upgrade what changed - re-run
it after editing a dashboard or rule.

## What's installed

1. A kind cluster, `rf-monitoring`. `kind` also switches `kubectl` to it.
2. The `grafana-admin` Secret, with the password `admin` (on the shared cluster
   a hook generates a random one).
3. kube-prometheus-stack - Prometheus, Alertmanager and Grafana.
4. The `resonance-flow` chart with every service off, as in CI: its dashboards
   and alert rules, and Kafka, PostgreSQL and MinIO with their exporters, so the
   System overview dashboard has data. They keep starting after Grafana is up.

## What to check

- **Dashboards** shows the folders `platform` (System overview, Platform alerts)
  and `kubernetes` (kube-prometheus-stack's own).
- Edit a dashboard and try to save it: Grafana refuses - dashboards change only
  through git.
- **Alerting → Alert rules**, data source *Prometheus*, lists
  `resonance-flow-alerts`.

To change a dashboard, edit its JSON under `../helm/resonance-flow/dashboards/`
and re-run `up.sh`; Grafana picks it up within a minute.

## Tear down

```bash
teams/relay/monitoring-env/down.sh
```
