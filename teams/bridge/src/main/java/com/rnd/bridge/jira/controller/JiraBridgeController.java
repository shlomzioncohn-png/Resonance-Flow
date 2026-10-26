package com.rnd.bridge.jira.controller;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.error.PublishException;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.jira.service.JiraClient;
import com.rnd.bridge.task.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bridge/jira")
public class JiraBridgeController {

    private static final Logger log = LoggerFactory.getLogger(JiraBridgeController.class);

    @Value("${jira.target.project:}")
    private String defaultProject;

    private final JiraClient jiraClient;
    private final PayloadPublisher<Task> taskPublisher;

    public JiraBridgeController(JiraClient jiraClient, PayloadPublisher<Task> taskPublisher) {
        this.jiraClient = jiraClient;
        this.taskPublisher = taskPublisher;
    }

    @PostMapping("/sync")
    public Mono<ResponseEntity<Map<String, Object>>> sync(
            @RequestParam(required = false) String projectKey) {

        String targetProject = (projectKey != null && !projectKey.isBlank()) ? projectKey : defaultProject;

        log.info("Sync requested for Jira project: {}", targetProject);

        return jiraClient.fetchIssues(targetProject)
                .collectList()
                .map(fetched -> ResponseEntity.ok(Map.<String, Object>of("project", targetProject,
                        "fetched", fetched.size(), "published", publishTasks(fetched))))
                .doOnError(error -> log.error("Failed to sync Jira tasks for project {}", targetProject, error))
                .onErrorResume(error -> Mono.just(ResponseEntity.internalServerError()
                        .body(Map.<String, Object>of("error", error.getMessage(), "project", targetProject))));
    }

    /**
     * publish() blocks and fails the record - so each task gets its own attempt:
     * one invalid/unpublishable record must not kill the rest of the batch.
     */
    private int publishTasks(List<Task> tasks) {
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
