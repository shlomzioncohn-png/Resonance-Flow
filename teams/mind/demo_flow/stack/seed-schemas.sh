#!/bin/sh
# #359: registers every schema in the demo's empty registry the way the
# cluster does it, with Relay's own scripts (teams/relay/ci). Since #899 no
# Mind producer registers a schema itself: it looks the subject up, and an
# unregistered one stops the publish with 40401.
#
# Run by the `schema-seed` service (stack/docker-compose.yml) on the stack's
# network, with the checkout mounted at /repo. The two folders the scripts
# read, teams/relay/ci and shared/proto, are copied out of the working tree
# into a throwaway git repository, because seed-registry.sh reads the schema
# list with `git show <ref>:...`. Not a clone of /repo: in a git worktree its
# .git is a file naming the main repository by a host path the container
# cannot see. Line endings are made LF on the way: on a Windows checkout made
# before .gitattributes the scripts can be CRLF, which bash cannot run.
set -eu

apk add --no-cache bash curl jq git >/dev/null

mkdir -p /tmp/repo/teams/relay /tmp/repo/shared
cp -R /repo/teams/relay/ci /tmp/repo/teams/relay/ci
cp -R /repo/shared/proto /tmp/repo/shared/proto
find /tmp/repo -type f -exec sed -i 's/\r$//' {} +
cd /tmp/repo
git init -q
git -c user.name=demo -c user.email=demo@localhost add -A
git -c user.name=demo -c user.email=demo@localhost commit -q -m "schemas for the demo"

export SCHEMA_REGISTRY_URL=http://schema-registry:8080/apis/ccompat/v6
TSV=teams/relay/ci/schema-subjects.tsv

bash teams/relay/ci/seed-registry.sh HEAD

# seed-registry.sh leaves the "# reset:" subjects out (CI checks them as new);
# the cluster's hook deletes them and registers the current file. Same here.
grep '^# reset:' "$TSV" | sed 's/^# reset: *//' | while read -r SUBJECT; do
  FILE=$(awk -F'\t' -v s="$SUBJECT" '$2 == s { print $1 }' "$TSV")
  echo "=== registering reset subject ${SUBJECT} from shared/proto/${FILE} ==="
  bash teams/relay/ci/register-schema.sh "shared/proto/${FILE}" "$SUBJECT"
done
