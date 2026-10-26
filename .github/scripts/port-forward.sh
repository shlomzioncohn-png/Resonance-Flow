# Sourced by setup-service-infra (Postgres) and deploy-service/smoke-test.sh.
#
# port_forward_until <namespace> <service> <remote-port> <log> <tries> <interval> <check...>
#   Starts `kubectl port-forward` in the background and retries <check> (with
#   $LOCAL_PORT set) until it succeeds. Returns 0 with the forward still running
#   and PF_PID / LOCAL_PORT set; on failure prints why plus the log, returns 1.

port_forward_until() {
  local namespace="$1" service="$2" remote_port="$3" log="$4" tries="$5" interval="$6"
  shift 6
  LOCAL_PORT=""
  # Create it now: the background redirect may not have run yet when sed first
  # reads it, and a missing file would fail the caller (set -e).
  : > "$log"
  # ":<port>" with no local port: the OS picks a free one at bind time, so
  # parallel jobs on one host never collide. kubectl logs which one it got.
  kubectl -n "$namespace" port-forward "$service" ":$remote_port" > "$log" 2>&1 &
  PF_PID=$!
  local _
  for _ in $(seq 1 "$tries"); do
    if [ -z "$LOCAL_PORT" ]; then
      LOCAL_PORT=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9][0-9]*\) -> .*/\1/p' "$log" | head -n 1)
    fi
    if [ -n "$LOCAL_PORT" ] && "$@"; then
      return 0
    fi
    if ! kill -0 "$PF_PID" 2>/dev/null; then
      echo "port-forward to $service exited:" >&2; cat "$log" >&2
      return 1
    fi
    sleep "$interval"
  done
  echo "port-forward to $service: check failed after $tries tries (local port: ${LOCAL_PORT:-none}):" >&2
  cat "$log" >&2
  return 1
}
