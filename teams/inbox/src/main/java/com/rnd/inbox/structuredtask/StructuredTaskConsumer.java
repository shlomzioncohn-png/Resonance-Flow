package com.rnd.inbox.structuredtask;

import com.rnd.inbox.event.StructuredTaskEvent;
import com.rnd.inbox.shared.messaging.kafka.CompositeEventValidator;
import com.rnd.inbox.shared.messaging.kafka.ErrorTopicPublisher;
import com.rnd.inbox.shared.messaging.kafka.EventProcessingTemplate;
import com.rnd.inbox.shared.messaging.validation.BeanValidationEventValidator;
import com.rnd.inbox.task.AssigneeConsistencyValidator;
import com.rnd.inbox.task.TaskIngestionService;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StructuredTaskConsumer {

    private final EventProcessingTemplate<StructuredTaskEvent, StructuredTaskEventDto> template;

    public StructuredTaskConsumer(StructuredTaskProtoMapper protoMapper, RetryTemplate kafkaRetryTemplate,
            ErrorTopicPublisher errorTopicPublisher, Validator validator,
            TaskIngestionService taskIngestionService) {
        this.template = new EventProcessingTemplate<>(
                protoMapper::toDto,
                kafkaRetryTemplate,
                errorTopicPublisher,
                new CompositeEventValidator<>(List.of(
                        new BeanValidationEventValidator<>(validator),
                        new AssigneeConsistencyValidator<>(StructuredTaskEventDto::getAssignees))),
                taskIngestionService::ingest,
                StructuredTaskEventDto::getEventId);
    }

    @KafkaListener(
            topics = "${inbox.kafka.topics.bridge-structured-tasks:bridge.structured.tasks}",
            containerFactory = "structuredTaskContainerFactory")
    public void onMessage(ConsumerRecord<String, StructuredTaskEvent> record, Acknowledgment acknowledgment) {
        template.process(record, acknowledgment);
    }
}
