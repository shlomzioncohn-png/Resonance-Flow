package com.rnd.chain.service;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TaskStateHistoryRepository;
import com.rnd.chain.repository.TransactionRunner;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Read side of the task state history (Issue #775): validates the request, checks the task
 * exists, and delegates to {@link TaskStateHistoryRepository}. Writing is done elsewhere
 * (Issue #774), inside the transaction of the change it describes.
 */
@Service
public class TaskStateHistoryService {

    static final int MAX_PAGE_SIZE = 100;

    private final TaskRepository taskRepository;
    private final TaskStateHistoryRepository historyRepository;
    private final TransactionRunner transactionRunner;

    public TaskStateHistoryService(TaskRepository taskRepository,
                                   TaskStateHistoryRepository historyRepository,
                                   TransactionRunner transactionRunner) {
        this.taskRepository = taskRepository;
        this.historyRepository = historyRepository;
        this.transactionRunner = transactionRunner;
    }

    /**
     * Entries for {@code taskId} with {@code from <= createdAt < to}, newest first. A null bound
     * is open. A task that exists but has no history yields an empty page.
     *
     * @throws InvalidRequestException if page is negative, size is not in 1..100, or from is after to
     * @throws TaskNotFoundException   if the task does not exist
     */
    public PageResponse<TaskStateHistoryEntry> getHistory(UUID taskId, Instant from, Instant to,
                                                          int page, int size) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size must be in 1..100");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("from must not be after to");
        }
        taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));

        return transactionRunner.runInReadOnlyTransaction(
                () -> historyRepository.findByTaskId(taskId, from, to, page, size));
    }
}
