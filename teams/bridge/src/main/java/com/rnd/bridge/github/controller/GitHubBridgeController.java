package com.rnd.bridge.github.controller;

import com.rnd.bridge.dto.Task;
import com.rnd.bridge.github.service.GitHubClient;
import com.rnd.bridge.github.service.GitHubKafkaProducer;
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
@RequestMapping("/api/bridge/github")
public class GitHubBridgeController {

    private static final Logger log = LoggerFactory.getLogger(GitHubBridgeController.class);

    @Value("${github.target.owner:KamaTechOrg}")
    private String defaultOwner;

    @Value("${github.target.repo:Resonance-Flow}")
    private String defaultRepo;

    private final GitHubClient gitHubClient;
    private final GitHubKafkaProducer gitHubKafkaProducer;

    public GitHubBridgeController(GitHubClient gitHubClient, GitHubKafkaProducer gitHubKafkaProducer) {
        this.gitHubClient = gitHubClient;
        this.gitHubKafkaProducer = gitHubKafkaProducer;
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
                .doOnNext(this::publishTasks)
                .map(fetched -> ResponseEntity.ok(Map.<String, Object>of("owner", targetOwner, "repo", targetRepo, "published", fetched.size())))
                .doOnError(error -> log.error("Failed to sync GitHub tasks for {}/{}", targetOwner, targetRepo, error))
                .onErrorResume(error -> Mono.just(ResponseEntity.internalServerError()
                        .body(Map.<String, Object>of("error", error.getMessage(), "owner", targetOwner, "repo", targetRepo))));
    }

    private void publishTasks(List<Task> tasks) {
        gitHubKafkaProducer.publishAll(tasks);
    }
}
