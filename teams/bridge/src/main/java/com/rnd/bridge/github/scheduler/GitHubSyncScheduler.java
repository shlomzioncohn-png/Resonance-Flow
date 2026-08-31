package com.rnd.bridge.github.scheduler;

import com.rnd.bridge.github.service.GitHubClient;
import com.rnd.bridge.github.service.GitHubKafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


@Service
public class GitHubSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GitHubSyncScheduler.class);

    private final GitHubClient gitHubClient;
    private final GitHubKafkaProducer gitHubKafkaProducer;

    @Value("${github.sync.cron.enabled:false}")
    private boolean syncEnabled;

    public GitHubSyncScheduler(GitHubClient gitHubClient, GitHubKafkaProducer gitHubKafkaProducer) {
        this.gitHubClient = gitHubClient;
        this.gitHubKafkaProducer = gitHubKafkaProducer;
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
                    gitHubKafkaProducer.publishAll(tasks);
                })
                .doOnError(error -> log.error("Scheduled GitHub sync failed", error))
                .subscribe();
    }
}
