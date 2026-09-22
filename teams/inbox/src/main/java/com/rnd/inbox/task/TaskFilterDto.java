package com.rnd.inbox.task;

import java.time.Instant;

// Bound from GET /api/v1/tasks query params (title=&status=&...). Every field is optional -
// a null field means "don't filter on this", not "match empty/false". Spring binds this
// automatically the same way it already binds Pageable from page/size/sort.
//
// Deadline is a from/to range (raw Instant), not a bucket enum ("today"/"this week") - the
// client already has to resolve "today" against its own timezone to show the dropdown in the
// screenshot, so it computes the concrete bounds and sends them; the server only ever sees a
// plain range. noDeadline is separate because a range can't express "no value at all".
public class TaskFilterDto {

    private String title;
    private String description;
    private TaskStatus status;
    private Boolean available;
    private SourceType source;
    private Instant deadlineFrom;
    private Instant deadlineTo;
    private Boolean noDeadline;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
