package com.rnd.chain.events;

import com.rnd.chain.dto.TaskTier;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.service.TaskCounterUpdater;
import com.rnd.mind.event.TaskScoreProposedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import static com.rnd.chain.events.EventFields.parseUuid;

/**
 * Consumes task.score.proposed. A task Chain doesn't know yet is created as a PENDING placeholder in
 * the event's tenant before the score is applied. A score or confidence outside [0, 1] goes straight
 * to .errors without retry.
 */
@Component
public class TaskScoreProposedEventHandler {

    private final TaskCounterUpdater taskCounterUpdater;
    private final ChainEventProcessingTemplate template;

    public TaskScoreProposedEventHandler(
            TaskCounterUpdater taskCounterUpdater,
            ChainEventProcessingTemplate template) {

        this.taskCounterUpdater = taskCounterUpdater;
        this.template = template;
    }

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
            taskCounterUpdater.onScoreProposed(taskId, tenantId, toTier(event), mindScore, confidence);
        } catch (InvalidRequestException ex) {
            throw new NonRetryableEventException(
                    "Mind proposed an invalid score for task " + taskId, ex);
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

    private static TaskTier toTier(TaskScoreProposedEvent event) {
        return switch (event.getTier()) {
            case TASK_TIER_OPERATIONAL -> TaskTier.OPERATIONAL;
            case TASK_TIER_TACTICAL -> TaskTier.TACTICAL;
            case TASK_TIER_STRATEGIC -> TaskTier.STRATEGIC;
            default -> null;
        };
    }
}
