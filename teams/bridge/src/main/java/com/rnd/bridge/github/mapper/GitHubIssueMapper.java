package com.rnd.bridge.github.mapper;

import com.rnd.bridge.github.dto.GitHubIssue;
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
public class GitHubIssueMapper extends AbstractTaskMapper<GitHubIssue> {

    public GitHubIssueMapper(EnrichmentProperties enrichmentProperties, TaskEnricher taskEnricher) {
        super(enrichmentProperties, taskEnricher);
    }

    @Override
    protected SourceType sourceType() {
        return SourceType.GITHUB_ISSUE;
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
    protected String toTitle(GitHubIssue issue) {
        return issue.getTitle();
    }

    @Override
    protected String toDescription(GitHubIssue issue) {
        return issue.getBody();
    }

    @Override
    protected String toSourceRef(GitHubIssue issue) {
        return issue.getHtmlUrl();
    }

    @Override
    protected TaskStatus toStatus(GitHubIssue issue) {
        String githubState = issue.getState();
        if (githubState == null) {
            return TaskStatus.NEW;
        }
        return switch (githubState.toLowerCase()) {
            case "open" -> TaskStatus.NEW;
            case "closed" -> TaskStatus.DONE;
            default -> TaskStatus.IN_PROGRESS;
        };
    }

    @Override
    protected Integer toUrgency(GitHubIssue issue, Instant asOf) {
        return null;
    }

    @Override
    protected Instant toDeadline(GitHubIssue issue) {
        return null;
    }

    @Override
    protected Instant toSnoozedUntil(GitHubIssue issue) {
        return null;
    }

    @Override
    protected Instant toCompletedAt(GitHubIssue issue) {
        // No business completion date to map: closed_at is when the issue
        // was closed in GitHub, not the same thing as a business completion
        // date. Deciding whether/how to map it is for the GitHub lane's
        // owner, not assumed here.
        return null;
    }
}
