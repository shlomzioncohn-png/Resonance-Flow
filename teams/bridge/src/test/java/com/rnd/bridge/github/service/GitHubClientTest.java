package com.rnd.bridge.github.service;

import com.rnd.bridge.github.dto.GitHubIssue;
import com.rnd.bridge.github.dto.GitHubUser;
import com.rnd.bridge.github.mapper.GitHubIssueMapper;
import com.rnd.bridge.github.mapper.GitHubPullRequestMapper;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.task.model.RoleContext;
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
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    private GitHubIssueMapper gitHubIssueMapper = new GitHubIssueMapper(new EnrichmentProperties(), new NoOpTaskEnricher(), "");

    @Spy
    private GitHubPullRequestMapper gitHubPullRequestMapper = new GitHubPullRequestMapper(new EnrichmentProperties(), new NoOpTaskEnricher(), "");

    @Spy
    private TaskValidator taskValidator;

    @InjectMocks
    private GitHubClient gitHubClient;

    @BeforeEach
    void setUp() {
        // @InjectMocks doesn't apply @Value defaults - pageSize stays 0 and the
        // client pages forever on an empty first page.
        ReflectionTestUtils.setField(gitHubClient, "pageSize", 100);
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
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

    @Test
    void fetchIssues_shouldFilterOutPullRequests() {
        GitHubIssue issue = createGitHubIssue(1L, "Real issue", "body", "open", "https://github.com/owner/repo/issues/1", "user", false);
        GitHubIssue prReturnedAsIssue = createGitHubIssue(2L, "PR disguised as issue", "body", "open", "https://github.com/owner/repo/pull/2", "user", true);
        when(responseSpec.bodyToFlux(GitHubIssue.class)).thenReturn(Flux.just(issue, prReturnedAsIssue));

        List<Task> tasks = gitHubClient.fetchIssues("owner", "repo").collectList().block();

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).sourceRef()).isEqualTo("https://github.com/owner/repo/issues/1");
        assertThat(tasks.get(0).sourceType()).isEqualTo(SourceType.GITHUB_ISSUE);
    }

    @Test
    void fetchIssues_shouldFetchAllPagesUntilShortPage() {
        ReflectionTestUtils.setField(gitHubClient, "pageSize", 2);

        GitHubIssue i1 = createGitHubIssue(1L, "Issue 1", "b", "open", "https://github.com/o/r/issues/1", "u", false);
        GitHubIssue i2 = createGitHubIssue(2L, "Issue 2", "b", "open", "https://github.com/o/r/issues/2", "u", false);
        GitHubIssue i3 = createGitHubIssue(3L, "Issue 3", "b", "open", "https://github.com/o/r/issues/3", "u", false);
        // Page 1 is full (2 == pageSize) so a second page is fetched; page 2 is short so paging stops.
        when(responseSpec.bodyToFlux(GitHubIssue.class))
                .thenReturn(Flux.just(i1, i2), Flux.just(i3));

        List<Task> tasks = gitHubClient.fetchIssues("owner", "repo").collectList().block();

        assertThat(tasks).hasSize(3);
        assertThat(tasks).extracting(Task::sourceRef).containsExactly(
                "https://github.com/o/r/issues/1",
                "https://github.com/o/r/issues/2",
                "https://github.com/o/r/issues/3");
    }

    @Test
    void fetchIssues_shouldSkipInvalidRecordWithoutDroppingValidOnes() {
        GitHubIssue valid = createGitHubIssue(1L, "Valid", "b", "open", "https://github.com/o/r/issues/1", "u", false);
        GitHubIssue noTitle = createGitHubIssue(2L, null, "b", "open", "https://github.com/o/r/issues/2", "u", false);
        GitHubIssue alsoValid = createGitHubIssue(3L, "Also valid", "b", "open", "https://github.com/o/r/issues/3", "u", false);
        when(responseSpec.bodyToFlux(GitHubIssue.class)).thenReturn(Flux.just(valid, noTitle, alsoValid));

        List<Task> tasks = gitHubClient.fetchIssues("owner", "repo").collectList().block();

        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(Task::title).containsExactly("Valid", "Also valid");
    }

    @Test
    void fetchIssues_shouldReturnEmptyWhenNoIssues() {
        when(responseSpec.bodyToFlux(GitHubIssue.class)).thenReturn(Flux.empty());

        List<Task> tasks = gitHubClient.fetchIssues("owner", "repo").collectList().block();

        assertThat(tasks).isEmpty();
    }

    @Test
    void fetchIssues_shouldMapClosedIssueToDone() {
        GitHubIssue closed = createGitHubIssue(1L, "Closed", "b", "closed", "https://github.com/o/r/issues/1", "u", false);
        when(responseSpec.bodyToFlux(GitHubIssue.class)).thenReturn(Flux.just(closed));

        List<Task> tasks = gitHubClient.fetchIssues("owner", "repo").collectList().block();

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).status()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void fetchIssues_shouldMapUnknownStateToInProgress() {
        GitHubIssue weird = createGitHubIssue(1L, "Weird", "b", "locked", "https://github.com/o/r/issues/1", "u", false);
        when(responseSpec.bodyToFlux(GitHubIssue.class)).thenReturn(Flux.just(weird));

        List<Task> tasks = gitHubClient.fetchIssues("owner", "repo").collectList().block();

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).status()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void fetchIssues_shouldMapNullStateToNew() {
        GitHubIssue noState = createGitHubIssue(1L, "No state", "b", null, "https://github.com/o/r/issues/1", "u", false);
        when(responseSpec.bodyToFlux(GitHubIssue.class)).thenReturn(Flux.just(noState));

        List<Task> tasks = gitHubClient.fetchIssues("owner", "repo").collectList().block();

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).status()).isEqualTo(TaskStatus.NEW);
    }

    @Test
    void fetchIssues_shouldHaveDeveloperRoleContext() {
        GitHubIssue issue = createGitHubIssue(1L, "Dev task", "b", "open", "https://github.com/o/r/issues/1", "u", false);
        when(responseSpec.bodyToFlux(GitHubIssue.class)).thenReturn(Flux.just(issue));

        List<Task> tasks = gitHubClient.fetchIssues("owner", "repo").collectList().block();

        assertThat(tasks.get(0).roleContext()).isEqualTo(RoleContext.DEVELOPER);
    }

    @Test
    void fetchPullRequests_shouldHaveManagerRoleContext() {
        GitHubIssue pr = createGitHubIssue(1L, "PR", "b", "open", "https://github.com/o/r/pull/1", "u", true);
        when(responseSpec.bodyToFlux(GitHubIssue.class)).thenReturn(Flux.just(pr));

        List<Task> tasks = gitHubClient.fetchPullRequests("owner", "repo").collectList().block();

        assertThat(tasks.get(0).roleContext()).isEqualTo(RoleContext.MANAGER);
    }

    @Test
    void fetchIssues_shouldRetryOnServerError() {
        GitHubIssue issue = createGitHubIssue(1L, "Recovered", "b", "open", "https://github.com/o/r/issues/1", "u", false);
        when(responseSpec.bodyToFlux(GitHubIssue.class))
                .thenReturn(
                        Flux.error(WebClientResponseException.create(500, "Internal Error", HttpHeaders.EMPTY, new byte[0], null)),
                        Flux.just(issue));

        List<Task> tasks = gitHubClient.fetchIssues("owner", "repo").collectList().block();

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).title()).isEqualTo("Recovered");
        verify(webClient, times(2)).get();
    }

    @Test
    void fetchIssues_shouldNotRetryOnClientError() {
        when(responseSpec.bodyToFlux(GitHubIssue.class))
                .thenReturn(Flux.error(WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, new byte[0], null)));

        assertThatThrownBy(() -> gitHubClient.fetchIssues("owner", "repo").collectList().block())
                .isInstanceOf(WebClientResponseException.class);
        verify(webClient, times(1)).get();
    }

    @Test
    void fetchAllTasks_shouldMergeIssuesAndPullRequests() {
        GitHubIssue issue = createGitHubIssue(1L, "Issue", "b", "open", "https://github.com/o/r/issues/1", "u", false);
        GitHubIssue pr = createGitHubIssue(2L, "PR", "b", "open", "https://github.com/o/r/pull/2", "u", true);
        // Both endpoints return the same mixed flux; fetchIssues filters out PRs,
        // fetchPullRequests maps everything as a PR.
        when(responseSpec.bodyToFlux(GitHubIssue.class))
                .thenReturn(Flux.just(issue, pr), Flux.just(issue, pr));

        List<Task> tasks = gitHubClient.fetchAllTasks("owner", "repo").collectList().block();

        assertThat(tasks).hasSize(3);
        assertThat(tasks).filteredOn(t -> t.sourceType() == SourceType.GITHUB_ISSUE).hasSize(1);
        assertThat(tasks).filteredOn(t -> t.sourceType() == SourceType.GITHUB_PR).hasSize(2);
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
