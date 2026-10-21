package com.rnd.chain.events;

import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.inbox.event.TaskCompletedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.rnd.chain.events.EventFields.parseUuid;

/**
 * Consumes task.completed (Inbox, Protobuf) to feed {@link TaskEligibilityService#onTaskCompleted}.
 *
 * The event has no timestamp or event id, so the Kafka record timestamp orders it against the
 * stored updatedAt (compare-only, never written back). A task.completed arriving after the task
 * was reopened is ignored. Interim until Inbox adds completed_at to the event.
 */
@Component
public class TaskCompletedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskCompletedEventHandler.class);

    private final TaskEligibilityService taskEligibilityService;
    private final ChainEventProcessingTemplate template;

    public TaskCompletedEventHandler(TaskEligibilityService taskEligibilityService,
                                     ChainEventProcessingTemplate template) {
        this.taskEligibilityService = taskEligibilityService;
        this.template = template;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = ".errors",
            exclude = NonRetryableEventException.class,
            kafkaTemplate = "taskCompletedEventKafkaTemplate")
    @KafkaListener(
            topics = "${chain.kafka.topics.task-completed:task.completed}",
            groupId = "${chain.kafka.consumer-group-id}",
            containerFactory = "taskCompletedEventKafkaListenerContainerFactory")
    @Transactional
    public void onMessage(ConsumerRecord<String, TaskCompletedEvent> record) {
        TaskCompletedEvent event = record.value();
        UUID taskId = parseUuid(event.getTaskId(), "task_id");

        if (!event.getIsCompleted()) {
            log.warn("task.completed for task {} has is_completed=false; ignoring", taskId);
            return;
        }
        if (record.timestamp() < 0) {
            throw new NonRetryableEventException("task.completed record has no Kafka timestamp: " + taskId);
        }
        LocalDateTime recordTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp()), ZoneOffset.UTC);

        template.processUnclaimed(taskId.toString(), "task.completed", () -> apply(taskId, recordTime));
    }

    private void apply(UUID taskId, LocalDateTime recordTime) {
        try {
            taskEligibilityService.onTaskCompleted(taskId, recordTime);
        } catch (TaskNotFoundException e) {
            throw new ChainTransientProcessingException(
                    "task.completed referenced a task Chain hasn't seen yet: " + taskId, e);
        } catch (RuntimeException e) {
            throw new ChainTransientProcessingException(
                    "Failed to apply task.completed for task " + taskId, e);
        }
    }
}
