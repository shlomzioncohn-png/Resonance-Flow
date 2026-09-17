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

/**
 * Kept separate from the WIP ChainController on another branch to avoid a merge conflict;
 * unify once that branch lands - tracked as issue #401.
 *
 * @see <a href="https://github.com/KamaTechOrg/Resonance-Flow/issues/401">#401</a>
 */
@RestController
@RequestMapping("/tasks")
public class DependencyHistoryController {

    private final TaskDependencyService taskDependencyService;

    public DependencyHistoryController(TaskDependencyService taskDependencyService) {
        this.taskDependencyService = taskDependencyService;
    }

    /** page/size validation (size capped at 100) lives in {@link TaskDependencyService#getHistory}, the shared
     * boundary every caller of this method goes through - not duplicated here. */
    @GetMapping("/{taskId}/dependencies/history")
    public ResponseEntity<PageResponse<DependencyHistoryResponse>> getHistory(
            @PathVariable UUID taskId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<DependencyHistoryEntry> entries = taskDependencyService.getHistory(taskId, page, size);
        return ResponseEntity.ok(entries.map(DependencyHistoryResponse::from));
    }
}
