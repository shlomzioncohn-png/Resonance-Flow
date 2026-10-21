# Service metrics

The metrics each team's service is expected to expose to Prometheus. Every service gets a technical
baseline almost for free from Spring Boot Actuator / Micrometer; on top of that each team owns a
small set of business metrics for what its service is responsible for.

- **Required** — without it the service doesn't count as monitored.
- **Recommended** — worth adding as the service matures.

Scraping is already wired: each component opts in with `components.<name>.metrics` in the shared
chart's `values.yaml`, and the chart renders a ServiceMonitor for it (see the chart README,
"Metrics scraping").

## Naming and labels

1. **Team prefix:** `bridge_`, `inbox_`, `chain_`, `mind_`, `lens_`, `relay_`. In Micrometer, name
   with dots (`bridge.tasks.published`); the registry exports `bridge_tasks_published_total`.
2. **Suffix by type:** counters `_total`, durations `_seconds` (use a Timer, not milliseconds),
   sizes `_bytes`, gauges no suffix.
3. **Don't add infra labels by hand** — `application`, `component`, `team`, `pod` and `namespace`
   are already attached.
4. **Business labels only from small closed sets** (`sourceType`, `reason`, `model`, `result`) —
   aim for fewer than 20 values per label.
5. **Never a label:** `taskId`, `userId`, `orgId`, `eventId`, full URLs, free-text error messages.
   Those belong in logs.
6. **camelCase label names** matching the event field (`sourceType`), consistent across teams.
7. **Histograms:** enable `publishPercentileHistogram` on any Timer an alert depends on, so p95 can
   be computed across pods.

## Shared baseline

| Area | Key metrics | Source | Applies to |
| --- | --- | --- | --- |
| Inbound HTTP | `http_server_requests_seconds` (`uri`, `method`, `status`, `outcome`) | Actuator, automatic | every REST service |
| Outbound HTTP | `http_client_requests_seconds` (`client_name`, `status`) | Actuator, only if the `RestClient`/`WebClient` is built from Spring's builder | bridge, lens-bff, lens-gateway |
| JVM | `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_live_threads`, `process_cpu_usage` | Actuator, automatic | every JVM service |
| Kafka producer | `spring_kafka_template_seconds` (`name`, `result`) | needs `observation-enabled: true` on the KafkaTemplate | bridge, chain, inbox |
| Kafka consumer | `spring_kafka_listener_seconds` (`name`, `result`) | needs `observation-enabled: true` on the listener container | inbox, chain, notification-delivery |
| Database | `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds` | Actuator, automatic with a DataSource | every Postgres service |
| Python | `http_request_duration_seconds`, `http_requests_total`, `process_resident_memory_bytes` | `prometheus-fastapi-instrumentator` | mind |

Consumer-group lag (`kafka_consumergroup_lag`) comes from kafka-exporter at cluster level — teams
don't implement it, they just keep a stable, meaningful `group.id`.

## bridge

Is every source syncing and reaching `bridge.structured.tasks`?

| Metric | Type | Labels | Level | Answers |
| --- | --- | --- | --- | --- |
| `bridge_tasks_published_total` | counter | `sourceType` | required | tasks published per source |
| `bridge_publish_failures_total` | counter | `sourceType` | required | failed Kafka publishes |
| `bridge_source_fetch_duration_seconds` | timer | `sourceType` | required | duration of one fetch round |
| `bridge_source_fetch_failures_total` | counter | `sourceType`, `reason` (`auth`, `rate_limit`, `timeout`, `server_error`) | required | what breaks on the source side |
| `bridge_source_last_success_timestamp_seconds` | gauge | `sourceType` | required | last successful sync — catches a stuck source that throws no errors |
| `bridge_records_rejected_total` | counter | `sourceType`, `reason` (`validation`, `mapping`) | recommended | records fetched but not turned into tasks |
| `bridge_intake_raw_published_total` | counter | `lane` | recommended | unstructured volume sent to `bridge.intake.*` |
| `bridge_source_rate_limit_remaining` | gauge | `sourceType` | recommended | API quota left (GitHub, HubSpot) |

## inbox

Does everything bridge sends land in `tasks` exactly once, and do Chain's values get applied?

| Metric | Type | Labels | Level | Answers |
| --- | --- | --- | --- | --- |
| `inbox_tasks_ingested_total` | counter | `sourceType` | required | new tasks stored; against `bridge_tasks_published_total` it shows the gap between teams |
| `inbox_duplicates_skipped_total` | counter | `sourceType` | required | idempotency-key hits; a spike means a replay or a bridge bug |
| `inbox_ingest_failures_total` | counter | `reason` (`deserialization`, `validation`, `persistence`) | required | events that failed ingestion |
| `inbox_scheduling_updates_applied_total` | counter | `result` (`applied`, `stale`, `unknown_task`) | required | `task.scheduling.updated` messages applied or rejected |
| `inbox_task_events_published_total` | counter | `eventType` (`created`, `updated`, `completed`) | required | what inbox emitted to downstream consumers |
| `inbox_ingest_lag_seconds` | timer | `sourceType` | recommended | end-to-end time from bridge event creation to storage |
| `inbox_tasks` | gauge | `status` | recommended | task count per status; refresh on a schedule (e.g. every 60 s), not per scrape |

## Chain

How fast do `is_available` and `priority_score` update after a change, and is the graph healthy?

