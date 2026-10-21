package com.rnd.bridge.github.scheduler;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.error.PublishException;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.github.service.GitHubClient;
import com.rnd.bridge.task.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;


@Service
public class GitHubSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GitHubSyncScheduler.class);

    private final GitHubClient gitHubClient;
    private final PayloadPublisher<Task> taskPublisher;

    @Value("${github.sync.cron.enabled:false}")
    private boolean syncEnabled;

    public GitHubSyncScheduler(GitHubClient gitHubClient, PayloadPublisher<Task> taskPublisher) {
        this.gitHubClient = gitHubClient;
        this.taskPublisher = taskPublisher;
    }

    @Scheduled(cron = "${github.sync.cron.expression:0 0 2 * * ?}")
    public void syncGitHubTasks() {
        if (!syncEnabled) {
            log.info("GitHub sync scheduler is disabled.");
            return;
        }

        log.info("Starting scheduled GitHub sync.");
        gitHubClient.fetchAllTasks()
                .collectList()
                .doOnNext(tasks -> {
                    log.info("Fetched {} tasks from GitHub. Publishing to Kafka.", tasks.size());
                    publishAll(tasks);
                })
                .doOnError(error -> log.error("Scheduled GitHub sync failed", error))
                .block(Duration.ofMinutes(10));
    }

    /**
     * publish() blocks and fails the record - so each task gets its own attempt:
     * one invalid/unpublishable record must not kill the rest of the batch.
     */
    private void publishAll(List<Task> tasks) {
        for (Task task : tasks) {
            try {
                taskPublisher.publish(task);
            } catch (PublishException | ValidationException e) {
                log.error("Failed to publish GitHub task {}", task.sourceRef(), e);
            }
        }
    }
}
