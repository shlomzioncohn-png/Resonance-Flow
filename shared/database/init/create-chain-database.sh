#!/bin/bash
# Mirrors the chain-db-init ConfigMap script in
# teams/relay/helm/resonance-flow/templates/services/chain/configmap.yaml (PR #417) - Chain
# gets its own database and its own DB user on the shared Postgres instance, not the superuser
# or Inbox's resonance_flow database. Runs once, only against a fresh (empty) data volume -
# the official postgres image only executes /docker-entrypoint-initdb.d/* on first init.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username postgres <<-EOSQL
  CREATE USER chain WITH PASSWORD '${CHAIN_DB_PASSWORD}';
  CREATE DATABASE chain OWNER chain;
EOSQL
