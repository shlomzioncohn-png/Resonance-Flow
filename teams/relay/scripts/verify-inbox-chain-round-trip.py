#!/usr/bin/env python3
"""Live Inbox <-> Chain round trip (#387) against an installed resonance-flow release.

Real Inbox and real Chain, no simulator:
  1. Create tasks A and B, then edit them, through Inbox REST -> task.updated -> Chain
  2. Add "B depends on A" through Chain REST                  -> task.scheduling.updated -> Inbox
  3. Mark A DONE through Inbox REST                           -> task.completed -> Chain -> task.scheduling.updated -> Inbox

Prerequisites: the resonance-flow chart installed with Inbox + Chain enabled (as
setup-service-infra does: values.yaml + all-off overlay + values-inbox.yaml + values-chain.yaml),
and the schema-sync-hook chart. Needs only kubectl (current context) and the Python 3 stdlib.

Usage:
    python teams/relay/scripts/verify-inbox-chain-round-trip.py [--namespace resonance-flow]
        [--release resonance-flow] [--timeout 60]

Exits 0 only if every check passes. Each run seeds its own tenant, so it can be rerun as is.
"""

import argparse
import base64
import json
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from contextlib import contextmanager

INBOX_DB = "inbox"  # Each service has its own DB (db-credentials hook).
failures = []


def kubectl(ns, *args):
    return subprocess.run(["kubectl", "-n", ns, *args], check=True, capture_output=True, text=True).stdout


def check(name, ok, detail=""):
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" - {detail}" if detail else ""))
    if not ok:
        failures.append(name)
    return ok


def wait_for(name, probe, timeout):
    """Polls probe() -> (ok, detail) until ok or timeout; events are async, so no fixed sleeps."""
    deadline = time.monotonic() + timeout
    while True:
        ok, detail = probe()
        if ok or time.monotonic() > deadline:
            return check(name, ok, detail)
        time.sleep(2)


class Psql:
    def __init__(self, ns, release):
        self.ns, self.pod = ns, f"{release}-postgresql-0"
        secret = kubectl(ns, "get", "secret", f"{release}-postgresql", "-o", "jsonpath={.data.postgres-password}")
        self.password = base64.b64decode(secret).decode()

    def query(self, sql):
        # -qtA: bare values, no headers or command tags.
        out = kubectl(self.ns, "exec", self.pod, "-c", "postgresql", "--", "env", f"PGPASSWORD={self.password}",
                      "psql", "-U", "postgres", "-d", INBOX_DB, "-v", "ON_ERROR_STOP=1", "-qtA", "-c", sql)
        return out.strip()


