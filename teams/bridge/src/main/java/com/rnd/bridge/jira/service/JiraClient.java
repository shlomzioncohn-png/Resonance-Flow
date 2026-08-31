package com.rnd.bridge.jira.service;

import com.rnd.bridge.jira.dto.JiraIssue;
import com.rnd.bridge.jira.dto.JiraSearchResult;
import com.rnd.bridge.jira.mapper.JiraIssueMapper;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;

@Service
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);

    private final WebClient webClient;
    private final JiraIssueMapper jiraIssueMapper;
    private final TaskValidator taskValidator;

    @Value("${jira.target.project:}")
    private String defaultProject;

    public JiraClient(@Qualifier("jiraWebClient") WebClient webClient,
                      JiraIssueMapper jiraIssueMapper,
                      TaskValidator taskValidator) {
        this.webClient = webClient;
        this.jiraIssueMapper = jiraIssueMapper;
        this.taskValidator = taskValidator;
    }

    public Flux<Task> fetchIssues() {
        return searchIssues(defaultProject.isEmpty() ? "" : "project = " + defaultProject);
    }

    public Flux<Task> fetchIssues(String projectKey) {
        return searchIssues("project = " + projectKey);
    }

    public Flux<Task> searchIssues(String jql) {
        log.info("Searching Jira issues with JQL: {}", jql);

        return webClient.get()
                .uri("/rest/api/2/search?jql={jql}", jql)
                .retrieve()
                .bodyToMono(JiraSearchResult.class)
                .flatMapMany(result -> {
                    if (result == null || result.getIssues() == null) {
                        return Flux.empty();
                    }
                    return Flux.fromIterable(result.getIssues());
                })
                .map(issue -> jiraIssueMapper.map(issue, Instant.now()))
                .doOnNext(taskValidator::validate)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(this::isRetryableException)
                        .doBeforeRetry(retrySignal -> log.warn("Retrying Jira API call, attempt: {}", retrySignal.totalRetries() + 1)))
                .doOnError(error -> log.error("Failed to fetch issues from Jira", error));
    }

    private boolean isRetryableException(Throwable throwable) {
        if (throwable instanceof WebClientResponseException) {
            WebClientResponseException ex = (WebClientResponseException) throwable;
            return ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429;
        }
        return false;
    }
}