| Metric | Type | Labels | Level | Answers |
| --- | --- | --- | --- | --- |
| `chain_scheduling_duration_seconds` | timer | `trigger` (`task_event`, `dependency_change`, `full_recompute`) | required | time to recompute availability and priority |
| `chain_scheduling_updates_published_total` | counter | — | required | `task.scheduling.updated` messages sent |
| `chain_graph_size` | gauge | `kind` (`nodes`, `edges`) | required | graph size — explains recompute-time growth |
| `chain_dependency_proposals_total` | counter | `result` (`accepted`, `rejected`, `cycle`) | required | outcome of mind's `task.dependencies.proposed` |
| `chain_available_tasks` | gauge | — | recommended | currently available tasks; near 0 means users see an empty list |
| `chain_scheduling_failures_total` | counter | `reason` | recommended | recomputes that failed and produced no update |

One task event can trigger several scheduling updates, so inbound events vs. outbound updates isn't
1:1. What should balance is `chain_scheduling_updates_published_total` against the sum over
`result` of `inbox_scheduling_updates_applied_total`.

## relay (notification-delivery)

Do notifications from `resonance.task-events-notifications` reach connected users, and how loaded
is each pod?

| Metric | Type | Labels | Level | Answers |
| --- | --- | --- | --- | --- |
| `relay_ws_active_connections` | gauge (per pod) | — | required | pod load; the intended replacement for CPU in `pipe-hpa.yaml` |
| `relay_ws_messages_delivered_total` | counter | — | required | notifications pushed to clients |
| `relay_ws_delivery_failures_total` | counter | `reason` (`closed`, `send_error`, `backpressure`) | required | sends that failed on an open connection |
| `relay_notifications_undeliverable_total` | counter | `pushPolicy` | required | consumed, but the user has no open connection on any pod |
| `relay_ws_connections_total` | counter | `result` (`accepted`, `auth_rejected`) | recommended | connect rate; exposes frontend reconnect loops |
| `relay_notification_delivery_latency_seconds` | timer | — | recommended | Kafka record timestamp to WebSocket send |

## mind

How fast and reliably does each model respond, and what is the quality of what it proposes? mind
is Python, so Kafka metrics don't come for free and are on the required list.

| Metric | Type | Labels | Level | Answers |
| --- | --- | --- | --- | --- |
| `mind_inference_duration_seconds` | histogram | `model`, `task` (`extraction`, `classification`, `scoring`, `embedding`) | required | model latency per use |
| `mind_inference_errors_total` | counter | `model`, `reason` (`timeout`, `rate_limit`, `invalid_output`) | required | failed calls |
| `mind_messages_consumed_total` | counter | `topic`, `result` (`ok`, `error`) | required | Kafka consumption |
| `mind_messages_published_total` | counter | `topic`, `result` | required | Kafka output, e.g. `task.dependencies.proposed` |
| `mind_prediction_confidence` | histogram | `task` | recommended | output confidence distribution; a sudden drop hints at drift |
| `mind_llm_tokens_total` | counter | `model`, `direction` (`input`, `output`) | recommended | token usage / cost, only with an external LLM API |
| `mind_vector_search_duration_seconds` | histogram | — | recommended | vector-store query time |

`model` values are a fixed family + version from config, not a run id or file path. Accuracy
against ground truth (precision/recall) belongs in the evaluation harness, not Prometheus.

## lens (gateway, bff)

What does the user experience, and which backend is responsible when it's slow? Both components
are tagged `team: lens`.

| Metric | Component | Type | Labels | Level | Answers |
| --- | --- | --- | --- | --- | --- |
| `lens_auth_failures_total` | lens-gateway | counter | `reason` (`missing_token`, `expired`, `invalid_signature`, `forbidden`) | required | Keycloak / token problems |
| `http_client_requests_seconds` | lens-bff, lens-gateway | timer (baseline) | `client_name` = downstream service | required | latency and errors toward inbox / Chain / mind — points at the responsible team |
| `lens_bff_aggregation_duration_seconds` | lens-bff | timer | `view` | recommended | time to build a multi-service response |
| `lens_bff_partial_responses_total` | lens-bff | counter | `view`, `missing` | recommended | responses returned without some backend's data |

lens-frontend has no metrics endpoint. Browser metrics (Web Vitals, JS errors) need a RUM tool,
not a Prometheus scrape.

## Shared infrastructure

Owned by relay. The exporters are enabled only in `environments/shared.yaml` (never in team CI) and
scraped through `infra-servicemonitors.yaml` with `team=relay`.

| Component | Exporter | What to watch |
| --- | --- | --- |
| Kafka consumer groups | kafka-exporter | `kafka_consumergroup_lag` by `consumergroup` / `topic` |
| Kafka brokers | JMX exporter | under-replicated and offline partitions, bytes in/out per broker |
| PostgreSQL | postgres-exporter | `pg_up`, `pg_stat_database_numbackends`, deadlocks, DB size |
| MinIO | built in (`/minio/v2/metrics/*`) | used vs. free capacity, S3 API errors |

Teams consuming from Kafka should tell relay their consumer-group names so lag alerts route to the
right team.

## Suggested alerts

At least one alert per team on a business metric, beyond the baseline ones. Thresholds are a
starting point — tune them after a couple of weeks of real data.

| Team | Alert | Condition |
| --- | --- | --- |
| all | target down | `up == 0` for 5 min |
| all | high 5xx rate | > 5% of `http_server_requests_seconds_count` for 10 min |
| all | growing lag | `kafka_consumergroup_lag` > 1,000 and rising for 15 min |
| bridge | stuck source | `time() - bridge_source_last_success_timestamp_seconds` > 3 sync intervals |
| inbox | tasks going missing | `rate(bridge_tasks_published_total)` exceeds ingested + duplicates by 10% for 30 min |
| Chain | slow scheduling | p95 of `chain_scheduling_duration_seconds` > 5 s |
| relay | overloaded pod | `relay_ws_active_connections` above the per-pod target (set by load testing) |
| mind | failing model | `mind_inference_errors_total` > 10% of calls for 10 min |
| lens | auth failures | `lens_auth_failures_total` 3× the previous hour |
