package com.rnd.bridge.jira.service;

import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.TaskValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class JiraKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(JiraKafkaProducer.class);

    @Value("${jira.kafka.topic:bridge.jira.tasks}")
    String TOPIC = "bridge.jira.tasks";

    private final KafkaTemplate<String, Task> kafkaTemplate;
    private final TaskValidator taskValidator;

    @Autowired
    public JiraKafkaProducer(KafkaTemplate<String, Task> kafkaTemplate, TaskValidator taskValidator) {
        this.kafkaTemplate = kafkaTemplate;
        this.taskValidator = taskValidator;
    }

    public CompletableFuture<SendResult<String, Task>> publish(Task task) {
        if (task == null || task.sourceRef() == null) {
            throw new IllegalArgumentException("Task and sourceRef must not be null");
        }

        taskValidator.validate(task);

        log.info("Publishing task {} to topic {}", task.sourceRef(), TOPIC);

        CompletableFuture<SendResult<String, Task>> future = kafkaTemplate.send(TOPIC, task.sourceRef(), task);
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
