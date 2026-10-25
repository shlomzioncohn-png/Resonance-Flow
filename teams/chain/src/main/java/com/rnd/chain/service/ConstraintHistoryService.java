package com.rnd.chain.service;

import com.rnd.chain.dto.ConstraintHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.ConstraintHistoryRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TransactionRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read side of the constraint history (Issue #875): validates the request, checks the task exists, and pages the history. */
@Service
@RequiredArgsConstructor
public class ConstraintHistoryService {

    static final int MAX_PAGE_SIZE = 100;

    private final TaskRepository taskRepository;
    private final ConstraintHistoryRepository historyRepository;
    private final TransactionRunner transactionRunner;

    /**
     * Entries for {@code taskId} with {@code from <= createdAt < to}, newest first; a null bound is open.
     * History outlives its task (soft delete), so a missing task is still readable if it left history behind.
     *
     * @throws InvalidRequestException if page is negative, size is not in 1..100, or from is after to
     * @throws TaskNotFoundException   if there is neither a task nor any history for {@code taskId}
     */
    public PageResponse<ConstraintHistoryEntry> getHistory(UUID taskId, Instant from, Instant to,
                                                           int page, int size) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size must be in 1..100");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("from must not be after to");
        }

        return transactionRunner.runInReadOnlyTransaction(() -> {
            requireTaskOrHistory(taskId);
            return historyRepository.findByTaskId(taskId, from, to, page, size);
        });
    }

    /** 404 only when the id is unknown to both the task store and the history (a mistyped id). */
    private void requireTaskOrHistory(UUID taskId) {
        if (taskRepository.findById(taskId).isEmpty() && !historyRepository.existsByTaskId(taskId)) {
            throw new TaskNotFoundException("Task not found: " + taskId);
        }
    }
}
