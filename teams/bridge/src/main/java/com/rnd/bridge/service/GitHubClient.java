package com.rnd.bridge.service;

import com.rnd.bridge.dto.ExtractionProvenance;
import com.rnd.bridge.dto.GitHubIssue;
import com.rnd.bridge.dto.SourceType;
import com.rnd.bridge.dto.Task;
import com.rnd.bridge.dto.TaskStatus;
import com.rnd.bridge.dto.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

@Service
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final WebClient webClient;

    private String currentRepo;

    public GitHubClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Flux<Task> fetchIssues(String owner, String repo) {
        this.currentRepo = repo;
        String url = String.format("/repos/%s/%s/issues", owner, repo);
        log.info("Fetching issues from GitHub: {}", url);

        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToFlux(GitHubIssue.class)
                .map(this::mapToTask)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(this::isRetryableException)
                        .doBeforeRetry(retrySignal -> log.warn("Retrying GitHub API call, attempt: {}", retrySignal.totalRetries() + 1)))
                .doOnError(error -> log.error("Failed to fetch issues from GitHub", error));
    }

    public Flux<Task> fetchPullRequests(String owner, String repo) {
        this.currentRepo = repo;
        String url = String.format("/repos/%s/%s/pulls", owner, repo);
        log.info("Fetching pull requests from GitHub: {}", url);

        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToFlux(GitHubIssue.class)
                .map(this::mapToTask)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(this::isRetryableException)
                        .doBeforeRetry(retrySignal -> log.warn("Retrying GitHub API call, attempt: {}", retrySignal.totalRetries() + 1)))
                .doOnError(error -> log.error("Failed to fetch pull requests from GitHub", error));
    }

    public Flux<Task> fetchAllTasks(String owner, String repo) {
        this.currentRepo = repo;
        return Flux.merge(fetchIssues(owner, repo), fetchPullRequests(owner, repo));
    }

    private Task mapToTask(GitHubIssue issue) {
        String type = issue.getPullRequest() != null ? "pr" : "issue";
        String id = String.format("github:%s:%s:%d", currentRepo, type, issue.getNumber());

        TaskStatus status = mapStatus(issue.getState());

        Task task = new Task();
        task.setId(id);
        task.setTitle(issue.getTitle());
        task.setDescription(issue.getBody());
        task.setSourceType(SourceType.GITHUB);
        task.setSourceRef(issue.getHtmlUrl());
        task.setOwner(issue.getUser() != null ? issue.getUser().getLogin() : null);
        task.setRoleContext(null);
        task.setTier(Tier.TIER_2);
        task.setUrgency(null);
        task.setEffortEstimateMinutes(null);
        task.setDeadline(null);
        task.setParentTaskId(null);
        task.setDependsOn(List.of());
        task.setConstraints(List.of());
        task.setConfidence(null);
        task.setExtractionProvenance(ExtractionProvenance.API);
        task.setStatus(status);

        return task;
    }

    private TaskStatus mapStatus(String githubState) {
        if (githubState == null) {
            return TaskStatus.OPEN;
        }
        return switch (githubState.toLowerCase()) {
            case "open" -> TaskStatus.OPEN;
            case "closed" -> TaskStatus.CLOSED;
            default -> TaskStatus.IN_PROGRESS;
        };
    }

    private boolean isRetryableException(Throwable throwable) {
        if (throwable instanceof WebClientResponseException) {
            WebClientResponseException ex = (WebClientResponseException) throwable;
            return ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429;
        }
        return false;
    }
}
