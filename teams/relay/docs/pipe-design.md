# The pipe: Kafka → WebSocket for Resonance Flow

**Backend design doc · v1**

How an urgency signal gets from the moment it's computed to the moment it lands on a user's screen — without Redis, without losing anything when an instance dies, and without a reconnect storm taking the service down with it.

| | |
|---|---|
| Consumes | `resonance.task-events-notifications` |
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
- [11 — Configuration](#11--configuration)

---

## 00 — The ask, and what changed

The product answers one question — given X minutes free, what's the most urgent thing to do. Nobody is watching LENS wait for a ping the instant a task changes; the bar is lower and more useful than that: **the screen should never need a manual refresh to be right.**

The Pipe is what makes that true. It consumes processed task-notification events from Kafka and fans them out to LENS over WebSocket.

The current flow has two Kafka stages:

1. The existing service consumes `TaskNotification` from the `resonance.task-events-notifications` topic.
2. A **Processing Module inside the existing service** calculates things in `TaskNotification`.
3. The service publishes the resulting notification to `resonance.task-notifications`.
4. The Pipe consumes `resonance.task-notifications` and delivers relevant notifications to connected LENS clients over WebSocket.

The Pipe itself is deliberately kept separate from the task-processing logic. Its responsibility starts at `resonance.task-events-notifications` and ends at the client's WebSocket connection.

This design intentionally avoids Redis. Kafka provides the durable replay source, while full-topic fan-out removes the need for cross-instance routing.

---

## 01 — Where the pipe sits

The Processing Module is **not a separate service at this stage**. It is a module inside the existing service responsible for consuming raw task events, performing the required calculations, and publishing the stable `TaskNotification` contract.

A future extraction into a dedicated scoring service remains possible, but is not part of the current deployment.

The transport is based on WebSockets, despite the current traffic being primarily one-directional (server-to-client). While Server-Sent Events (SSE) offer a lightweight HTTP-native option, adopting WebSockets as the core transport layer provides three key advantages:

1. Industry Standard & Maintainability: WebSockets are widely adopted and standardized across modern backend ecosystems, making maintenance, onboarding, and infrastructure monitoring straightforward for the engineering team;

2. Future-Proof Architecture: Establishing a full-duplex socket from day one eliminates the need for architectural refactoring when bidirectional communication features are introduced down the road;

3. Unified Protocol: Utilizing a single protocol for real-time messaging simplifies client-side state management and streamlines edge routing.

### High-level flow

```text
               ┌─────────────────────────────────────────┐
               │         Kafka Cluster                   │
               └────────────────────┬────────────────────┘
                                    │
                                    │  1. Inbound Notification Topic
                                    ▼
                      ┌───────────────────────────┐
                      │   Your Processing Service │
                      │ ┌───────────────────────┐ │
                      │ │   Processing Module   │ │  
                      │ │ (Calculations/Logic)  │ │      
                      │ └───────────────────────┘ │
                      └─────────────┬─────────────┘
                                    │
                                    │  2. Outbound Processed Topic
                                    ▼
               ┌─────────────────────────────────────────┐
               │         Kafka Cluster                   │
               └────┬──────────────────┬─────────────┬───┘
                    │                  │             │
                    │  3. Full Fan-Out (Consumers)   │
                    ▼                  ▼             ▼
              Instance A         Instance B        Instance C
              full topic         full topic        full topic
                    │                  │                  │
                    ▼                  ▼                  ▼
              local map          local map          local map
                        │
                        ├── match ───────▶ WS ──▶ LENS
                        │
                        └── no match ────▶ drop
```

A REST poll endpoint covers clients that cannot hold a long-lived WebSocket connection.

The Pipe is not exposed to browsers directly. Its only caller is lens-bff, which holds the WebSocket to the Pipe on the user's behalf after lens-gateway has authenticated the user — see [§08](#08--isolation--sensitive-data).

For reconnects, the caller supplies the last received event ID on the handshake (`Last-Event-ID` header or `lastEventId` query parameter), and the receiving instance can recover missed events from its local ring buffer or directly from Kafka.

---

## 02 — The topic contract

The service publishes a topic:

`resonance.task-notifications`

**Partitioning / key**

   The notification topic is keyed by:

   `org_id:user_id`

   This gives Kafka ordering for events belonging to the same user.


### Schema — v1

The source of truth is `shared/proto/task-notification.proto`; this is a copy of it.

```protobuf
// resonance.task-notifications, v1
message TaskNotification {
  string event_id = 1;                       // UUID, minted by producer — dedup key
  string org_id = 2;                         // partition key (with user_id)
  string user_id = 3;                        // recipient / assignee
  string task_id = 4;
  // 5 is unused (an earlier draft had `EventType event_type = 5`; see §03 for why it isn't needed)
  double urgency_score = 6;
  int32 effort_estimate_minutes = 7;
  google.protobuf.Timestamp occurred_at = 8; // source time, not Kafka time
  string source_ref = 9;                     // traceability
  PushPolicy push_policy = 10;               // how the pipe should deliver this
}
```

```protobuf
enum PushPolicy {
  PUSH_POLICY_UNSPECIFIED = 0; // proto3 default — an unset policy is logged and ignored, never pushed
  ACTIVITY = 1;                // deliver immediately as its own WebSocket message
  ROLLUP_ONLY = 2;             // coalesced into a refresh signal
}
```

| Decision | Value | Why |
|---|---|---|
| Partition key | `org_id:user_id` | Every event for one user lands in one partition, giving per-user ordering |
| Dedup key | `event_id` | Producer retries may create another Kafka record with a new offset but the same logical event |
| Resume token | partition offset | Assigned by Kafka |
| Push decision | `push_policy` | Explicitly supplied by the producer rather than inferred by the Pipe |
| Retention | 7 days *(proposed)* | Covers expected offline periods while keeping replay bounded — see [§11](#11--configuration) |

---

## 03 — What gets pushed

Not every message deserves an immediate push.

Some events are discrete and useful to show as activity. Others are background recalculations that may happen frequently and only indicate that the user's current ranking should be refreshed.

### `push_policy` decides

The delivery decision is carried in `push_policy`.

The Pipe does not infer the delivery behavior from what kind of change happened — which is why the contract carries no `event_type` field at all.

This keeps the classification with the component that knows whether the change is meaningful to the user.

A new kind of change can therefore be introduced without requiring the Pipe to be redeployed just to understand its delivery behavior.

| `push_policy` | On the wire |
|---|---|
| `ACTIVITY` | Pushed immediately as its own WebSocket message |
| `ROLLUP_ONLY` | Not pushed individually; marks the user's rollup dirty |
| `PUSH_POLICY_UNSPECIFIED` | Not pushed; logged as a producer bug |

### Refresh coalescing

`ROLLUP_ONLY` events are not simply discarded.

They indicate that something relevant to the user's current ranking may have changed.

Instead of pushing every recalculation separately, the Pipe coalesces them:

```text
ROLLUP_ONLY
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

At any moment there can be thousands of live WebSocket connections spread across many instances behind one Service.

A Kafka notification is addressed to a specific user, but that user's browser can be connected to any Pipe instance.

The design therefore needs to deliver the notification to the correct connection without requiring instances to know about each other.

To see the decision table with the options and trade-offs go to `kafka-relay-consumption-strategy.md`

#### full fan-out per instance — chosen

Every Pipe instance independently consumes the entire `resonance.task-notifications` topic.

Each instance maintains only its own local connection map:

```text
user_id → active WebSocket connection
```

When a message arrives:

- if the user exists in the local map, the instance pushes the notification;
- if the user does not exist in the local map, the instance drops the notification.

There is no instance-to-instance forwarding.

There is no shared user-to-instance registry.

There is no sticky routing requirement.

### Why no Redis?

Redis would normally provide two capabilities:

| Redis job | Replacement |
|---|---|
| Replay buffer | Kafka retention |
| Pub/sub routing between instances | Full-topic fan-out |

Kafka already retains the durable notification history, and every Pipe instance independently receives every notification.

Therefore Redis is not required for either replay or routing.

### How full fan-out works

1. The Processing Module creates a `TaskNotification`.
2. The service publishes it to `resonance.task-notifications`, keyed by `org_id:user_id`.
3. **Every Pipe instance** consumes that same Kafka record using its own consumer group.
4. Each instance checks its own local `user_id → WebSocket connection` map.
5. An instance with no matching connection drops the message.
6. The instance holding the user's connection pushes the event to the client.

```text
Kafka: resonance.task-notifications
                       │
        ┌──────────────┼──────────────┬──────────────┐
        ▼               ▼              ▼               ▼
   Instance A       Instance B     Instance C      Instance D
        │               │              │               │
     no match         User X        no match         no match
        │               │              │               │
       drop             push           drop            drop
                         │
                         ▼
                     User X
                         │
                         ▼
                         WS
```

### What this model does not need

- a shared cache between instances;
- a shared pub/sub bus;
- a `which instance is user X on` registry;
- direct instance-to-instance communication;
- sticky Gateway routing.

### Accepted trade-off

N instances each reading the full topic means N× read amplification against Kafka.

This is an intentional trade-off.

The system pays additional Kafka read volume in exchange for eliminating cross-instance coordination and shared routing state.

If the notification volume eventually makes this expensive, the fallback is partition ownership / conventional consumer-group scaling. That optimization is deferred until measured traffic justifies the added complexity.

---

## 05 — Scaling out in Kubernetes

The full fan-out design allows all Pipe pods to be interchangeable.

The workload therefore uses a normal Kubernetes `Deployment`, not a `StatefulSet`.

| Concern | Setting | Why |
|---|---|---|
| Workload kind | `Deployment` | Pods are interchangeable |
| Consumer group id | `pipe-${INSTANCE_NAME}-<random>` | Each instance independently consumes the full topic |
| Autoscaling | CPU-based HPA for now; custom-metric HPA on active WebSocket connections/pod as a follow-up | Connections/pod is the real scaling signal for this workload, but needs a custom-metrics adapter (e.g. Prometheus Adapter) plus a load-tested target — see [§10](#10-decisions--open-questions) |
| Rolling update | `maxUnavailable: 0`, `maxSurge: 25%` | Avoid intentionally reducing capacity |
| Shutdown | `terminationGracePeriodSeconds: 30` | Stop new connections and drain existing streams |
| Readiness probe | `GET /readyz` | Do not route clients before Kafka consumption is active |
| Liveness probe | `GET /healthz` | Process up |
| Ingress | None — `ClusterIP` Service only | The Pipe's only caller is lens-bff (§08); a `NetworkPolicy` denies every other in-cluster source |
| Edge idle timeout | > 60s, on lens-gateway's Ingress / LB | The browser's long-lived connection terminates at the edge, not here; that timeout must exceed the 15s heartbeat interval and is set on the ingress controller/cloud LB, not on this Deployment |

### Consumer group behavior

Each running instance gets its own consumer group, named after `INSTANCE_NAME` — a generic
environment variable the app defines and reads (`relay.pipe.consumer-group-id` in
`application.yml`) — plus a short random suffix it generates at startup. The app has no opinion
on what supplies `INSTANCE_NAME` or how; that's deliberate, so this stays the same code whether
it's run under Kubernetes, plain `docker run`, or a bare JVM. In Kubernetes specifically, the
Helm chart is what populates it, from that pod's own `metadata.name` via the downward API
(`fieldRef: fieldPath: metadata.name`) — see [§11](#11--configuration)'s neighbor, the
`deployment.yaml` template, for that wiring. Using the downward API there rather than the
kernel `$HOSTNAME` is itself deliberate: `$HOSTNAME` only happens to match the pod name and isn't
guaranteed to (e.g. if `spec.hostname` is ever set explicitly).

If `INSTANCE_NAME` is unset, the config falls back to a fresh random UUID rather than a fixed
placeholder — so a plain local run (`mvn spring-boot:run`, `docker compose up`) always gets a
usable, already-unique id and never fails to start just for lacking one.

Inside an actual Kubernetes cluster, though, a missing `INSTANCE_NAME` means the chart's
downward-API wiring got dropped, and that's worth failing loudly for rather than quietly running
on the random fallback. `InstanceNameGuard` (`TaskNotificationConsumer`'s sibling in the `pipe` package)
checks this specifically: it reads `KUBERNETES_SERVICE_HOST`, which the kubelet sets in every pod
regardless of this service's own config (the same signal Kubernetes client libraries use to
detect in-cluster execution — the one piece of Kubernetes-specific awareness this class needs,
since detecting "am I in a cluster" isn't the same concern as naming this instance), and if
that's present while `INSTANCE_NAME` is blank, it throws `IllegalStateException` during startup
with a message naming the gap — failing the deploy with a clear, specific cause instead of a
generic "could not resolve placeholder" error, and without misfiring for local/CI runs that were
never going to have `INSTANCE_NAME` in the first place.

A present `INSTANCE_NAME` is still not proof the resulting consumer-group id is actually free to
use. Kubernetes guarantees distinct pod names, but nothing guarantees this instance isn't racing
an old instance of itself — a slow non-graceful exit whose group hasn't been swept yet (§06.7) —
or starting up while some other process already holds that exact id. So the same guard also asks
the broker directly, via `AdminClient.describeConsumerGroups`, whether a group by this id already
has live members; if it does, `InstanceNameGuard` throws instead of joining it, since two members
on one group id would silently split that instance's partition instead of each instance
independently reading the full topic, defeating the full-fan-out design in §04.

```text
Instance A → pipe-<Instance A's INSTANCE_NAME>-<random>
Instance B → pipe-<Instance B's INSTANCE_NAME>-<random>
Instance C → pipe-<Instance C's INSTANCE_NAME>-<random>
```

These groups are intentionally separate.

Therefore:

```text
resonance.task-notifications
        │
        ├── pipe-<Instance A's INSTANCE_NAME>-<random> → full topic
        ├── pipe-<Instance B's INSTANCE_NAME>-<random> → full topic
        └── pipe-<Instance C's INSTANCE_NAME>-<random> → full topic
```

There is no partition ownership relationship such as:

```text
partition 0 → Instance A
partition 1 → Instance B
```

Every instance independently reads the entire topic.

### Why this survives a pod dying mid-stream

A crashed pod loses only ephemeral state:

- its WebSocket connections;
- its local connection map;
- its optional in-memory replay buffer.

The durable source of truth is Kafka.

lens-bff reconnects to any available pod (through the Pipe's `ClusterIP` Service) and supplies `Last-Event-ID`.

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
Any Pipe Instance
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

The instance can seek to the partition offset immediately after the client's last received event, replay forward, and then resume live consumption.

After replay, the connection rejoins the normal live WebSocket path.

### 2 · Reconnect thundering herd — availability

**Cause:** An instance restart or network problem drops many connections at once.

**Fix:**

- client reconnects use exponential backoff with jitter;
- lens-gateway rate-limits browser reconnects;
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

- `org_id:user_id` provides per-user partition ordering;
- `event_id` provides logical-event identity;
- a small per-user recently-seen set is used for deduplication.

Kafka offsets alone cannot identify retries because the retry receives a new offset.

### 5 · Slow clients & backpressure — resource exhaustion

**Cause:** A slow client can cause its server-side output buffer to grow.

**Fix:**

- `ROLLUP_ONLY` events are coalesced before reaching the connection;
- activity events are expected to be low-rate;
- every connection has a bounded output buffer;
- a client that exceeds the buffer limit is disconnected;
- the client reconnects and recovers using `Last-Event-ID`.

### 6 · Pod crash & recovery — no durable data loss

**Cause:** A pod is killed while serving a WebSocket connection.

**Fix:**

Nothing durable lives exclusively on the pod.

```text
Pod crash
    │
    ▼
WebSocket connection lost
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
live WebSocket
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
live WebSocket
```

This keeps Kafka consumption state separate from client delivery state.

### 7 · Orphaned consumer group metadata — operational cleanliness

**Cause:** Each Pipe pod runs its own throwaway consumer group (`pipe-${INSTANCE_NAME}-<random>`).

On a clean shutdown — rolling deploy, HPA scale-down, manual pod delete — the pod has an opportunity to remove its own group before exiting.

OOMKill, node eviction/preemption, and SIGKILL past the `terminationGracePeriodSeconds` window give the process no such opportunity. The group's metadata is left registered on the broker with no owner, and since pod names are never reused, nothing ever comes back to claim it.

**Fix:**

```text
SIGTERM received
      │
      ▼
drain WebSocket connections
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
| `GET /api/v1/events/stream` (WebSocket upgrade) | Main WebSocket stream | Requires `X-Org-Id` / `X-User-Id` headers (400 if missing or blank); optional resume token as `Last-Event-ID` header or `lastEventId` query param (400 if not a number) — client frames are ignored |
| `GET /api/v1/events/sync?since=` | REST catch-up / polling fallback | Same identity headers; same dedup rules apply |
| `GET /healthz` | Liveness | Process up |
| `GET /readyz` | Readiness | Kafka consumer attached and not critically lagging |

### WebSocket connection flow

```text
Browser
   │  Keycloak access token
   ▼
lens-gateway (Ingress)
   │  validates the token against Keycloak
   ▼
lens-bff
   │ GET /api/v1/events/stream
   │ Upgrade: websocket
   │ X-Org-Id / X-User-Id  (from the validated token)
   │ (optional) Last-Event-ID
   ▼
Pipe Service (ClusterIP)
   │
   ▼
Any Pipe Instance
   │
   ├── register org_id:user_id → WebSocket connection
   │
   └── start delivery (pings/pongs for keepalive)
```

Neither lens-gateway nor lens-bff needs to know which instance previously served the user.

---

## 08 — Isolation & sensitive data

The Pipe never sees a token. Authentication happens upstream:

1. **lens-gateway** validates every request's Keycloak access token (issuer + signing keys).
2. **lens-bff** resolves `org_id` and `user_id` from that validated identity and forwards them to the Pipe as `X-Org-Id` / `X-User-Id`.
3. **The Pipe** trusts those headers as-is (`TrustedHeaderAuthenticator`), rejecting a missing or blank value with `400` — otherwise blank values would collapse into one shared `:` key and leak notifications between users.

Trusting headers is only safe because nothing but lens-bff can reach the Pipe:

- the Pipe has no Ingress — only a `ClusterIP` Service;
- a `NetworkPolicy` (`components.notification-delivery.networkPolicy.allowFrom: [lens-bff]` in the shared chart) denies ingress from every other pod in the cluster, so no other workload can forge the headers.

Because the only client is server-to-server, the WebSocket endpoint does not check `Origin`; browser-facing origin checks live in lens-gateway.

A browser cannot request another user's stream by supplying a different user ID — it never talks to the Pipe, and lens-bff derives the IDs from the token rather than from client input.

The notification topic is keyed by:

```text
org_id:user_id
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

- active WebSocket connections per pod;
- Kafka consumer lag per partition;
- replay fallback rate;
- dedup-hit rate;
- dropped slow-client count;
- number of `ROLLUP_ONLY` messages;
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
WebSocket
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
live WebSocket
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

The calculation of `TaskNotification` is currently implemented as a **module inside the existing service**.

It is not a separate service at this stage.

A future extraction into a dedicated scoring service is possible if scale or ownership makes it worthwhile.

#### Notification topic

The Pipe consumes:

`resonance.task-notifications`

The topic is separate from the `resonance.task-events-notifications` topic.

#### Partition key

`org_id:user_id`

This provides per-user ordering through Kafka partition ordering.

#### Consumer groups

Each running instance has its own consumer group:

`pipe-${INSTANCE_NAME}-<random>`

`INSTANCE_NAME` is a generic env var the app defines and reads — it has no built-in notion of
Kubernetes or pods. In this deployment, the Helm chart is what populates it, from the pod's own
`metadata.name` via the downward API (`fieldRef: fieldPath: metadata.name`), rather than the
kernel `$HOSTNAME`, which only happens to match the pod name. That mapping is the chart's
concern, not the app's — see the `deployment.yaml` template. The app appends a short random
suffix of its own at startup as extra headroom, and falls back to a random UUID in place of
`INSTANCE_NAME` itself if that's ever unset — which keeps plain local runs working, but is
exactly the case `InstanceNameGuard` refuses to let slide inside a real Kubernetes cluster
(detected via `KUBERNETES_SERVICE_HOST`, the one piece of Kubernetes awareness this class
needs): a missing `INSTANCE_NAME` there means the chart's downward-API wiring is broken, so it
fails startup with a specific, actionable error rather than letting the instance come up on an
unreliable fallback. The same guard also checks the id it's about to use isn't already claimed —
see [§05](#05-scaling-out-in-kubernetes).

This causes every instance to consume the complete notification topic independently.

#### Routing

No sticky routing is required.

lens-bff can establish a connection to any Pipe instance through the Pipe's `ClusterIP` Service.

#### Identity

The Pipe trusts `X-Org-Id` / `X-User-Id` from lens-bff, which is enforced as its only caller by a `NetworkPolicy` — see [§08](#08--isolation--sensitive-data).

#### Shared state

No Redis, shared registry, or instance-to-instance routing is required.

#### Recovery

Recovery is client-driven through `Last-Event-ID` and Kafka replay.

Consumer-group offsets are not used as the client recovery mechanism.

#### Durable source of truth

Kafka retention is the durable source of notification history.

The per-instance ring buffer is only an optimization for small replay gaps.

---

### Open questions

- **Partition count** for `resonance.task-notifications` — proposing 12 to start; needs a real concurrent-user estimate. Tracked in [§11](#11--configuration).
- **Retention window** — 7 days proposed; confirm against expected maximum offline duration. Tracked in [§11](#11--configuration).
- **HPA target connections/pod** — needs a load test against the real per-connection memory footprint.
- **Read amplification threshold** — define the Kafka read-volume threshold at which full-topic fan-out should be reconsidered.
- **Future extraction of Processing Module** — only if scale or ownership makes a dedicated scoring service worthwhile.

---

## 11 — Configuration

The numbers this doc treats as constants — topic retention, partition count, replay buffer size, and so on — should live in one config file instead of being scattered across prose here and hardcoded literals in the service. A single source makes them each swappable per-environment (e.g. shorter retention in staging) without hunting through the doc or the codebase, and without a rebuild.

`teams/relay/notification_delivery/main/resources/pipe-limits.yaml`, imported the same way `kafka-config.yaml` already is (`spring.config.import`, see `application.yml`):

```yaml
relay:
  kafka:
    topics:
      task-notifications:
        partitions: 12
        retention-days: 7
  pipe:
    replay-buffer:
      capacity-per-user: 200
      max-polls: 20
    refresh:
      debounce-window: 3s
      max-dirty-age: 10s
    dedup:
      ttl: 5m
    websocket:
      outbox-capacity: 32
      heartbeat-interval-seconds: 15
```

| Constant | Value | Where it's discussed |
|---|---|---|
| `kafka.topics.task-notifications.partitions` | 12 *(proposed)* | §02, §10 |
| `kafka.topics.task-notifications.retention-days` | 7 *(proposed)* | §02, §10 |
| `pipe.replay-buffer.capacity-per-user` | 200 | §06.1 |
| `pipe.replay-buffer.max-polls` | 20 | §06.1 |
| `pipe.refresh.debounce-window` | 3s | §03 |
| `pipe.refresh.max-dirty-age` | 10s | §03 (hard ceiling) |
| `pipe.dedup.ttl` | 5m | §06.4 |
| `pipe.websocket.outbox-capacity` | 32 | §06.5 |
| `pipe.websocket.heartbeat-interval-seconds` | 15 | §05, §06.3 |

Moving a value into this file doesn't settle it — partition count and retention stay marked `(proposed)` until §10's open questions are answered. It just gives them, and every other tunable, one place to be found and changed.

Partition count and retention are topic-level settings; they take effect through however the topic gets provisioned (Terraform, an admin script, `kafka-topics.sh`), not through the running service. Reading `pipe-limits.yaml` as the single source for topic provisioning as well as the app's own runtime limits is the point — but wiring provisioning to read from it is separate follow-up work, not covered by this doc.
