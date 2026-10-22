package com.rnd.bridge.runner;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.error.PublishException;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.github.service.GitHubClient;
import com.rnd.bridge.jira.service.JiraClient;
import com.rnd.bridge.task.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-shot manual sync runner.
 *
 * Activates only when {@code bridge.manual-sync.enabled=true} is set
 * (for example, in an IntelliJ Run Configuration VM option).
 *
 * Fetches all GitHub and Jira tasks, publishes them to the shared
 * bridge.structured.tasks topic via the central PayloadPublisher (blocking,
 * per-record), then exits the application with an appropriate exit code.
 *
 * This is intentionally not a cron and not triggered on every startup.
 */
@Component
@ConditionalOnProperty(name = "bridge.manual-sync.enabled", havingValue = "true")
public class ManualSyncRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ManualSyncRunner.class);

    private final GitHubClient gitHubClient;
    private final JiraClient jiraClient;
    private final PayloadPublisher<Task> taskPublisher;
    private final ConfigurableApplicationContext context;

    public ManualSyncRunner(GitHubClient gitHubClient,
                            JiraClient jiraClient,
                            PayloadPublisher<Task> taskPublisher,
                            ConfigurableApplicationContext context) {
        this.gitHubClient = gitHubClient;
        this.jiraClient = jiraClient;
        this.taskPublisher = taskPublisher;
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

        publishAll("GitHub", tasks);
    }

    private void syncJira() {
        log.info("Manual sync: fetching Jira tasks");
        List<Task> tasks = jiraClient.fetchIssues().collectList().block();

        if (tasks == null || tasks.isEmpty()) {
            log.info("No Jira tasks fetched");
            return;
        }

        publishAll("Jira", tasks);
    }

    /**
     * publish() blocks and fails the record - so each task gets its own attempt:
     * one invalid/unpublishable record must not kill the rest of the batch.
     */
    private void publishAll(String lane, List<Task> tasks) {
        log.info("Publishing {} {} task(s) to Kafka", tasks.size(), lane);
        int published = 0;
        for (Task task : tasks) {
            try {
                taskPublisher.publish(task);
                published++;
            } catch (PublishException | ValidationException e) {
                log.error("Failed to publish {} task {}", lane, task.sourceRef(), e);
            }
        }
        log.info("Successfully published {}/{} {} task(s)", published, tasks.size(), lane);
    }
}
