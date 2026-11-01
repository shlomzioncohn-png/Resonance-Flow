package com.rnd.bridge.github.controller;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.error.PublishException;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.github.service.GitHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@ConditionalOnProperty(name = "bridge.manual-triggers.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/bridge/github")
public class GitHubBridgeController {

    private static final Logger log = LoggerFactory.getLogger(GitHubBridgeController.class);

    @Value("${github.target.owner:KamaTechOrg}")
    private String defaultOwner;

    @Value("${github.target.repo:Resonance-Flow}")
    private String defaultRepo;

    private final GitHubClient gitHubClient;
    private final PayloadPublisher<Task> taskPublisher;

    public GitHubBridgeController(GitHubClient gitHubClient, PayloadPublisher<Task> taskPublisher) {
        this.gitHubClient = gitHubClient;
        this.taskPublisher = taskPublisher;
    }

    @PostMapping("/sync")
    public Mono<ResponseEntity<Map<String, Object>>> sync(
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String repo) {

        String targetOwner = (owner != null && !owner.isBlank()) ? owner : defaultOwner;
        String targetRepo = (repo != null && !repo.isBlank()) ? repo : defaultRepo;

        log.info("Sync requested for GitHub repository: {}/{}", targetOwner, targetRepo);

        return gitHubClient.fetchAllTasks(targetOwner, targetRepo)
                .collectList()
                .map(fetched -> ResponseEntity.ok(Map.<String, Object>of("owner", targetOwner, "repo", targetRepo,
                        "fetched", fetched.size(), "published", publishTasks(fetched))))
                .doOnError(error -> log.error("Failed to sync GitHub tasks for {}/{}", targetOwner, targetRepo, error))
                .onErrorResume(error -> Mono.just(ResponseEntity.internalServerError()
                        .body(Map.<String, Object>of("error", error.getMessage(), "owner", targetOwner, "repo", targetRepo))));
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
                log.error("Failed to publish GitHub task {}", task.sourceRef(), e);
            }
        }
        return published;
    }
}
