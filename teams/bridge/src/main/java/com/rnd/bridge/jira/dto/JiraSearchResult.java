package com.rnd.bridge.jira.dto;

import java.util.List;

public class JiraSearchResult {

    private List<JiraIssue> issues;
    private boolean isLast;
    private String nextPageToken;

    public JiraSearchResult() {
    }

    public List<JiraIssue> getIssues() {
        return issues;
    }

    public void setIssues(List<JiraIssue> issues) {
        this.issues = issues;
    }

    public boolean isLast() {
        return isLast;
    }

    public void setLast(boolean last) {
        isLast = last;
    }

    public String getNextPageToken() {
        return nextPageToken;
    }

    public void setNextPageToken(String nextPageToken) {
        this.nextPageToken = nextPageToken;
    }
}
