package com.rnd.bridge.github.service;

import com.rnd.bridge.github.dto.GitHubIssue;
import com.rnd.bridge.github.mapper.GitHubIssueMapper;
import com.rnd.bridge.github.mapper.GitHubPullRequestMapper;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.TaskValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final WebClient webClient;
    private final GitHubIssueMapper gitHubIssueMapper;
    private final GitHubPullRequestMapper gitHubPullRequestMapper;
    private final TaskValidator taskValidator;


    @Value("${github.target.owner:KamaTechOrg}")
    private String defaultOwner;

    @Value("${github.target.repo:Resonance-Flow}")
    private String defaultRepo;

    @Value("${github.page-size:100}")
    private int pageSize;

    public GitHubClient(WebClient webClient, GitHubIssueMapper gitHubIssueMapper, GitHubPullRequestMapper gitHubPullRequestMapper, TaskValidator taskValidator) {
        this.webClient = webClient;
        this.gitHubIssueMapper = gitHubIssueMapper;
        this.gitHubPullRequestMapper = gitHubPullRequestMapper;
        this.taskValidator = taskValidator;
    }

    public Flux<Task> fetchIssues(String owner, String repo) {
        String path = String.format("/repos/%s/%s/issues", owner, repo);
        log.info("Fetching issues from GitHub: {}", path);
       return fetchAllPages(path)
                // The /issues endpoint also returns pull requests (they carry a
                // pull_request field). They are fetched via /pulls below, so
                // filtering them here prevents publishing every PR twice.
                .filter(issue -> issue.getPullRequest() == null)
                .map(issue -> gitHubIssueMapper.map(issue, Instant.now()))
                .concatMap(this::validateOrSkip)
                .doOnError(error -> log.error("Failed to fetch issues from GitHub", error));
    }

    public Flux<Task> fetchPullRequests(String owner, String repo) {
        String path = String.format("/repos/%s/%s/pulls", owner, repo);
        log.info("Fetching pull requests from GitHub: {}", path);

        return fetchAllPages(path)
                .map(pr -> gitHubPullRequestMapper.map(pr, Instant.now()))
                .concatMap(this::validateOrSkip)
                .doOnError(error -> log.error("Failed to fetch pull requests from GitHub", error));
    }

    public Flux<Task> fetchAllTasks() {
        return fetchAllTasks(defaultOwner, defaultRepo);
    }

    public Flux<Task> fetchAllTasks(String owner, String repo) {
        return Flux.merge(fetchIssues(owner, repo), fetchPullRequests(owner, repo));
    }

    /**
     * Fetches every page of a list endpoint. A full page (size == pageSize)
     * means there may be more pages; a short page ends the traversal.
     */
    private Flux<GitHubIssue> fetchAllPages(String path) {
        return fetchPage(path, 1)
                .expand(page -> !page.items().isEmpty() && page.items().size() == pageSize
                        ? fetchPage(path, page.number() + 1)
                        : Mono.empty())
                .concatMapIterable(GitHubPage::items);
    }

    private Mono<GitHubPage> fetchPage(String path, int page) {
        // defer: the request must be built per subscription so each retry
        // actually re-issues it instead of replaying the first response flux.
        return Flux.defer(() -> webClient.get()
                .uri(uriBuilder -> uriBuilder.path(path)
                       .queryParam("per_page", pageSize)
                        .queryParam("page", page)
                        .build())
                .retrieve()
                .bodyToFlux(GitHubIssue.class))
                .collectList()
                .map(items -> new GitHubPage(page, items))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(this::isRetryableException)
                        .doBeforeRetry(retrySignal -> log.warn("Retrying GitHub API call, attempt: {}", retrySignal.totalRetries() + 1)));
    }

    private record GitHubPage(int number, List<GitHubIssue> items) {
    }

    /**
     * Validates a single task; an invalid record is logged and dropped instead
     * of failing the entire stream.
     */
    private Mono<Task> validateOrSkip(Task task) {
        try {
            taskValidator.validate(task);
            return Mono.just(task);
        } catch (ValidationException e) {
            log.warn("Skipping invalid task {}: {}", task.sourceRef(), e.getMessage());
            return Mono.empty();
        }
    }

    private boolean isRetryableException(Throwable throwable) {
        if (throwable instanceof WebClientResponseException) {
            WebClientResponseException ex = (WebClientResponseException) throwable;
            return ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429;
        }
        return false;
    }
}
