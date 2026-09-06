package com.rnd.bridge.github.service;

import com.rnd.bridge.github.dto.GitHubIssue;
import com.rnd.bridge.github.dto.GitHubUser;
import com.rnd.bridge.github.mapper.GitHubIssueMapper;
import com.rnd.bridge.github.mapper.GitHubPullRequestMapper;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class GitHubClientTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    @Spy
    private GitHubIssueMapper gitHubIssueMapper;

    @Spy
    private GitHubPullRequestMapper gitHubPullRequestMapper;

    @Spy
    private TaskValidator taskValidator;

    @InjectMocks
    private GitHubClient gitHubClient;

    @BeforeEach
    void setUp() {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    void fetchIssues_shouldMapGitHubIssueToTask() {
        GitHubIssue issue = createGitHubIssue(1L, "Test Issue", "Issue body", "open", "https://github.com/owner/repo/issues/1", "testuser", false);
        when(responseSpec.bodyToFlux(GitHubIssue.class)).thenReturn(Flux.just(issue));

        List<Task> tasks = gitHubClient.fetchIssues("owner", "repo").collectList().block();

        assertThat(tasks).hasSize(1);
        Task task = tasks.get(0);
        assertThat(task.sourceRef()).isEqualTo("https://github.com/owner/repo/issues/1");
        assertThat(task.title()).isEqualTo("Test Issue");
        assertThat(task.description()).isEqualTo("Issue body");
        assertThat(task.sourceType()).isEqualTo(SourceType.GITHUB_ISSUE);
        assertThat(task.status()).isEqualTo(TaskStatus.NEW);
        assertThat(task.extractionProvenance()).isEqualTo(ExtractionProvenance.ETL);
        assertThat(task.tier()).isEqualTo(Tier.TACTICAL);
    }

    @Test
    void fetchPullRequests_shouldMapGitHubPrToTask() {
        GitHubIssue pr = createGitHubIssue(42L, "Test PR", "PR body", "closed", "https://github.com/owner/repo/pull/42", "contributor", true);
        when(responseSpec.bodyToFlux(GitHubIssue.class)).thenReturn(Flux.just(pr));

        List<Task> tasks = gitHubClient.fetchPullRequests("owner", "repo").collectList().block();

        assertThat(tasks).hasSize(1);
        Task task = tasks.get(0);
        assertThat(task.sourceRef()).isEqualTo("https://github.com/owner/repo/pull/42");
        assertThat(task.title()).isEqualTo("Test PR");
        assertThat(task.description()).isEqualTo("PR body");
        assertThat(task.sourceType()).isEqualTo(SourceType.GITHUB_PR);
        assertThat(task.status()).isEqualTo(TaskStatus.DONE);
        assertThat(task.extractionProvenance()).isEqualTo(ExtractionProvenance.ETL);
        assertThat(task.tier()).isEqualTo(Tier.TACTICAL);
    }

    private GitHubIssue createGitHubIssue(Long number, String title, String body, String state,
                                          String htmlUrl, String userLogin, boolean isPullRequest) {
        GitHubIssue issue = new GitHubIssue();
        issue.setNumber(number);
        issue.setTitle(title);
        issue.setBody(body);
        issue.setState(state);
        issue.setHtmlUrl(htmlUrl);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());

        GitHubUser user = new GitHubUser();
        user.setLogin(userLogin);
        issue.setUser(user);

        if (isPullRequest) {
            issue.setPullRequest(java.util.Collections.singletonMap("url", htmlUrl));
        }

        return issue;
    }
}
