package com.rnd.bridge.jira.dto;

import java.util.List;

public class JiraSearchResult {

    private List<JiraIssue> issues;
    private int total;

    public JiraSearchResult() {
    }

    public List<JiraIssue> getIssues() {
        return issues;
    }

    public void setIssues(List<JiraIssue> issues) {
        this.issues = issues;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }
}
