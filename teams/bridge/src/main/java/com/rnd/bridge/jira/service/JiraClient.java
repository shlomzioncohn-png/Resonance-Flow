package com.rnd.bridge.jira.service;

import com.rnd.bridge.core.error.ValidationException;
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
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);

    private final WebClient webClient;
    private final JiraIssueMapper jiraIssueMapper;
    private final TaskValidator taskValidator;

    @Value("${jira.target.project:}")
    private String defaultProject;

    @Value("${jira.page-size:100}")
    private int pageSize;

    public JiraClient(@Qualifier("jiraWebClient") WebClient webClient,
                      JiraIssueMapper jiraIssueMapper,
                      TaskValidator taskValidator) {
        this.webClient = webClient;
        this.jiraIssueMapper = jiraIssueMapper;
        this.taskValidator = taskValidator;
    }

    public Flux<Task> fetchIssues() {
        if (!StringUtils.hasText(defaultProject)) {
            return Flux.error(new IllegalArgumentException("jira.target.project must be configured for default sync"));
        }
        return searchIssues("project = " + defaultProject);
    }

    public Flux<Task> fetchIssues(String projectKey) {
        String effectiveProject = StringUtils.hasText(projectKey) ? projectKey : defaultProject;
        if (!StringUtils.hasText(effectiveProject)) {
            return Flux.error(new IllegalArgumentException("Jira project key must be configured or provided"));
        }
        return searchIssues("project = " + effectiveProject);
    }

    public Flux<Task> searchIssues(String jql) {
        log.info("Searching Jira issues with JQL: {}", jql);

        return fetchPage(jql, null)
                .expand(page -> !page.isLast()
                        && StringUtils.hasText(page.nextPageToken())
                        && !page.issues().isEmpty()
                        ? fetchPage(jql, page.nextPageToken())
                        : Mono.empty())
                .concatMapIterable(JiraPage::issues)
                .map(issue -> jiraIssueMapper.map(issue, Instant.now()))
                .concatMap(this::validateOrSkip)
                .doOnError(error -> log.error("Failed to fetch issues from Jira", error));
    }

    /**
     * Fetches one page of search results via the enhanced JQL endpoint.
     * Jira returns {@code isLast}/{@code nextPageToken} instead of
     * {@code total}/{@code startAt}: we keep paging while the response
     * reports a next page, passing the token back verbatim. The first
     * page is requested with no token.
     */
    private Mono<JiraPage> fetchPage(String jql, String nextPageToken) {
        return webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/rest/api/3/search/jql")
                            .queryParam("jql", jql)
                            .queryParam("maxResults", pageSize);
                    if (StringUtils.hasText(nextPageToken)) {
                        builder.queryParam("nextPageToken", nextPageToken);
                    }
                    return builder.build();
                })
                .retrieve()
                .bodyToMono(JiraSearchResult.class)
                .map(result -> new JiraPage(
                        result != null && result.getIssues() != null ? result.getIssues() : List.of(),
                        result != null ? result.getNextPageToken() : null,
                        result == null || result.isLast()))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(this::isRetryableException)
                        .doBeforeRetry(retrySignal -> log.warn("Retrying Jira API call, attempt: {}", retrySignal.totalRetries() + 1)));
    }

    private record JiraPage(List<JiraIssue> issues, String nextPageToken, boolean isLast) {
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
