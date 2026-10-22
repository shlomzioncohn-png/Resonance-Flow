#!/bin/sh
# #359: registers every schema in the demo's empty registry the way the
# cluster does it, with Relay's own scripts (teams/relay/ci). Since #899 no
# Mind producer registers a schema itself: it looks the subject up, and an
# unregistered one stops the publish with 40401.
#
# Run by the `schema-seed` service (stack/docker-compose.yml) on the stack's
# network. The scripts run from a clone of the repo's HEAD, not from the
# working tree: on a Windows checkout made before .gitattributes they can be
# CRLF, which bash cannot run. So a schema change must be committed to show
# up here.
set -eu

apk add --no-cache bash curl jq git >/dev/null
git config --global --add safe.directory '*'
git clone -q --shared /repo /tmp/repo
cd /tmp/repo

export SCHEMA_REGISTRY_URL=http://schema-registry:8080/apis/ccompat/v6
TSV=teams/relay/ci/schema-subjects.tsv

bash teams/relay/ci/seed-registry.sh HEAD

# seed-registry.sh leaves the "# reset:" subjects out (CI checks them as new);
# the cluster's hook deletes them and registers the current file. Same here.
grep '^# reset:' "$TSV" | sed 's/^# reset: *//' | tr -d '\r' | while read -r SUBJECT; do
  FILE=$(awk -F'\t' -v s="$SUBJECT" '{ sub(/\r$/, "") } $2 == s { print $1 }' "$TSV")
  echo "=== registering reset subject ${SUBJECT} from shared/proto/${FILE} ==="
  bash teams/relay/ci/register-schema.sh "shared/proto/${FILE}" "$SUBJECT"
done
