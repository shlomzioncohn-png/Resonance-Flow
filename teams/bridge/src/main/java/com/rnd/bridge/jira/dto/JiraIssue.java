package com.rnd.bridge.jira.dto;

public class JiraIssue {

    private String id;
    private String key;
    private String self;
    private JiraIssueFields fields;

    public JiraIssue() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getSelf() {
        return self;
    }

    public void setSelf(String self) {
        this.self = self;
    }

    public JiraIssueFields getFields() {
        return fields;
    }

    public void setFields(JiraIssueFields fields) {
        this.fields = fields;
    }
}
