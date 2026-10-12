package com.rnd.bridge.jira.scheduler;

import com.rnd.bridge.jira.service.JiraClient;
import com.rnd.bridge.jira.service.JiraKafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class JiraSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(JiraSyncScheduler.class);

    private final JiraClient jiraClient;
    private final JiraKafkaProducer jiraKafkaProducer;

    @Value("${jira.sync.cron.enabled:false}")
    private boolean syncEnabled;

    public JiraSyncScheduler(JiraClient jiraClient, JiraKafkaProducer jiraKafkaProducer) {
        this.jiraClient = jiraClient;
        this.jiraKafkaProducer = jiraKafkaProducer;
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
                    jiraKafkaProducer.publishAll(tasks);
                })
                .doOnError(error -> log.error("Scheduled Jira sync failed", error))
                .block(Duration.ofMinutes(10));
    }
}
