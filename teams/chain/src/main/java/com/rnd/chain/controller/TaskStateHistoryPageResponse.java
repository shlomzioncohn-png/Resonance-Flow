package com.rnd.chain.controller;

import com.rnd.chain.dto.TenantStateHistoryPage;

import java.util.List;

/** API-layer DTO for a cursor-paged page of {@link TaskStateHistoryResponse}; {@code nextCursor} is null on the last page. */
public record TaskStateHistoryPageResponse(List<TaskStateHistoryResponse> content, String nextCursor) {

    public static TaskStateHistoryPageResponse from(TenantStateHistoryPage page) {
        return new TaskStateHistoryPageResponse(
                page.content().stream().map(TaskStateHistoryResponse::from).toList(), page.nextCursor());
    }
}
