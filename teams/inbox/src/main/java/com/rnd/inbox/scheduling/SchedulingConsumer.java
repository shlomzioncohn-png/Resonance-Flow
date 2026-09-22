package com.rnd.inbox.scheduling;

import com.rnd.inbox.event.SchedulingUpdatedEvent;
import com.rnd.inbox.shared.messaging.kafka.ErrorTopicPublisher;
import com.rnd.inbox.shared.messaging.kafka.EventProcessingTemplate;
import com.rnd.inbox.shared.messaging.validation.BeanValidationEventValidator;
import com.rnd.inbox.task.TaskService;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchedulingConsumer {

    private final EventProcessingTemplate<SchedulingUpdatedEvent, SchedulingEventDto> template;

    public SchedulingConsumer(SchedulingProtoMapper protoMapper, RetryTemplate kafkaRetryTemplate,
            ErrorTopicPublisher errorTopicPublisher, Validator validator,
            TaskService taskService) {
        this.template = new EventProcessingTemplate<>(
                protoMapper::toDto,
                kafkaRetryTemplate,
                errorTopicPublisher,
                new BeanValidationEventValidator<>(validator),
                taskService::applySchedulingUpdate,
                SchedulingEventDto::getEventId);
    }

    @KafkaListener(
            topics = "${inbox.kafka.topics.chain-scheduling-updated:task.scheduling.updated}",
            containerFactory = "schedulingUpdatedContainerFactory")
    public void onMessage(ConsumerRecord<String, SchedulingUpdatedEvent> record, Acknowledgment acknowledgment) {
        template.process(record, acknowledgment);
    }
}
