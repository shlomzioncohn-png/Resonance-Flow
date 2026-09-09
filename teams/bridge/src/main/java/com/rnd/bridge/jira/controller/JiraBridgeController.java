package com.rnd.bridge.jira.controller;

import com.rnd.bridge.jira.service.JiraClient;
import com.rnd.bridge.jira.service.JiraKafkaProducer;
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
    private final JiraKafkaProducer jiraKafkaProducer;

    public JiraBridgeController(JiraClient jiraClient, JiraKafkaProducer jiraKafkaProducer) {
        this.jiraClient = jiraClient;
        this.jiraKafkaProducer = jiraKafkaProducer;
    }

    @PostMapping("/sync")
    public Mono<ResponseEntity<Map<String, Object>>> sync(
            @RequestParam(required = false) String projectKey) {

        String targetProject = (projectKey != null && !projectKey.isBlank()) ? projectKey : defaultProject;

        log.info("Sync requested for Jira project: {}", targetProject);

        return jiraClient.fetchIssues(targetProject)
                .collectList()
                .doOnNext(this::publishTasks)
                .map(fetched -> ResponseEntity.ok(Map.<String, Object>of("project", targetProject, "published", fetched.size())))
                .doOnError(error -> log.error("Failed to sync Jira tasks for project {}", targetProject, error))
                .onErrorResume(error -> Mono.just(ResponseEntity.internalServerError()
                        .body(Map.<String, Object>of("error", error.getMessage(), "project", targetProject))));
    }

    private void publishTasks(List<Task> tasks) {
        jiraKafkaProducer.publishAll(tasks);
    }
}
