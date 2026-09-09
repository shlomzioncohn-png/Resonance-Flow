package com.rnd.chain.controller;

import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.service.TaskDependencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Kept separate from the WIP ChainController on another branch to avoid a merge conflict; unify once that branch lands. */
@RestController
@RequestMapping("/tasks")
public class DependencyHistoryController {

    private final TaskDependencyService taskDependencyService;

    public DependencyHistoryController(TaskDependencyService taskDependencyService) {
        this.taskDependencyService = taskDependencyService;
    }

    /** size is capped at 100 (not just validated as positive) to bound how much a single page query can scan. */
    @GetMapping("/{taskId}/dependencies/history")
    public ResponseEntity<PageResponse<DependencyHistoryResponse>> getHistory(
            @PathVariable UUID taskId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (page < 0 || size <= 0 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size must be in 1..100");
        }
        PageResponse<DependencyHistoryEntry> entries = taskDependencyService.getHistory(taskId, page, size);
        return ResponseEntity.ok(entries.map(DependencyHistoryResponse::from));
    }
}
