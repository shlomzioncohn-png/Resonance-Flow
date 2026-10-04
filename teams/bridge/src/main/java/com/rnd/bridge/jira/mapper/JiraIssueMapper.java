package com.rnd.bridge.jira.mapper;

import com.rnd.bridge.jira.dto.JiraIssue;
import com.rnd.bridge.jira.dto.JiraIssueFields;
import com.rnd.bridge.jira.dto.JiraStatus;
import com.rnd.bridge.task.AbstractTaskMapper;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class JiraIssueMapper extends AbstractTaskMapper<JiraIssue> {

    public JiraIssueMapper(EnrichmentProperties enrichmentProperties, TaskEnricher taskEnricher) {
        super(enrichmentProperties, taskEnricher);
    }

    @Override
    protected SourceType sourceType() {
        return SourceType.JIRA_ISSUE;
    }

    @Override
    protected Tier tier() {
        return Tier.TACTICAL;
    }

    @Override
    protected RoleContext roleContext() {
        return RoleContext.DEVELOPER;
    }

    @Override
    protected String toTitle(JiraIssue issue) {
        JiraIssueFields fields = issue.getFields();
        return fields == null ? null : fields.getSummary();
    }

    @Override
    protected String toDescription(JiraIssue issue) {
        JiraIssueFields fields = issue.getFields();
        return fields == null ? null : fields.getDescription();
    }

    @Override
    protected String toSourceRef(JiraIssue issue) {
        return issue.getSelf();
    }

    @Override
    protected TaskStatus toStatus(JiraIssue issue) {
        JiraIssueFields fields = issue.getFields();
        if (fields == null) {
            return TaskStatus.NEW;
        }

        JiraStatus status = fields.getStatus();
        if (status == null || status.getName() == null) {
            return TaskStatus.NEW;
        }

        String name = status.getName().toLowerCase();
        if (name.contains("to do") || name.contains("open") || name.contains("new") || name.contains("backlog") || name.contains("todo")) {
            return TaskStatus.NEW;
        }
        if (name.contains("done") || name.contains("closed") || name.contains("resolved")) {
            return TaskStatus.DONE;
        }
        return TaskStatus.IN_PROGRESS;
    }

    @Override
    protected Integer toUrgency(JiraIssue issue, Instant asOf) {
        return null;
    }

    @Override
    protected Instant toDeadline(JiraIssue issue) {
        return null;
    }

    @Override
    protected Instant toSnoozedUntil(JiraIssue issue) {
        return null;
    }

    @Override
    protected Instant toCompletedAt(JiraIssue issue) {
        // Jira API currently exposes no business completion date field we reliably source,
        // so we leave completedAt to be derived downstream.
        return null;
    }
}
