package com.rnd.bridge.github.dto;

import java.time.Instant;
import java.util.Map;

public class GitHubIssue {
    private Long number;
    private String title;
    private String body;
    private String state;
    private String htmlUrl;
    private Instant createdAt;
    private Instant updatedAt;
    private GitHubUser user;
    private Map<String, Object> pullRequest;

    public GitHubIssue() {
    }

    public Long getNumber() {
        return number;
    }

    public void setNumber(Long number) {
        this.number = number;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public GitHubUser getUser() {
        return user;
    }

    public void setUser(GitHubUser user) {
        this.user = user;
    }

    public Map<String, Object> getPullRequest() {
        return pullRequest;
    }

    public void setPullRequest(Map<String, Object> pullRequest) {
        this.pullRequest = pullRequest;
    }
}
