# resonance-flow

Shared Helm chart for every team's service. One release, one namespace
(`resonance-flow`) - each team owns a `components.<team>` block in
[`values.yaml`](values.yaml) (image, probes, resources, env), kept up to
date by that team's own CI. ArgoCD watches this chart and keeps the
cluster in sync with it; see
[`teams/relay/argocd/README.md`](../teams/relay/argocd/README.md) for
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
