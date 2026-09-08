package com.rnd.bridge.runner;

import com.rnd.bridge.github.service.GitHubClient;
import com.rnd.bridge.github.service.GitHubKafkaProducer;
import com.rnd.bridge.jira.service.JiraClient;
import com.rnd.bridge.jira.service.JiraKafkaProducer;
import com.rnd.bridge.task.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One-shot manual sync runner.
 *
 * Activates only when {@code bridge.manual-sync.enabled=true} is set
 * (for example, in an IntelliJ Run Configuration VM option).
 *
 * Fetches all GitHub and Jira tasks, publishes them to their respective
 * Kafka topics, waits for the GitHub send futures to complete, then exits the
 * application with an appropriate exit code.
 *
 * This is intentionally not a cron and not triggered on every startup.
 */
@Component
@ConditionalOnProperty(name = "bridge.manual-sync.enabled", havingValue = "true")
public class ManualSyncRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ManualSyncRunner.class);

    private final GitHubClient gitHubClient;
    private final GitHubKafkaProducer gitHubKafkaProducer;
    private final JiraClient jiraClient;
    private final JiraKafkaProducer jiraKafkaProducer;
    private final ConfigurableApplicationContext context;

    public ManualSyncRunner(GitHubClient gitHubClient,
                            GitHubKafkaProducer gitHubKafkaProducer,
                            JiraClient jiraClient,
                            JiraKafkaProducer jiraKafkaProducer,
                            ConfigurableApplicationContext context) {
        this.gitHubClient = gitHubClient;
        this.gitHubKafkaProducer = gitHubKafkaProducer;
        this.jiraClient = jiraClient;
        this.jiraKafkaProducer = jiraKafkaProducer;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        int exitCode;
        try {
            syncGitHub();
            syncJira();
            exitCode = 0;
        } catch (Exception ex) {
            log.error("Manual sync failed", ex);
            exitCode = 1;
        }
        final int finalExitCode = exitCode;
        System.exit(SpringApplication.exit(context, () -> finalExitCode));
    }

    private void syncGitHub() {
        log.info("Manual sync: fetching GitHub tasks");
        List<Task> tasks = gitHubClient.fetchAllTasks().collectList().block();

        if (tasks == null || tasks.isEmpty()) {
            log.info("No GitHub tasks fetched");
            return;
        }

        log.info("Publishing {} GitHub task(s) to Kafka", tasks.size());

        CompletableFuture<?>[] futures = tasks.stream()
                .map(gitHubKafkaProducer::publish)
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(futures).join();
            log.info("Successfully published {} GitHub task(s)", tasks.size());
        } catch (Exception ex) {
            throw new RuntimeException("Failed to publish GitHub tasks to Kafka", ex);
        }
    }

    private void syncJira() {
        log.info("Manual sync: fetching Jira tasks");
        List<Task> tasks = jiraClient.fetchIssues().collectList().block();

        if (tasks == null || tasks.isEmpty()) {
            log.info("No Jira tasks fetched");
            return;
        }

        log.info("Publishing {} Jira task(s) to Kafka", tasks.size());
        jiraKafkaProducer.publishAll(tasks);
        log.info("Successfully published {} Jira task(s)", tasks.size());
    }
}