@contextmanager
def port_forward(ns, service):
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    proc = subprocess.Popen(["kubectl", "-n", ns, "port-forward", f"svc/{service}", f"{port}:80"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(30):
            try:
                socket.create_connection(("127.0.0.1", port), timeout=1).close()
                break
            except OSError:
                time.sleep(1)
        else:
            sys.exit(f"port-forward to svc/{service} never came up")
        yield f"http://127.0.0.1:{port}"
    finally:
        proc.terminate()


def http(method, url, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        # Generous: a cold first request on a loaded kind host took over 10s.
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--namespace", default="resonance-flow")
    parser.add_argument("--release", default="resonance-flow")
    parser.add_argument("--timeout", type=int, default=60, help="seconds to wait for each async event")
    args = parser.parse_args()
    ns, rel, timeout = args.namespace, args.release, args.timeout

    for svc in ("inbox", "chain"):
        kubectl(ns, "rollout", "status", f"deploy/{rel}-{svc}", "--timeout=180s")

    db = Psql(ns, rel)
    run = uuid.uuid4().hex[:8]
    # No admin exists to create a tenant/user over REST, so seed them directly.
    tenant = db.query(f"insert into tenants (name, slug, status) values ('Round trip {run}', 'round-trip-{run}', "
                      f"'active') returning id;")
    user = db.query(f"insert into users (tenant_id, display_name, role, status) values ('{tenant}', "
                    f"'Round trip {run}', 'employee', 'active') returning id;")
    actor = {"X-Actor-Id": user}
    print(f"Seeded tenant {tenant}, user {user}")

    with port_forward(ns, f"{rel}-inbox") as inbox, port_forward(ns, f"{rel}-chain") as chain:
        def inbox_task(task_id):
            return http("GET", f"{inbox}/api/v1/tasks/{task_id}", headers=actor)[1]

        def eligibility(task_id):
            status, body = http("GET", f"{chain}/tasks/{task_id}/eligibility")
            return body if status == 200 else None

        def chain_ready(task_id, ready, blocking):
            def probe():
                e = eligibility(task_id)
                return bool(e) and e["isReady"] == ready and e["blockingCount"] == blocking, str(e)
            return probe

        print("\n--- 1. Inbox -> Chain: create and edit A and B (task.updated) ---")
        ids = {}
        for name in ("A", "B"):
            status, body = http("POST", f"{inbox}/api/v1/tasks",
                                {"tenant_id": tenant, "title": f"Round trip {run} {name}", "owner_id": user}, actor)
            if not check(f"Inbox created {name}", status == 201, f"HTTP {status}"):
                return 1
            ids[name] = body["id"]
            # Chain ignores task.created (#802) and learns of a task from its first task.updated.
            status, _ = http("PATCH", f"{inbox}/api/v1/tasks/{ids[name]}",
                             {"title": f"Round trip {run} {name} (edited)"}, actor)
            if not check(f"Inbox edited {name}", status == 200, f"HTTP {status}"):
                return 1
        a, b = ids["A"], ids["B"]

        wait_for("Inbox outbox published both task.updated events", lambda: (
            (n := db.query(f"select count(*) from outbox where aggregate_id in ('{a}','{b}') "
                           f"and event_type = 'TASK_UPDATED' and status = 'published';")) == "2", f"{n}/2"), timeout)
        wait_for("Chain consumed A (ready, 0 blockers)", chain_ready(a, True, 0), timeout)
        wait_for("Chain consumed B (ready, 0 blockers)", chain_ready(b, True, 0), timeout)

        print("\n--- 2. Chain -> Inbox: B depends on A (task.scheduling.updated) ---")
        b_updated = db.query(f"select updated_at from tasks where id = '{b}';")
        status, body = http("POST", f"{chain}/tasks/{b}/dependencies", {"dependsOnTaskId": a})
        check("Chain added B -> A", status == 201, f"HTTP {status}")
        check("Chain marks B not ready (1 blocker)", chain_ready(b, False, 1)()[0], str(eligibility(b)))
        # B is already unavailable by column default, so the proof is that Inbox touched the row.
        wait_for("Inbox applied B unavailable", lambda: (
            db.query(f"select updated_at > '{b_updated}' and not is_available from tasks where id = '{b}';") == "t",
            f"updated_at was {b_updated}"), timeout)

        print("\n--- 3. Inbox -> Chain -> Inbox: A DONE (task.completed, then task.scheduling.updated) ---")
        status, _ = http("PATCH", f"{inbox}/api/v1/tasks/{a}/status", {"status": "DONE"}, actor)
        check("Inbox marked A DONE", status == 200, f"HTTP {status}")
        wait_for("Inbox outbox published task.completed for A", lambda: (
            (n := db.query(f"select count(*) from outbox where aggregate_id = '{a}' "
                           f"and event_type = 'TASK_COMPLETED' and status = 'published';")) == "1", f"{n}/1"), timeout)
        wait_for("Chain unblocked B (ready, 0 blockers)", chain_ready(b, True, 0), timeout)
        wait_for("Inbox shows B available over REST", lambda: (
            (t := inbox_task(b)) is not None and t.get("available") is True, str(t)), timeout)

        # Not a failure: Chain only publishes on a readiness flip, so a never-blocked task stays
        # unavailable in Inbox (#387 finding 4).
        a_available = (inbox_task(a) or {}).get("available")
        print(f"\n[NOTE] A (never blocked) available in Inbox: {a_available}")

    print(f"\n{'All checks passed' if not failures else f'{len(failures)} check(s) failed: ' + ', '.join(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
