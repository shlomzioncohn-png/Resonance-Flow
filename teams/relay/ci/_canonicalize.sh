#!/usr/bin/env bash
# Sourced by seed-registry.sh and register-added-schemas.sh, so they register a schema the
# same way relay-schema-sync.yaml writes it for the cluster: through schema-canonicalize.
#
# canonicalize <proto-dir> <file.proto> <out-file>
# Runs the jar with local java, or through the Maven image when java isn't installed.
# Requires: REPO_ROOT. Optional: CANONICALIZE_JAR.

CANONICALIZE_JAR="${CANONICALIZE_JAR:-${REPO_ROOT}/teams/relay/schema-canonicalize/target/canonicalize.jar}"

canonicalize() {
  local dir="$1" file="$2" out="$3"
  if [ ! -f "$CANONICALIZE_JAR" ]; then
    echo "${CANONICALIZE_JAR} not found - build it with: mvn -f teams/relay/schema-canonicalize/pom.xml package" >&2
    return 1
  fi
  if command -v java >/dev/null 2>&1; then
    java -jar "$CANONICALIZE_JAR" "$dir" "$file" 2>/dev/null > "$out"
  else
    docker run --rm -v "$(cd "$dir" && pwd):/protos" -v "$(dirname "$CANONICALIZE_JAR"):/jar" \
      maven:3.9.9-eclipse-temurin-21 java -jar /jar/canonicalize.jar /protos "$file" 2>/dev/null > "$out"
  fi
  if [ ! -s "$out" ]; then
    echo "canonicalize produced no output for ${dir}/${file}" >&2
    return 1
  fi
}
