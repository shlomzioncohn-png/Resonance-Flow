package com.rnd.bridge.github.service;

import com.rnd.bridge.github.dto.GitHubIssue;
import com.rnd.bridge.github.mapper.GitHubIssueMapper;
import com.rnd.bridge.github.mapper.GitHubPullRequestMapper;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.TaskValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;

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

    public GitHubClient(WebClient webClient, GitHubIssueMapper gitHubIssueMapper, GitHubPullRequestMapper gitHubPullRequestMapper, TaskValidator taskValidator) {
        this.webClient = webClient;
        this.gitHubIssueMapper = gitHubIssueMapper;
        this.gitHubPullRequestMapper = gitHubPullRequestMapper;
        this.taskValidator = taskValidator;
    }

    public Flux<Task> fetchIssues(String owner, String repo) {
        String url = String.format("/repos/%s/%s/issues", owner, repo);
        log.info("Fetching issues from GitHub: {}", url);

        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToFlux(GitHubIssue.class)
                .map(issue -> gitHubIssueMapper.map(issue, Instant.now()))
                .doOnNext(taskValidator::validate)
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
                .map(pr -> gitHubPullRequestMapper.map(pr, Instant.now()))
                .doOnNext(taskValidator::validate)
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


    private boolean isRetryableException(Throwable throwable) {
        if (throwable instanceof WebClientResponseException) {
            WebClientResponseException ex = (WebClientResponseException) throwable;
            return ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429;
        }
        return false;
    }
}
