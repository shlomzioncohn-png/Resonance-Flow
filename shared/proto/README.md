# shared/proto

The single copy of every Kafka event schema registered in the Schema Registry.
Owned by Relay: [`teams/relay/ci/schema-subjects.tsv`](../../teams/relay/ci/schema-subjects.tsv)
maps each file here to its subject, and
[`relay-schema-ci.yaml`](../../.github/workflows/relay-schema-ci.yaml) blocks any PR
that breaks compatibility. A file that imports another (e.g. `import "task-created-event.proto"`)
is registered with a schema reference to a subject named after the imported file
(`task-created-event.proto`), because that's the subject Confluent serializers look imports up
under. So every imported file has a "library" row in the tsv (`<file>\t<file>`), listed before its
importers. Producers then need only `auto.register.schemas=false` + `use.latest.version=true`,
with no custom reference strategy.

Never copy these files into a team's `src/main/proto/`. That folder is only for a team's own
unregistered schemas.

## How services build against it

- **Maven**: the `pom.xml` sets `protoSourceRoot` to `${proto.root}`, which defaults to the repo's
  `shared/proto`, and lists only the files that service needs under `<includes>`.
- **Docker**: a service's build context is only its own directory, so `shared/` comes in as a
  named build context called `shared`. The Dockerfile does
  `COPY --from=shared proto ./shared/proto` and passes `-Dproto.root=/app/shared/proto`.
  Wired in the service's `docker-compose.yml` (`additional_contexts`) and in its CI workflow
  (`extra_build_contexts: shared=shared`, passed through to
  [`deploy-service`](../../.github/actions/deploy-service/action.yaml)).
- **CI**: a service's workflow lists `shared/proto/**` in its `paths:` filter.

## The one remaining copy

`teams/relay/schema-sync-hook/files/schemas/` mirrors this directory, because a Helm chart
can't read files outside itself.
[`relay-schema-sync.yaml`](../../.github/workflows/relay-schema-sync.yaml) regenerates it on
every push to `main`. Never edit it by hand.
