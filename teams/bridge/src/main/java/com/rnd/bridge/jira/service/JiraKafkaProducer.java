package com.rnd.bridge.jira.service;

import com.rnd.bridge.protobuf.TaskProtobufConverter;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class JiraKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(JiraKafkaProducer.class);

    private static final String DEFAULT_TOPIC = "bridge.structured.tasks";

    final String TOPIC;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final TaskValidator taskValidator;

    @Autowired
    public JiraKafkaProducer(
            @Qualifier("protobufKafkaTemplate") KafkaTemplate<String, byte[]> kafkaTemplate,
            @Value("${bridge.structured-tasks-topic:bridge.structured.tasks}") String topic,
            TaskValidator taskValidator) {
        this.TOPIC = (topic != null && !topic.isBlank()) ? topic : DEFAULT_TOPIC;
        this.kafkaTemplate = kafkaTemplate;
        this.taskValidator = taskValidator;
    }

    public CompletableFuture<SendResult<String, byte[]>> publish(Task task) {
        if (task == null || task.sourceRef() == null) {
            throw new IllegalArgumentException("Task and sourceRef must not be null");
        }

        taskValidator.validate(task);

        log.info("Publishing task {} to topic {} as Protobuf", task.sourceRef(), TOPIC);

        // Convert Bridge Task to Protobuf Task and serialize to bytes
        com.rnd.bridge.dto.Task protobufTask = TaskProtobufConverter.toProtobufTask(task);
        byte[] taskBytes = protobufTask.toByteArray();

        CompletableFuture<SendResult<String, byte[]>> future = kafkaTemplate.send(TOPIC, task.sourceRef(), taskBytes);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish task {} to topic {}", task.sourceRef(), TOPIC, ex);
            } else {
                log.info("Successfully published task {} to topic {} at offset {}",
                        task.sourceRef(), TOPIC, result.getRecordMetadata().offset());
            }
        });

        return future;
    }

    public void publishAll(List<Task> tasks) {
        if (tasks == null) {
            return;
        }
        tasks.forEach(this::publish);
    }
}
