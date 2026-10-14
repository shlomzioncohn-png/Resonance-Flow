package com.rnd.bridge.jira.mapper;

import com.rnd.bridge.jira.dto.JiraIssue;
import com.rnd.bridge.jira.dto.JiraIssueFields;
import com.rnd.bridge.jira.dto.JiraIssueType;
import com.rnd.bridge.jira.dto.JiraStatus;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JiraIssueMapperTest {

    private final JiraIssueMapper mapper = new JiraIssueMapper(new EnrichmentProperties(), new NoOpTaskEnricher());

    @Test
    void map_shouldConvertOpenIssueToNewTask() {
        JiraIssue issue = createJiraIssue("10001", "PROJ-1", "https://jira.example.com/PROJ-1", "To Do", "Summary");

        Task task = mapper.map(issue, Instant.now());

        assertThat(task.sourceType()).isEqualTo(SourceType.JIRA_ISSUE);
        assertThat(task.sourceRef()).isEqualTo("https://jira.example.com/PROJ-1");
        assertThat(task.title()).isEqualTo("Summary");
        assertThat(task.description()).isEqualTo("Description");
        assertThat(task.status()).isEqualTo(TaskStatus.NEW);
        assertThat(task.tier()).isEqualTo(Tier.TACTICAL);
        assertThat(task.roleContext()).isEqualTo(RoleContext.DEVELOPER);
        assertThat(task.extractionProvenance()).isEqualTo(ExtractionProvenance.ETL);
    }

    @Test
    void map_shouldConvertDoneIssueToDoneTask() {
        JiraIssue issue = createJiraIssue("10002", "PROJ-2", "https://jira.example.com/PROJ-2", "Done", "Done summary");

        Task task = mapper.map(issue, Instant.now());

        assertThat(task.status()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void map_shouldReturnDefaultsForMissingFields() {
        JiraIssue issue = new JiraIssue();
        issue.setId("10003");
        issue.setKey("PROJ-3");
        issue.setSelf("https://jira.example.com/PROJ-3");

        Task task = mapper.map(issue, Instant.now());

        assertThat(task.sourceType()).isEqualTo(SourceType.JIRA_ISSUE);
        assertThat(task.sourceRef()).isEqualTo("https://jira.example.com/PROJ-3");
        assertThat(task.title()).isNull();
        assertThat(task.description()).isNull();
        assertThat(task.status()).isEqualTo(TaskStatus.NEW);
    }

    private JiraIssue createJiraIssue(String id, String key, String self, String statusName, String summary) {
        JiraIssue issue = new JiraIssue();
        issue.setId(id);
        issue.setKey(key);
        issue.setSelf(self);

        JiraIssueFields fields = new JiraIssueFields();
        fields.setSummary(summary);
        fields.setDescription("Description");

        JiraStatus status = new JiraStatus();
        status.setName(statusName);
        fields.setStatus(status);

        JiraIssueType issueType = new JiraIssueType();
        issueType.setName("Story");
        fields.setIssuetype(issueType);

        issue.setFields(fields);
        return issue;
    }
}
