package com.rnd.inbox.task.dto;

import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.model.TaskTier;

import java.time.Instant;

// Bound from GET /api/v1/tasks query params (search=&status=&...). Every field is optional -
// a null field means "don't filter on this", not "match empty/false". Spring binds this
// automatically the same way it already binds Pageable from page/size/sort.
//
// search is a single free-text box (the UI's "search by title or description") that matches
// title OR description - not two separately-ANDed fields, since that would require both fields
// to contain the typed text instead of either one.
//
// Deadline is a from/to range (raw Instant), not a bucket enum ("today"/"this week") - the
// client already has to resolve "today" against its own timezone to show the dropdown in the
// screenshot, so it computes the concrete bounds and sends them; the server only ever sees a
// plain range. noDeadline is separate because a range can't express "no value at all".
public class TaskFilterDto {

    private String search;
    private TaskStatus status;
    private Boolean available;
    private SourceType source;
    private TaskTier tier;
    private Instant deadlineFrom;
    private Instant deadlineTo;
    private Boolean noDeadline;

    public String getSearch() {
        return search;
    }

    public void setSearch(String search) {
        this.search = search;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Boolean getAvailable() {
        return available;
    }

    public void setAvailable(Boolean available) {
        this.available = available;
    }

    public SourceType getSource() {
        return source;
    }

    public void setSource(SourceType source) {
        this.source = source;
    }

    public TaskTier getTier() {
        return tier;
    }

    public void setTier(TaskTier tier) {
        this.tier = tier;
    }

    public Instant getDeadlineFrom() {
        return deadlineFrom;
    }

    public void setDeadlineFrom(Instant deadlineFrom) {
        this.deadlineFrom = deadlineFrom;
    }

    public Instant getDeadlineTo() {
        return deadlineTo;
    }

    public void setDeadlineTo(Instant deadlineTo) {
        this.deadlineTo = deadlineTo;
    }

    public Boolean getNoDeadline() {
        return noDeadline;
    }

    public void setNoDeadline(Boolean noDeadline) {
        this.noDeadline = noDeadline;
    }
}
