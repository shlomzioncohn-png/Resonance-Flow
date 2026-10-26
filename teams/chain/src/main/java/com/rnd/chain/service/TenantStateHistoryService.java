package com.rnd.chain.service;

import com.rnd.chain.dto.TaskBlockedSince;
import com.rnd.chain.dto.TaskStateEventType;
import com.rnd.chain.dto.TaskStateHistoryRow;
import com.rnd.chain.dto.TenantStateHistoryPage;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.repository.TaskStateHistoryRepository;
import com.rnd.chain.repository.TransactionRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-wide read side of the task state history (Issue #1000), for the manager dashboard. */
@Service
@RequiredArgsConstructor
public class TenantStateHistoryService {

    static final int MAX_PAGE_SIZE = 100;
    static final Duration MAX_RANGE = Duration.ofDays(90);

    private final TaskStateHistoryRepository historyRepository;
    private final TransactionRunner transactionRunner;

    /**
     * Events of {@code tenantId} with {@code from <= createdAt < to}, oldest first. {@code after} is
     * the {@code nextCursor} of the previous page; a null {@code eventType} means all types.
     *
     * @throws InvalidRequestException if from/to is missing, from is after to, the range exceeds 90
     *                                 days, size is not in 1..100, or after is not a valid cursor
     */
    public TenantStateHistoryPage getHistory(UUID tenantId, Instant from, Instant to,
                                             TaskStateEventType eventType, String after, int size) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (from == null || to == null) {
            throw new InvalidRequestException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new InvalidRequestException("from must not be after to");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new InvalidRequestException("from and to must be at most 90 days apart");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("size must be in 1..100");
        }

        // Without a cursor, start just before the first possible row of the range.
        Cursor cursor = after == null ? new Cursor(from, Long.MIN_VALUE) : decodeCursor(after);
        List<TaskStateEventType> types = eventType == null
                ? List.of(TaskStateEventType.values()) : List.of(eventType);

        List<TaskStateHistoryRow> rows = transactionRunner.runInReadOnlyTransaction(() ->
                historyRepository.findByTenant(tenantId, from, to, types, cursor.time(), cursor.sequence(), size + 1));

        List<TaskStateHistoryRow> page = rows.subList(0, Math.min(size, rows.size()));
        String nextCursor = rows.size() > size ? encodeCursor(page.get(page.size() - 1)) : null;
        return new TenantStateHistoryPage(page.stream().map(TaskStateHistoryRow::entry).toList(), nextCursor);
    }

    /**
     * Tasks of {@code tenantId} whose latest BLOCKED/UNBLOCKED event is BLOCKED, oldest block first.
     * Null or empty {@code taskIds} means every blocked task of the tenant.
     */
    public List<TaskBlockedSince> getBlockedSince(UUID tenantId, List<UUID> taskIds) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return transactionRunner.runInReadOnlyTransaction(() ->
                historyRepository.findCurrentlyBlockedSince(tenantId, taskIds)).entrySet().stream()
                .map(e -> new TaskBlockedSince(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(TaskBlockedSince::blockedSince).thenComparing(TaskBlockedSince::taskId))
                .toList();
    }

    private static String encodeCursor(TaskStateHistoryRow last) {
        String raw = last.entry().createdAt() + "|" + last.sequence();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            return new Cursor(Instant.parse(raw.substring(0, sep)), Long.parseLong(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw new InvalidRequestException("after is not a valid cursor", e);
        }
    }

    private record Cursor(Instant time, long sequence) {
    }
}
