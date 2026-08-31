// Violation example for the architecture-review skill's test scenarios (see ../../TEST-SCENARIOS.md).
// This file's *filename* stands in for its intended location: a PR that adds this class at
// shared/common/java/com/rnd/inbox/util/TaskTitleFormatter.java instead of teams/inbox.
// Expected finding: Major — Inbox-only code placed under shared/ (CLAUDE.md §13).
package com.rnd.inbox.util;

// Formats a Task title for display in the Inbox UI only — nothing about this is used, or asked
// for, by any other team, yet it was added under shared/common instead of teams/inbox.
public class TaskTitleFormatter {

    public static String truncateForList(String title) {
        return title.length() > 60 ? title.substring(0, 57) + "..." : title;
    }
}
