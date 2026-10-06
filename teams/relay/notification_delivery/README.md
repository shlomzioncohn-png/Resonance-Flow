# notification-delivery

Pushes task notifications to users over WebSocket (`/api/v1/events/stream`).

## Sending a notification

Produce to the Kafka topic:

```
resonance.task-events-notifications
```

- Value: a `TaskNotification` protobuf, serialized with the Confluent `KafkaProtobufSerializer` (schema registry).
- Key: anything. The service re-keys every message to `org_id:user_id` and republishes it to `resonance.task-notifications`, which is internal. Don't produce to that topic directly.

## Proto

Source: [`shared/proto/task-notification.proto`](../../../shared/proto/task-notification.proto)

```proto
message TaskNotification {
  string event_id = 1;
  string org_id = 2;
  string user_id = 3;
  string task_id = 4;
  PushPolicy push_policy = 10;
  double urgency_score = 6;
  int32 effort_estimate_minutes = 7;
  google.protobuf.Timestamp occurred_at = 8;
  string source_ref = 9;
}

enum PushPolicy {
  PUSH_POLICY_UNSPECIFIED = 0;
  ACTIVITY = 1;
  ROLLUP_ONLY = 2;
}
```

| Field | Meaning |
|---|---|
| `event_id` | Unique per event. Used to dedup, so keep it the same when you retry. |
| `org_id`, `user_id` | Recipient. Both required. |
| `task_id` | The task the notification is about. |
| `push_policy` | `ACTIVITY`: pushed right away as its own event. `ROLLUP_ONLY`: never pushed alone; folded into a debounced refresh. |
| `urgency_score` | 0–10. |
| `effort_estimate_minutes` | Current effort estimate. |
| `occurred_at` | When the change happened at the source, not when you produced it. |
| `source_ref` | Pointer to the originating record, e.g. `chain:task_4471`. |
