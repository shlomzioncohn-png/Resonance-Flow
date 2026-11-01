package com.rnd.bridge.jira.service;

import com.rnd.bridge.jira.dto.JiraIssue;
import com.rnd.bridge.jira.dto.JiraIssueFields;
import com.rnd.bridge.jira.dto.JiraIssueType;
import com.rnd.bridge.jira.dto.JiraSearchResult;
import com.rnd.bridge.jira.dto.JiraStatus;
import com.rnd.bridge.jira.mapper.JiraIssueMapper;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class JiraClientTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    @Spy
    private JiraIssueMapper jiraIssueMapper = new JiraIssueMapper(new EnrichmentProperties(), new NoOpTaskEnricher(), "");

    @Spy
    private TaskValidator taskValidator;

    @InjectMocks
    private JiraClient jiraClient;

    @BeforeEach
    void setUp() {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    void fetchIssues_shouldMapJiraIssueToTask() {
        JiraSearchResult result = new JiraSearchResult();
        result.setIssues(Collections.singletonList(createJiraIssue()));
        result.setLast(true);

        when(responseSpec.bodyToMono(JiraSearchResult.class)).thenReturn(Mono.just(result));

        List<Task> tasks = jiraClient.fetchIssues("PROJ").collectList().block();

        assertThat(tasks).hasSize(1);
        Task task = tasks.get(0);
        assertThat(task.sourceRef()).isEqualTo("https://jira.example.com/PROJ-1");
        assertThat(task.title()).isEqualTo("Jira issue summary");
        assertThat(task.description()).isEqualTo("Jira issue description");
        assertThat(task.sourceType()).isEqualTo(SourceType.JIRA_ISSUE);
        assertThat(task.status()).isEqualTo(TaskStatus.NEW);
        assertThat(task.extractionProvenance()).isEqualTo(ExtractionProvenance.ETL);
        assertThat(task.tier()).isEqualTo(Tier.TACTICAL);
    }

    @Test
    void fetchIssues_shouldReturnEmptyWhenNoIssues() {
        JiraSearchResult result = new JiraSearchResult();
        result.setIssues(null);
        result.setLast(true);

        when(responseSpec.bodyToMono(JiraSearchResult.class)).thenReturn(Mono.just(result));

        List<Task> tasks = jiraClient.fetchIssues("PROJ").collectList().block();

        assertThat(tasks).isEmpty();
    }

    @Test
    void searchIssues_shouldFetchAllPagesUntilIsLast() {
        ReflectionTestUtils.setField(jiraClient, "pageSize", 2);

        JiraSearchResult page1 = new JiraSearchResult();
        page1.setIssues(List.of(createJiraIssue("PROJ-1", "Issue 1"), createJiraIssue("PROJ-2", "Issue 2")));
        page1.setLast(false);
        page1.setNextPageToken("tok-1");

        JiraSearchResult page2 = new JiraSearchResult();
        page2.setIssues(List.of(createJiraIssue("PROJ-3", "Issue 3")));
        page2.setLast(true);

        // Page 1 is not last and carries a token, so page 2 is fetched;
        // page 2 reports isLast so paging stops.
        when(responseSpec.bodyToMono(JiraSearchResult.class))
                .thenReturn(Mono.just(page1), Mono.just(page2));

        List<Task> tasks = jiraClient.searchIssues("project = PROJ").collectList().block();

        assertThat(tasks).hasSize(3);
        assertThat(tasks).extracting(Task::title).containsExactly("Issue 1", "Issue 2", "Issue 3");
    }

    @Test
    void searchIssues_shouldStopWhenIsLastEvenWithTokenPresent() {
        JiraSearchResult result = new JiraSearchResult();
        result.setIssues(List.of(createJiraIssue("PROJ-1", "Only")));
        result.setLast(true);
        result.setNextPageToken("ignored");

        when(responseSpec.bodyToMono(JiraSearchResult.class)).thenReturn(Mono.just(result));

        List<Task> tasks = jiraClient.searchIssues("project = PROJ").collectList().block();

        assertThat(tasks).hasSize(1);
    }

    @Test
    void searchIssues_shouldSkipInvalidRecordWithoutDroppingValidOnes() {
        JiraSearchResult result = new JiraSearchResult();
        result.setIssues(List.of(
                createJiraIssue("PROJ-1", "Valid"),
                createJiraIssue("PROJ-2", null),
                createJiraIssue("PROJ-3", "Also valid")));
        result.setLast(true);

        when(responseSpec.bodyToMono(JiraSearchResult.class)).thenReturn(Mono.just(result));

        List<Task> tasks = jiraClient.searchIssues("project = PROJ").collectList().block();

        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(Task::title).containsExactly("Valid", "Also valid");
    }

    private JiraIssue createJiraIssue() {
        return createJiraIssue("PROJ-1", "Jira issue summary");
    }

    private JiraIssue createJiraIssue(String key, String summary) {
        JiraIssue issue = new JiraIssue();
        issue.setId("10001");
        issue.setKey(key);
        issue.setSelf("https://jira.example.com/" + key);

        JiraIssueFields fields = new JiraIssueFields();
        fields.setSummary(summary);
        fields.setDescription("Jira issue description");

        JiraStatus status = new JiraStatus();
        status.setName("To Do");
        fields.setStatus(status);

        JiraIssueType issueType = new JiraIssueType();
        issueType.setName("Story");
        fields.setIssuetype(issueType);

        issue.setFields(fields);
        return issue;
    }
}
