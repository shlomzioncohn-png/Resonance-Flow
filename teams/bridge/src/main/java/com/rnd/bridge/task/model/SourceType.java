package com.rnd.bridge.task.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SourceType {
    GITHUB_ISSUE,
    GITHUB_PR,
    JIRA_ISSUE,
    CRM_DEAL,
    EMAIL,
    SLACK,
    WHATSAPP,
    MEETING,
    DOC,
    SCANNED_DOC,
    CALENDAR;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
