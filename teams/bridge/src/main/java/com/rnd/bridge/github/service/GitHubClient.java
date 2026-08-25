package com.rnd.bridge.github.service;

import com.rnd.bridge.dto.ExtractionProvenance;
import com.rnd.bridge.github.dto.GitHubIssue;
import com.rnd.bridge.dto.SourceType;
import com.rnd.bridge.dto.Task;
import com.rnd.bridge.dto.TaskStatus;
import com.rnd.bridge.dto.Tier;
import com.rnd.bridge.dto.TaskValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final WebClient webClient;


    @Value("${github.target.owner:KamaTechOrg}")
    private String defaultOwner;

    @Value("${github.target.repo:Resonance-Flow}")
    private String defaultRepo;

    public GitHubClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Flux<Task> fetchIssues(String owner, String repo) {
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

    public Flux<Task> fetchAllTasks() {
        return fetchAllTasks(defaultOwner, defaultRepo);
    }

    public Flux<Task> fetchAllTasks(String owner, String repo) {
        return Flux.merge(fetchIssues(owner, repo), fetchPullRequests(owner, repo));
    }

    private Task mapToTask(GitHubIssue issue) {
        SourceType sourceType = issue.getPullRequest() != null ? SourceType.GITHUB_PR : SourceType.GITHUB_ISSUE;
        TaskStatus status = mapStatus(issue.getState());

        Task task = new Task(
                null,
                null,
                issue.getTitle(),
                issue.getBody(),
                status,
                null,
                null,
                Tier.TACTICAL,
                ExtractionProvenance.ETL,
                null,
                sourceType,
                issue.getHtmlUrl(),
                null,
                null,
                null
        );

        TaskValidator.validate(task);

        return task;
    }

    private TaskStatus mapStatus(String githubState) {
        if (githubState == null) {
            return TaskStatus.NEW;
        }
        return switch (githubState.toLowerCase()) {
            case "open" -> TaskStatus.NEW;
            case "closed" -> TaskStatus.DONE;
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
