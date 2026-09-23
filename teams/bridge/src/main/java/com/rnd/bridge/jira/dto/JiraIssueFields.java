package com.rnd.bridge.jira.dto;

import java.time.Instant;

public class JiraIssueFields {

    private String summary;
    private String description;
    private JiraStatus status;
    private JiraIssueType issuetype;
    private Instant created;
    private Instant updated;

    public JiraIssueFields() {
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JiraStatus getStatus() {
        return status;
    }

    public void setStatus(JiraStatus status) {
        this.status = status;
    }

    public JiraIssueType getIssuetype() {
        return issuetype;
    }

    public void setIssuetype(JiraIssueType issuetype) {
        this.issuetype = issuetype;
    }

    public Instant getCreated() {
        return created;
    }

    public void setCreated(Instant created) {
        this.created = created;
    }

    public Instant getUpdated() {
        return updated;
    }

    public void setUpdated(Instant updated) {
        this.updated = updated;
    }
}
