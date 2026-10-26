package com.rnd.bridge.jira.scheduler;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.error.PublishException;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.jira.service.JiraClient;
import com.rnd.bridge.task.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class JiraSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(JiraSyncScheduler.class);

    private final JiraClient jiraClient;
    private final PayloadPublisher<Task> taskPublisher;

    @Value("${jira.sync.cron.enabled:false}")
    private boolean syncEnabled;

    public JiraSyncScheduler(JiraClient jiraClient, PayloadPublisher<Task> taskPublisher) {
        this.jiraClient = jiraClient;
        this.taskPublisher = taskPublisher;
    }

    @Scheduled(cron = "${jira.sync.cron.expression:0 0 2 * * ?}")
    public void syncJiraTasks() {
        if (!syncEnabled) {
            log.info("Jira sync scheduler is disabled.");
            return;
        }

        log.info("Starting scheduled Jira sync.");
        jiraClient.fetchIssues()
                .collectList()
                .doOnNext(tasks -> {
                    log.info("Fetched {} tasks from Jira. Publishing to Kafka.", tasks.size());
                    int published = publishAll(tasks);
                    log.info("Published {}/{} Jira task(s).", published, tasks.size());
                })
                .doOnError(error -> log.error("Scheduled Jira sync failed", error))
                .block(Duration.ofMinutes(10));
    }

    /**
     * publish() blocks and fails the record - so each task gets its own attempt:
     * one invalid/unpublishable record must not kill the rest of the batch.
     */
    private int publishAll(List<Task> tasks) {
        int published = 0;
        for (Task task : tasks) {
            try {
                taskPublisher.publish(task);
                published++;
            } catch (PublishException | ValidationException e) {
                log.error("Failed to publish Jira task {}", task.sourceRef(), e);
            }
        }
        return published;
    }
}
