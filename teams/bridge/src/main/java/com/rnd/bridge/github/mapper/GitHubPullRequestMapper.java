package com.rnd.bridge.github.mapper;

import com.rnd.bridge.github.dto.GitHubIssue;
import com.rnd.bridge.task.AbstractTaskMapper;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class GitHubPullRequestMapper extends AbstractTaskMapper<GitHubIssue> {

    @Override
    protected SourceType sourceType() {
        return SourceType.GITHUB_PR;
    }

    @Override
    protected Tier tier() {
        return Tier.TACTICAL;
    }

    @Override
    protected RoleContext roleContext() {
        return RoleContext.MANAGER;
    }

    @Override
    protected String toTitle(GitHubIssue pr) {
        return pr.getTitle();
    }

    @Override
    protected String toDescription(GitHubIssue pr) {
        return pr.getBody();
    }

    @Override
    protected String toSourceRef(GitHubIssue pr) {
        return pr.getHtmlUrl();
    }

    @Override
    protected TaskStatus toStatus(GitHubIssue pr) {
        String githubState = pr.getState();
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
    protected Integer toUrgency(GitHubIssue pr, Instant asOf) {
        return null;
    }

    @Override
    protected Instant toDeadline(GitHubIssue pr) {
        return null;
    }

    @Override
    protected Instant toSnoozedUntil(GitHubIssue pr) {
        return null;
    }
}
