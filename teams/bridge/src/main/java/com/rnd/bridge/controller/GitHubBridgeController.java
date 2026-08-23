package com.rnd.bridge.controller;

import com.rnd.bridge.dto.Task;
import com.rnd.bridge.service.GitHubClient;
import com.rnd.bridge.service.GitHubKafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final GitHubClient gitHubClient;
    private final GitHubKafkaProducer gitHubKafkaProducer;

    public GitHubBridgeController(GitHubClient gitHubClient, GitHubKafkaProducer gitHubKafkaProducer) {
        this.gitHubClient = gitHubClient;
        this.gitHubKafkaProducer = gitHubKafkaProducer;
    }

    @PostMapping("/sync")
    public Mono<ResponseEntity<Map<String, Object>>> sync(@RequestParam String owner, @RequestParam String repo) {
        log.info("Sync requested for GitHub repository: {}/{}", owner, repo);

        return gitHubClient.fetchAllTasks(owner, repo)
                .collectList()
                .doOnNext(this::publishTasks)
                .map(tasks -> ResponseEntity.ok(Map.<String, Object>of("owner", owner, "repo", repo, "published", tasks.size())))
                .doOnError(error -> log.error("Failed to sync GitHub tasks for {}/{}", owner, repo, error))
                .onErrorResume(error -> Mono.just(ResponseEntity.internalServerError()
                        .body(Map.<String, Object>of("error", error.getMessage(), "owner", owner, "repo", repo))));
    }

    private void publishTasks(List<Task> tasks) {
        gitHubKafkaProducer.publishAll(tasks);
    }
}
