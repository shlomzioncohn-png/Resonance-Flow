package com.rnd.relay.event;

import java.util.LinkedHashMap;
import java.util.Map;

// The wire shape for a single activity notification, shared between the WebSocket stream
// (WebSocketNotificationRegistry, as the envelope's "data" field) and the REST catch-up endpoint
// (NotificationSyncController) so the two delivery paths in design doc §07 don't drift from each other.
final class NotificationPayloads {

    private NotificationPayloads() {
    }

    static Map<String, Object> toMap(TaskNotification event, long offset) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offset", offset);
        payload.put("task_id", event.getTaskId());
        payload.put("notification_type", event.getNotificationType().name());
        payload.put("urgency_score", event.getUrgencyScore());
        payload.put("effort_estimate_minutes", event.getEffortEstimateMinutes());
        if (!event.getSourceRef().isEmpty()) {
            payload.put("source_ref", event.getSourceRef());
        }
        return payload;
    }
}
