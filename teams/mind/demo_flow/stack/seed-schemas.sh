#!/usr/bin/env bash
# #359: registers every schema in the demo's empty registry the way the
# cluster does it, with Relay's own scripts (teams/relay/ci). Since #899 no
# Mind producer registers a schema itself: it looks the subject up, and an
# unregistered one stops the publish with 40401.
#
# Run by the `schema-seed` service (stack/docker-compose.yml) on the stack's
# network, with the checkout mounted at /repo, in the Maven image Relay's
# _canonicalize.sh uses: since #953 every schema is registered in its
# canonical form, written by the schema-canonicalize jar, which is built here.
#
# The folders the scripts read are copied out of the working tree into a
# throwaway git repository, because seed-registry.sh reads the schema list
# with `git show <ref>:...`. Not a clone of /repo: in a git worktree its .git
# is a file naming the main repository by a host path the container cannot
# see. Line endings are made LF on the way: on a Windows checkout made before
# .gitattributes the scripts can be CRLF, which bash cannot run.
set -euo pipefail

apt-get update -qq >/dev/null
apt-get install -y -qq --no-install-recommends curl jq git >/dev/null

mkdir -p /tmp/repo/teams/relay /tmp/repo/shared
cp -R /repo/teams/relay/ci /tmp/repo/teams/relay/ci
cp -R /repo/teams/relay/schema-canonicalize /tmp/repo/teams/relay/schema-canonicalize
rm -rf /tmp/repo/teams/relay/schema-canonicalize/target
cp -R /repo/shared/proto /tmp/repo/shared/proto
find /tmp/repo -type f \( -name '*.sh' -o -name '*.tsv' -o -name '*.proto' \) -exec sed -i 's/\r$//' {} +
cd /tmp/repo
git init -q
git -c user.name=demo -c user.email=demo@localhost add -A
git -c user.name=demo -c user.email=demo@localhost commit -q -m "schemas for the demo"

# The jar _canonicalize.sh runs. ~/.m2 is the mind-demo-m2 volume, kept across
# runs, so its dependencies are fetched once.
mvn -q -B -f teams/relay/schema-canonicalize/pom.xml package -DskipTests

export SCHEMA_REGISTRY_URL=http://schema-registry:8080/apis/ccompat/v6
export REPO_ROOT=/tmp/repo
TSV=teams/relay/ci/schema-subjects.tsv

bash teams/relay/ci/seed-registry.sh HEAD

# seed-registry.sh leaves the "# reset:" subjects out (CI checks them as new);
# the cluster's hook deletes them and registers the current file, in its
# canonical form. Same here.
source teams/relay/ci/_canonicalize.sh
CANON_DIR="$(mktemp -d)"
grep '^# reset:' "$TSV" | sed 's/^# reset: *//' | while read -r SUBJECT; do
  FILE=$(awk -F'\t' -v s="$SUBJECT" '$2 == s { print $1 }' "$TSV")
  echo "=== registering reset subject ${SUBJECT} from shared/proto/${FILE} ==="
  canonicalize shared/proto "$FILE" "${CANON_DIR}/${FILE}"
  bash teams/relay/ci/register-schema.sh "${CANON_DIR}/${FILE}" "$SUBJECT"
done
