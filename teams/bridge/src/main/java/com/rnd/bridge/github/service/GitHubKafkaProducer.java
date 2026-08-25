package com.rnd.bridge.github.service;

import com.rnd.bridge.dto.Task;
import com.rnd.bridge.dto.TaskValidator;
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
public class GitHubKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(GitHubKafkaProducer.class);

    @Value("${spring.kafka.topic:bridge.github.tasks}")
        private String TOPIC;

    private final KafkaTemplate<String, Task> kafkaTemplate;

    @Autowired
    public GitHubKafkaProducer(KafkaTemplate<String, Task> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, Task>> publish(Task task) {
        if (task == null || task.sourceRef() == null) {
            throw new IllegalArgumentException("Task and sourceRef must not be null");
        }

        TaskValidator.validate(task);

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
