# The pipe: Kafka → SSE for Resonance Flow

**Backend design doc · v1**

How an urgency signal gets from the moment it's computed to the moment it lands on a user's screen — without Redis, without losing anything when a pod dies, and without a reconnect storm taking the service down with it.

| | |
|---|---|
| Consumes | `resonance.task-events` |
| Produces | `resonance.task-notifications` |
| Serves | LENS (gateway + BFF) |
| Status | for review |

---

## Contents

- [00 — The ask, and what changed](#00-the-ask-and-what-changed)
- [01 — Where the pipe sits](#01-where-the-pipe-sits)
- [02 — The topic contract](#02-the-topic-contract)
- [03 — What gets pushed](#03-what-gets-pushed)
- [04 — Delivery: getting a message to the right connection](#04-delivery-getting-a-message-to-the-right-connection)
- [05 — Scaling out in Kubernetes](#05-scaling-out-in-kubernetes)
- [06 — Pitfalls & mitigations](#06-pitfalls--mitigations)
- [07 — API surface](#07-api-surface)
- [08 — Isolation & sensitive data](#08-isolation--sensitive-data)
- [09 — Observability & test plan](#09-observability--test-plan)
- [10 — Decisions & open questions](#10-decisions--open-questions)

---

## 00 — The ask, and what changed

The product answers one question — given X minutes free, what's the most urgent thing to do. Nobody is watching LENS wait for a ping the instant a task changes; the bar is lower and more useful than that: **the screen should never need a manual refresh to be right.**

The Pipe is what makes that true. It consumes processed task-notification events from Kafka and fans them out to LENS over Server-Sent Events.

The current flow has two Kafka stages:

1. The existing service consumes raw task events from the raw task-events topic.
2. A **Processing Module inside the existing service** calculates the urgency information and creates a `TaskNotification`.
3. The service publishes the resulting notification to `resonance.task-notifications`.
4. The Pipe consumes `resonance.task-notifications` and delivers relevant notifications to connected LENS clients over SSE.

The Pipe itself is deliberately kept separate from the task-processing logic. Its responsibility starts at `resonance.task-notifications` and ends at the client's SSE connection.

This design intentionally avoids Redis. Kafka provides the durable replay source, while full-topic fan-out removes the need for cross-pod routing.

---

## 01 — Where the pipe sits

The current-stage architecture is:

```text
Raw Task Events
       │
       ▼
Existing Service
       │
       ▼
Processing Module
       │
       ├── calculate urgency
       ├── determine push policy
       └── create TaskNotification
       │
       ▼
Kafka: resonance.task-notifications
       │
       │ full fan-out
       ├───────────────┬───────────────┬───────────────┐
       ▼               ▼               ▼
   Pipe Pod A       Pipe Pod B      Pipe Pod C
   pipe-pod-a       pipe-pod-b      pipe-pod-c
       │               │               │
       ▼               ▼               ▼
 local SSE map     local SSE map    local SSE map
       │               │               │
       ▼               ▼               ▼
   LENS Client      LENS Client     LENS Client
```

The Processing Module is **not a separate service at this stage**. It is a module inside the existing service responsible for consuming raw task events, performing the required calculations, and publishing the stable `TaskNotification` contract.

A future extraction into a dedicated scoring service remains possible, but is not part of the current deployment.

The transport is **SSE, not a WebSocket**, because the traffic is one-directional — the client never needs to push data back over this channel. That buys three things a WebSocket wouldn't:

- it rides plain HTTP, so it survives the proxies and load balancers already in front of every other endpoint;
- browsers reconnect it natively;
- and it carries `Last-Event-ID` as a first-class header, which is the exact hook the offline catch-up mechanism depends on.

### High-level flow

```text
Raw Task Events
      │
      ▼
Existing Service
      │
      ▼
Processing Module
      │
      │ calculate urgency
      │ create TaskNotification
      ▼
Kafka: resonance.task-notifications
      │
      │ full fan-out
      ├──────────────┬──────────────┬──────────────┐
      ▼              ▼              ▼
   Pod A           Pod B           Pod C
   full topic      full topic      full topic
      │              │              │
      ▼              ▼              ▼
 local map        local map        local map
      │
      ├── match ───────▶ SSE ──▶ LENS
      │
      └── no match ────▶ drop
```

A REST poll endpoint covers clients that cannot hold a long-lived SSE connection.

For reconnects, the client supplies `Last-Event-ID` and the receiving pod can recover missed events from its local ring buffer or directly from Kafka.

---

## 02 — The topic contract

### 2.1 Raw task-events topic

The raw topic contains the original task lifecycle events.

These events are intended for task processing and may contain information that is not relevant to the notification delivery layer.

The raw topic is keyed according to the task-event pipeline's requirements.

The Pipe does **not** consume this topic directly for SSE delivery.

Instead, the Processing Module consumes the raw events and converts the relevant information into the stable notification contract described below.

### 2.2 Notification topic

The service publishes a separate topic:

`resonance.task-notifications`

This topic is intentionally different from the raw task-events topic.

There are two important differences:

1. **Partitioning / key**

   The notification topic is keyed by:

   `tenant_id:user_id`

   This gives Kafka ordering for events belonging to the same user.

2. **Data shape**

   The notification topic contains only the information required by the notification delivery layer and the client.

   It does not carry the full raw task-event payload.

This keeps the delivery contract narrow and stable even if the internal task-event schema changes.

### Why a separate topic, not extra fields on the raw topic?

1. **Decoupling** — task-lifecycle schema changes do not directly become delivery-pipe schema changes.
2. **Partitioning** — the notification topic can be keyed for per-user ordering.
3. **Minimalism** — the notification topic stays small, making replay inexpensive.

### Current processing flow

```text
Raw Task Event
      │
      ▼
Processing Module
      │
      ├── calculate urgency_score
      ├── determine push_policy
      └── build TaskNotification
      │
      ▼
Kafka: resonance.task-notifications
```

The Processing Module is currently part of the existing service.

### Schema — v1

```protobuf
// resonance.task-notifications, v1
message TaskNotification {
  string event_id = 1;                 // UUID, minted by producer — dedup key
  string tenant_id = 2;                // partition key (with user_id)
  string user_id = 3;                  // recipient / assignee
  string task_id = 4;
  EventType event_type = 5;            // what happened
  double urgency_score = 6;
  int32 effort_estimate_minutes = 7;
  int64 occurred_at_ms = 8;            // source time, not Kafka time
  string source_ref = 9;               // traceability
  PushPolicy push_policy = 10;         // how the pipe should deliver this
}
```

```protobuf
enum EventType {
  TASK_CREATED = 0;
  TASK_STATUS_CHANGED = 1;
  TASK_TIME_ESTIMATE_UPDATED = 2;
  TASK_PRIORITY_RECALCULATED = 3;
}

enum PushPolicy {
  PUSH_ACTIVITY = 0;       // deliver immediately as its own SSE event
  PUSH_REFRESH_ONLY = 1;   // coalesced into a refresh signal
}
```

| Decision | Value | Why |
|---|---|---|
| Partition key | `tenant_id:user_id` | Every event for one user lands in one partition, giving per-user ordering |
| Dedup key | `event_id` | Producer retries may create another Kafka record with a new offset but the same logical event |
| Resume token | partition offset | Assigned by Kafka |
| Push decision | `push_policy` | Explicitly supplied by the producer rather than inferred by the Pipe |
| Retention | 7 days *(proposed)* | Covers expected offline periods while keeping replay bounded |

---

## 03 — What gets pushed

Not every message deserves an immediate push.

Some events are discrete and useful to show as activity. Others are background recalculations that may happen frequently and only indicate that the user's current ranking should be refreshed.

### `push_policy`, not `event_type`, decides

The delivery decision is carried in `push_policy`.

The Pipe does not infer the delivery behavior by pattern-matching on `event_type`.

This keeps the classification with the component that knows whether the change is meaningful to the user.

A new event type can therefore be introduced without requiring the Pipe to be redeployed just to understand its delivery behavior.

| `push_policy` | On the wire | Typical event |
|---|---|---|
| `PUSH_ACTIVITY` | Pushed immediately as its own SSE event | `TASK_CREATED`, `TASK_STATUS_CHANGED` |
| `PUSH_REFRESH_ONLY` | Not pushed individually; marks the user's rollup dirty | `TASK_TIME_ESTIMATE_UPDATED`, `TASK_PRIORITY_RECALCULATED` |

### Refresh coalescing

`PUSH_REFRESH_ONLY` events are not simply discarded.

They indicate that something relevant to the user's current ranking may have changed.

Instead of pushing every recalculation separately, the Pipe coalesces them:

```text
PUSH_REFRESH_ONLY
       │
       ▼
user marked dirty
       │
       ▼
short debounce window
       │
       ▼
one refresh signal
```

For example:

```text
100 recalculations
       │
       ▼
1 refresh signal
```

A hard ceiling is applied so a continuously changing user still receives a bounded number of refresh signals.

---

## 04 — Delivery: getting a message to the right connection

### The problem

At any moment there can be thousands of live SSE connections spread across many pods behind one Gateway/LB.

A Kafka notification is addressed to a specific user, but that user's browser can be connected to any Pipe pod.

The design therefore needs to deliver the notification to the correct connection without requiring pods to know about each other.

### Two ways to solve it

#### Option A — shared router

One pod receives the Kafka message, looks up a shared registry of:

```text
user → pod
```

and forwards the message to the correct pod.

This requires shared state such as Redis.

#### Option B — full fan-out per pod — chosen

Every Pipe pod independently consumes the entire `resonance.task-notifications` topic.

Each pod maintains only its own local connection map:

```text
user_id → active SSE connection
```

When a message arrives:

- if the user exists in the local map, the pod pushes the notification;
- if the user does not exist in the local map, the pod drops the notification.

There is no pod-to-pod forwarding.

There is no shared user-to-pod registry.

There is no sticky routing requirement.

### Why no Redis?

Redis would normally provide two capabilities:

| Redis job | Replacement |
|---|---|
| Replay buffer | Kafka retention |
| Pub/sub routing between pods | Full-topic fan-out |

Kafka already retains the durable notification history, and every Pipe pod independently receives every notification.

Therefore Redis is not required for either replay or routing.

### How full fan-out works

1. The Processing Module creates a `TaskNotification`.
2. The service publishes it to `resonance.task-notifications`, keyed by `tenant_id:user_id`.
3. **Every Pipe pod** consumes that same Kafka record using its own consumer group.
4. Each pod checks its own local `user_id → SSE connection` map.
5. A pod with no matching connection drops the message.
6. The pod holding the user's connection pushes the event to the client.

```text
Kafka: resonance.task-notifications
                  │
        ┌─────────┼─────────┬─────────┐
        ▼         ▼         ▼         ▼
      Pod A     Pod B     Pod C     Pod D
        │         │         │         │
     no match   User X    no match   no match
        │         │         │         │
       drop      push       drop      drop
                  │
                  ▼
              User X
                  │
                  ▼
                 SSE
```

### What this model does not need

- a shared cache between pods;
- a shared pub/sub bus;
- a `which pod is user X on` registry;
- direct pod-to-pod communication;
- sticky Gateway routing.

### Accepted trade-off

N pods each reading the full topic means N× read amplification against Kafka.

This is an intentional trade-off.

The system pays additional Kafka read volume in exchange for eliminating cross-pod coordination and shared routing state.

If the notification volume eventually makes this expensive, the fallback is partition ownership / conventional consumer-group scaling. That optimization is deferred until measured traffic justifies the added complexity.

---

## 05 — Scaling out in Kubernetes

The full fan-out design allows all Pipe pods to be interchangeable.

The workload therefore uses a normal Kubernetes `Deployment`, not a `StatefulSet`.

| Concern | Setting | Why |
|---|---|---|
| Workload kind | `Deployment` | Pods are interchangeable |
| Consumer group id | `pipe-${POD_NAME}` | Each pod independently consumes the full topic |
| Autoscaling | HPA on active SSE connections/pod | Connection-bound workload |
| Rolling update | `maxUnavailable: 0`, `maxSurge: 25%` | Avoid intentionally reducing capacity |
| Shutdown | `terminationGracePeriodSeconds: 30` | Stop new connections and drain existing streams |
| Readiness probe | Consumer-attached check | Do not route clients before Kafka consumption is active |
| Ingress / LB timeout | > 60s idle timeout | Must exceed the 15s heartbeat interval |

### Consumer group behavior

Each pod gets its own consumer group:

```text
Pod A → pipe-pod-a
Pod B → pipe-pod-b
Pod C → pipe-pod-c
```

These groups are intentionally separate.

Therefore:

```text
resonance.task-notifications
        │
        ├── pipe-pod-a → full topic
        ├── pipe-pod-b → full topic
        └── pipe-pod-c → full topic
```

There is no partition ownership relationship such as:

```text
partition 0 → Pod A
partition 1 → Pod B
```

Every pod independently reads the entire topic.

### Why this survives a pod dying mid-stream

A crashed pod loses only ephemeral state:

- its SSE connections;
- its local connection map;
- its optional in-memory replay buffer.

The durable source of truth is Kafka.

The client reconnects through the Gateway to any available pod and supplies `Last-Event-ID`.

That pod recovers any missing events using the recovery flow in §06.

---

## 06 — Pitfalls & mitigations

### 1 · Missed notifications while offline — data loss risk

**Cause:** The client is disconnected while events continue to arrive.

**Fix:**

```text
Client reconnect
      │
      │ Last-Event-ID
      ▼
Any Pipe Pod
      │
      ├── local ring buffer
      │       │
      │       └── small gap → replay
      │
      └── Kafka
              │
              └── large gap → replay from offset + 1
```

The local ring buffer is an optimization for small gaps.

Kafka retention is the durable recovery mechanism.

The pod can seek to the partition offset immediately after the client's last received event, replay forward, and then resume live consumption.

After replay, the connection rejoins the normal live SSE path.

### 2 · Reconnect thundering herd — availability

**Cause:** A pod restart or network problem drops many connections at once.

**Fix:**

- client reconnects use exponential backoff with jitter;
- Gateway rate-limits reconnects;
- rolling deployments use `maxUnavailable: 0`;
- recovery is driven by `Last-Event-ID`.

### 3 · Zombie connections & memory leaks — resource exhaustion

**Cause:** A client disappears without a clean connection close.

**Fix:**

- heartbeat every 15 seconds;
- write errors / broken pipes immediately remove the connection;
- inactive connections are not kept indefinitely.

### 4 · Out-of-order & duplicate delivery — correctness

**Cause:** Kafka delivery and producer retries are at-least-once.

A producer retry can create the same logical event twice with different Kafka offsets.

**Fix:**

- `tenant_id:user_id` provides per-user partition ordering;
- `event_id` provides logical-event identity;
- a small per-user recently-seen set is used for deduplication.

Kafka offsets alone cannot identify retries because the retry receives a new offset.

### 5 · Slow clients & backpressure — resource exhaustion

**Cause:** A slow client can cause its server-side output buffer to grow.

**Fix:**

- `PUSH_REFRESH_ONLY` events are coalesced before reaching the connection;
- activity events are expected to be low-rate;
- every connection has a bounded output buffer;
- a client that exceeds the buffer limit is disconnected;
- the client reconnects and recovers using `Last-Event-ID`.

### 6 · Pod crash & recovery — no durable data loss

**Cause:** A pod is killed while serving an SSE connection.

**Fix:**

Nothing durable lives exclusively on the pod.

```text
Pod crash
    │
    ▼
SSE connection lost
    │
    ▼
Client reconnects
    │
    │ Last-Event-ID
    ▼
Any available Pipe Pod
    │
    ├── ring buffer → small gap
    │
    └── Kafka replay → large gap
    │
    ▼
dedup
    │
    ▼
live SSE
```

### Important: Consumer offsets are not the recovery mechanism

Consumer-group offsets are deliberately not used as the client recovery protocol.

The Pipe does not interpret a Kafka consumer offset as the last event the client received.

Those are different concepts.

The client-driven recovery mechanism is:

```text
Last-Event-ID
      ↓
Kafka partition offset
      ↓
replay
      ↓
live SSE
```

This keeps Kafka consumption state separate from client delivery state.

### 7 · Orphaned consumer group metadata — operational cleanliness

**Cause:** Each Pipe pod runs its own throwaway consumer group (`pipe-${POD_NAME}`).

On a clean shutdown — rolling deploy, HPA scale-down, manual pod delete — the pod has an opportunity to remove its own group before exiting.

OOMKill, node eviction/preemption, and SIGKILL past the `terminationGracePeriodSeconds` window give the process no such opportunity. The group's metadata is left registered on the broker with no owner, and since pod names are never reused, nothing ever comes back to claim it.

**Fix:**

```text
SIGTERM received
      │
      ▼
drain SSE connections
      │
      ▼
delete own consumer group
      │
      ▼
process exits
```

- on `SIGTERM`, after streams have drained but before exit, the pod calls `AdminClient.deleteConsumerGroups()` on its own group id;
- an hourly `CronJob` lists all `pipe-*` groups, describes them, and deletes any with `GroupState == EMPTY`;
- the sweep is safe without a grace window — group ids are pod-name-derived and never reused, so an empty group is orphaned, not mid-restart;
- the sweep is idempotent (`concurrencyPolicy: Forbid`; deleting an already-gone group is a no-op);
- the sweep uses a scoped ACL (`DELETE` on `Group` resource, `pipe-*` pattern only).

The graceful path and the sweep are not redundant. The graceful path covers the common case — deploys and scale-downs — and keeps steady-state clutter near zero. The sweep is the backstop for every non-graceful exit, which the graceful path cannot reach by definition.

No committed offsets or delivery state are affected either way — this cleans up group registration only. The recovery mechanism in §06.6 (`Last-Event-ID` + Kafka replay) is unrelated and untouched.

Confirm whether the broker already expires empty groups with no committed offsets on its own (version/config-dependent) — if so, the sweep may only need to catch what the broker's own expiry misses.

---

### 7 · Orphaned consumer group metadata — operational cleanliness

**Cause:** Each Pipe pod runs its own throwaway consumer group (`pipe-${POD_NAME}`).

On a clean shutdown — rolling deploy, HPA scale-down, manual pod delete — the pod has an opportunity to remove its own group before exiting.

OOMKill, node eviction/preemption, and SIGKILL past the `terminationGracePeriodSeconds` window give the process no such opportunity. The group's metadata is left registered on the broker with no owner, and since pod names are never reused, nothing ever comes back to claim it.

**Fix:**

```text
SIGTERM received
      │
      ▼
drain SSE connections
      │
      ▼
delete own consumer group
      │
      ▼
process exits
```

- on `SIGTERM`, after streams have drained but before exit, the pod calls `AdminClient.deleteConsumerGroups()` on its own group id;
- an hourly `CronJob` lists all `pipe-*` groups, describes them, and deletes any with `GroupState == EMPTY`;
- the sweep is safe without a grace window — group ids are pod-name-derived and never reused, so an empty group is orphaned, not mid-restart;
- the sweep is idempotent (`concurrencyPolicy: Forbid`; deleting an already-gone group is a no-op);
- the sweep uses a scoped ACL (`DELETE` on `Group` resource, `pipe-*` pattern only).

The graceful path and the sweep are not redundant. The graceful path covers the common case — deploys and scale-downs — and keeps steady-state clutter near zero. The sweep is the backstop for every non-graceful exit, which the graceful path cannot reach by definition.

No committed offsets or delivery state are affected either way — this cleans up group registration only. The recovery mechanism in §06.6 (`Last-Event-ID` + Kafka replay) is unrelated and untouched.

Confirm whether the broker already expires empty groups with no committed offsets on its own (version/config-dependent) — if so, the sweep may only need to catch what the broker's own expiry misses.

---

## 07 — API surface

| Endpoint | Purpose | Notes |
|---|---|---|
| `GET /api/v1/events/stream` | Main SSE stream | `Last-Event-ID` optional; user/tenant resolved from auth token |
| `GET /api/v1/events/sync?since=` | REST catch-up / polling fallback | Same dedup rules apply |
| `GET /healthz` | Liveness | Process up |
| `GET /readyz` | Readiness | Kafka consumer attached and not critically lagging |

### SSE connection flow

```text
Browser
   │
   │ GET /api/v1/events/stream
   │ Last-Event-ID: <optional>
   ▼
Gateway / LB
   │
   ▼
Any Pipe Pod
   │
   ├── register user_id → SSE connection
   │
   └── start delivery
```

The Gateway does not need to know which pod previously served the user.

---

## 08 — Isolation & sensitive data

The Gateway authenticates every connection and resolves:

```text
tenant_id
user_id
```

from the authentication token.

A client cannot request another user's stream by supplying a different user ID.

The notification topic is keyed by:

```text
tenant_id:user_id
```

which also provides per-user ordering.

The payload remains deliberately thin.

An activity event contains IDs and the relevant small status information.

A refresh signal contains no task payload.

The client retrieves the full task record through the appropriate authenticated API when needed.

No meeting, email, chat, or other large raw payload is sent through the notification stream.

---

## 09 — Observability & test plan

### Metrics

Track at minimum:

- active SSE connections per pod;
- Kafka consumer lag per partition;
- replay fallback rate;
- dedup-hit rate;
- dropped slow-client count;
- number of `PUSH_REFRESH_ONLY` messages;
- number of emitted refresh signals;
- refresh coalescing ratio;
- reconnect rate;
- notification delivery failures.

### Tests

#### Unit tests

- deduplication;
- refresh coalescing;
- debounce logic;
- connection registration/removal;
- backpressure limits.

#### Integration tests

Exercise:

```text
Kafka
  ↓
Processing Module
  ↓
TaskNotification
  ↓
Pipe
  ↓
SSE
```

and:

```text
disconnect
    ↓
reconnect
    ↓
Last-Event-ID
    ↓
replay
    ↓
live SSE
```

#### Chaos test

Kill a Pipe pod while clients are connected and verify that:

- clients reconnect;
- the new pod can be any pod;
- missed events are recovered;
- duplicate logical events are deduplicated;
- no durable notification is lost.

---

## 10 — Decisions & open questions

### Current decisions

#### Processing location

The urgency calculation and `TaskNotification` creation are currently implemented as a **module inside the existing service**.

It is not a separate service at this stage.

A future extraction into a dedicated scoring service is possible if scale or ownership makes it worthwhile.

#### Notification topic

The Pipe consumes:

`resonance.task-notifications`

The topic is separate from the raw task-events topic.

#### Partition key

`tenant_id:user_id`

This provides per-user ordering through Kafka partition ordering.

#### Consumer groups

Each Pipe pod has its own consumer group:

`pipe-${POD_NAME}`

This causes every pod to consume the complete notification topic independently.

#### Routing

No sticky routing is required.

The Gateway can establish a connection to any Pipe pod.

#### Shared state

No Redis, shared registry, or pod-to-pod routing is required.

#### Recovery

Recovery is client-driven through `Last-Event-ID` and Kafka replay.

Consumer-group offsets are not used as the client recovery mechanism.

#### Durable source of truth

Kafka retention is the durable source of notification history.

The per-pod ring buffer is only an optimization for small replay gaps.

---

### Open questions

- **Partition count** for `resonance.task-notifications` — proposing 12 to start; needs a real concurrent-user estimate.
- **Retention window** — 7 days proposed; confirm against expected maximum offline duration.
- **HPA target connections/pod** — needs a load test against the real per-connection memory footprint.
- **Read amplification threshold** — define the Kafka read-volume threshold at which full-topic fan-out should be reconsidered.
- **Future extraction of Processing Module** — only if scale or ownership makes a dedicated scoring service worthwhile.
