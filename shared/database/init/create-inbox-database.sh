#!/bin/bash
# Mirrors the chart's inbox db-init script
# (teams/relay/helm/resonance-flow/templates/services/inbox/_db-init.tpl, PR #868). Runs once,
# only against a fresh (empty) data volume - like create-chain-database.sh.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username postgres <<-EOSQL
  CREATE USER inbox WITH PASSWORD '${INBOX_DB_PASSWORD}';
  CREATE DATABASE inbox OWNER inbox;
  \connect inbox
  -- As superuser, so V1's CREATE EXTENSION IF NOT EXISTS is a no-op.
  CREATE EXTENSION IF NOT EXISTS pgcrypto;
EOSQL
