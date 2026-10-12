package com.rnd.inbox.task.dto;

import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.model.TaskTier;

import java.time.Instant;
import java.util.UUID;

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
//
// userId means "tasks related to this user": tasks the user owns, tasks assigned to the user
// personally, or tasks assigned to a team they are currently a member of as long as nobody else
// is personally responsible for them (see TaskRepository.findFiltered for the exact rule). It
// only narrows the result inside
// the actor's existing scope - it never widens it - so a userId from a tenant the actor can't
// see simply yields an empty page (no 404, which would reveal that the user exists).
public class TaskFilterDto {

    private String search;
    private TaskStatus status;
    private Boolean available;
    private SourceType source;
    private TaskTier tier;
    private Instant deadlineFrom;
    private Instant deadlineTo;
    private Boolean noDeadline;
    private UUID userId;

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

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }
}
