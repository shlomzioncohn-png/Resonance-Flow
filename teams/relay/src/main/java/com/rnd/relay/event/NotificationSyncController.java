package com.rnd.relay.event;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// The REST poll fallback from design doc §01/§07
@RestController
@RequestMapping("/api/v1/events")
public class NotificationSyncController {

    private final ActivityReplayBuffer replayBuffer;

    public NotificationSyncController(ActivityReplayBuffer replayBuffer) {
        this.replayBuffer = replayBuffer;
    }

    @GetMapping("/sync")
    public List<Map<String, Object>> sync(
            @RequestHeader("X-Org-Id") String orgId,
            @RequestHeader("X-User-Id") String userId,
            @RequestParam("since") long sinceEpochMillis) {
        return replayBuffer.since(orgId, userId, Instant.ofEpochMilli(sinceEpochMillis)).stream()
                .map(entry -> NotificationPayloads.toMap(entry.event(), entry.offset()))
                .toList();
    }
}
