#!/usr/bin/env bash
# Deletes the kind cluster up.sh created - see README.md.
set -euo pipefail
kind delete cluster --name "${CLUSTER:-rf-monitoring}"
