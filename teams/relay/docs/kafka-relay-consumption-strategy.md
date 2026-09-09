# Kafka → Relay Consumption Strategy

## Decision / Trade-off Table

| Approach | How it works | Advantages | Disadvantages | Decision |
|---|---|---|---|---|
| **Manual Partition Assignment (`assign`)** | Explicitly map Kafka partitions to Relay Pods | Efficient; each event is processed by fewer Pods | Pod failure requires partition reassignment; introduces ownership management and coordination | ❌ Rejected |
| **Redis-based Ownership** | Store `user → Relay Pod` mapping in Redis | Explicit routing and ownership; selective processing | Adds another distributed dependency; requires leases/TTL, failure handling, synchronization | ❌ Rejected |
| **Full Topic Fan-Out** | Every Relay Pod uses its own Consumer Group and consumes the entire topic; each Pod locally filters events | Very simple; no partition ownership; easy scaling and failure handling; WebSocket connections can exist on any Pod | Every Pod processes every event; higher Kafka/network/CPU cost; consumer-lag observability is split across many independent groups | ✅ **Chosen** |
| **Selective / Partition-based Distribution (native consumer group + user-keyed partitioning)** | A single shared Consumer Group per topic; Kafka's group protocol handles partition assignment and rebalancing automatically. Events are produced with the **user ID as the message key**, so all events for a given user always land on the same partition — giving natural user↔partition affinity with no custom ownership code. | Much lower processing and network overhead at scale; rebalancing, scale up/down, and failure recovery are handled natively by Kafka — no Redis, no custom leases, no manual reassignment | Requires a routing hop (or co-located WS hosting) so events reach the Pod holding the relevant WebSocket connections; consumer lag must be watched per-partition rather than per-Pod | 🔄 **Future fallback if Fan-Out becomes too expensive** |
| **Topic per Tenant** | Dynamic Kafka topics created per tenant; each Relay Pod subscribes only to the topics that have active local (WebSocket-connected) users | Reduced read amplification per Pod; isolates tenant traffic from one another | High topic/partition count overhead on the Kafka brokers; dynamic consumer subscriptions trigger frequent rebalances; added provisioning/lifecycle complexity for topics | ❌ **Rejected** (unnecessary complexity at current scale — ~20 active connections / 500 msg/sec) |

## Core Trade-off

| | Full Fan-Out | Selective Distribution (native group + keyed partitioning) |
|---|---|---|
| **Complexity** | Low | Moderate — mostly Kafka-native, plus one internal routing hop |
| **Resource usage** | Higher | Lower |
| **Failure handling** | Simple (each Pod independent) | Handled by Kafka's group protocol (automatic rebalancing) |
| **Pod scaling** | Simple | Simple — Kafka rebalances partitions across Pods automatically |
| **Partition → Pod ownership** | ❌ No fixed ownership | ✅ Assigned by Kafka's consumer group protocol (not custom code) |
| **Extra infrastructure** | None | None (no Redis / external ownership store) |
| **WebSocket model** | Same | Same |
| **Current decision** | ✅ **Use now** | 🔄 **Fallback later if needed** |

## Topic per Tenant — Trade-off & Boundary Analysis

**Where NO (Current Context):**

- **Low connection count** — at ~20 concurrent dashboard connections, most Pods wouldn't benefit from selective routing, since active connections are sparse to begin with.
- **Low event throughput** — at 500 events/sec, reading the full topic across all Pods incurs trivial CPU/network overhead.
- **Operational simplicity** — avoids managing dynamic topic subscriptions and partition proliferation on the Kafka brokers.

**Where YES (When to Re-evaluate):**

- **High tenant volume with heavy fan-out** — if event volume grows significantly (e.g., >10k events/sec) and message processing becomes a bottleneck.
- **Strict tenant isolation / compliance** — if specific enterprise tenants require physical data segregation or dedicated SLA/rate-limiting at the message-broker layer.

## Key Decision

Start with **Full Topic Fan-Out** because the operational simplicity is more valuable at the expected scale.

If traffic grows enough that every Relay consuming the entire topic becomes too expensive, move to **Selective / Partition-based Distribution**, implemented as a single native Kafka Consumer Group with events keyed by user ID — not manual `assign()`, not Sticky Routing, and not a Redis-based ownership table. This gets the same efficiency win as those rejected approaches while letting Kafka's own group-coordination protocol handle rebalancing and failover, avoiding a home-grown ownership layer. WebSocket stays the client transport throughout, unchanged.
