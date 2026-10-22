package com.rnd.chain.events;

import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TenantReassignmentException;
import com.rnd.chain.service.TaskCounterUpdater;
import com.rnd.mind.event.TaskScoreProposedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.rnd.chain.events.EventFields.parseUuid;
import static com.rnd.chain.events.EventFields.toLocalDateTime;

/**
 * Consumes task.score.proposed. A task Chain doesn't know yet is created as a PENDING placeholder in
 * the event's tenant before the score is applied. A score or confidence outside [0, 1] goes straight
 * to .errors without retry, and so does a tenant that differs from the stored one. An event scored
 * earlier than the stored score is ignored.
 */
@RequiredArgsConstructor
@Component
public class TaskScoreProposedEventHandler {

    private final TaskCounterUpdater taskCounterUpdater;
    private final ChainEventProcessingTemplate template;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = ".errors",
            exclude = NonRetryableEventException.class,
            kafkaTemplate = "taskScoreProposedEventKafkaTemplate")
    @KafkaListener(
            topics = "${chain.kafka.topics.score-proposed:task.score.proposed}",
            groupId = "${chain.kafka.consumer-group-id}",
            containerFactory = "taskScoreProposedEventKafkaListenerContainerFactory")
    @Transactional
    public void onMessage(TaskScoreProposedEvent event) {

        UUID eventId = parseUuid(event.getEventId(), "event_id");
        UUID taskId = parseUuid(event.getTaskId(), "task_id");
        UUID tenantId = parseUuid(event.getTenantId(), "tenant_id");

        template.processClaimed(
                eventId,
                "task.score.proposed",
                () -> applyScore(taskId, tenantId, event));
    }

    private void applyScore(UUID taskId, UUID tenantId, TaskScoreProposedEvent event) {

        double mindScore = toUnitRange(event.getMindScore(), "mind_score");
        double confidence = toUnitRange(event.getConfidence(), "confidence");

        try {
            LocalDateTime scoredAt = event.hasScoredAt() ? toLocalDateTime(event.getScoredAt()) : null;
            taskCounterUpdater.onScoreProposed(taskId, tenantId, InboxTaskTierMapper.map(event.getTier()), mindScore, confidence, scoredAt);
        } catch (InvalidRequestException ex) {
            throw new NonRetryableEventException(
                    "Mind proposed an invalid score for task " + taskId, ex);
        } catch (TenantReassignmentException ex) {
            // tenant_id never changes after creation, so retrying the same event never helps.
            throw new NonRetryableEventException(
                    "task.score.proposed for task " + taskId + " claims a different tenant than stored", ex);
        }
    }

    /**
     * Rounds to two decimals so a float like 0.8f doesn't become 0.800000011920929, then checks
     * [0, 1] on the rounded value so float noise right at a bound (e.g. 1.0000001f) is tolerated.
     */
    private static double toUnitRange(float raw, String fieldName) {
        if (Float.isNaN(raw) || Float.isInfinite(raw)) {
            throw new NonRetryableEventException(fieldName + " must be a finite number, got " + raw);
        }
        double rounded = BigDecimal.valueOf((double) raw).setScale(2, RoundingMode.HALF_UP).doubleValue();
        if (rounded < 0.0 || rounded > 1.0) {
            throw new NonRetryableEventException(fieldName + " must be between 0 and 1, got " + raw);
        }
        return rounded;
    }
}
