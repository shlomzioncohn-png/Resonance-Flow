package com.rnd.bridge.jira.service;

import com.rnd.bridge.publish.kafka.KafkaPayloadPublisher;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.Task;
import com.rnd.inbox.event.StructuredTaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JiraKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(JiraKafkaProducer.class);

    private static final String DEFAULT_TOPIC = "bridge.structured.tasks";

    final String TOPIC;
    private final KafkaPayloadPublisher kafkaPayloadPublisher;
    private final TaskValidator taskValidator;

    @Autowired
    public JiraKafkaProducer(
            @Qualifier("bridgeTaskEventKafkaTemplate") KafkaTemplate<String, StructuredTaskEvent> kafkaTemplate,
            @Value("${bridge.structured-tasks-topic:bridge.structured.tasks}") String topic,
            TaskValidator taskValidator) {
        this.TOPIC = (topic != null && !topic.isBlank()) ? topic : DEFAULT_TOPIC;
        this.kafkaPayloadPublisher = new KafkaPayloadPublisher(kafkaTemplate, this.TOPIC);
        this.taskValidator = taskValidator;
    }

    public void publish(Task task) {
        if (task == null || task.sourceRef() == null) {
            throw new IllegalArgumentException("Task and sourceRef must not be null");
        }

        taskValidator.validate(task);

        log.info("Publishing task {} to topic {} as StructuredTaskEvent", task.sourceRef(), TOPIC);

        kafkaPayloadPublisher.publish(task);

        log.info("Successfully published task {} to topic {}", task.sourceRef(), TOPIC);
    }

    public void publishAll(List<Task> tasks) {
        if (tasks == null) {
            return;
        }
        tasks.forEach(this::publish);
    }
}
