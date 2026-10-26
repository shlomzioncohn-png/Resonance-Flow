package com.rnd.chain.dto;

import java.util.List;

/** One page of a tenant's state history; {@code nextCursor} is null on the last page. */
public record TenantStateHistoryPage(List<TaskStateHistoryEntry> content, String nextCursor) {
}
