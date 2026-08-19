package com.rnd.chain.controller;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.UpdateConstraintRequest;
import com.rnd.chain.service.ChainService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tasks")
public class ChainController {

    private final ChainService chainService;

    public ChainController(ChainService chainService) {
        this.chainService = chainService;
    }

    /**
     * GET /tasks/{taskId}/dependencies
     * מחזיר את המשימות שהמשימה תלויה בהן ישירות.
     *
     * Example response:
     * [
     *   {
     *     "id": "11111111-1111-1111-1111-111111111111",
     *     "title": "Prepare data",
     *     "parentTaskId": null,
     *     "status": "ACTIVE",
     *     "ready": true,
     *     "blockingCount": 2
     *   }
     * ]
     */
    @GetMapping("/{taskId}/dependencies")
    public List<Task> getDependencies(@PathVariable UUID taskId) {
        return chainService.getDependencies(taskId);
    }

    /**
     * GET /tasks/{taskId}/dependents
     * מחזיר את המשימות שתלויות במשימה הזאת.
     *
     * Example response:
     * [
     *   {
     *     "id": "22222222-2222-2222-2222-222222222222",
     *     "title": "Publish report",
     *     "parentTaskId": null,
     *     "status": "ACTIVE",
     *     "ready": false,
     *     "blockingCount": 0
     *   }
     * ]
     */
    @GetMapping("/{taskId}/dependents")
    public List<Task> getDependents(@PathVariable UUID taskId) {
        return chainService.getDependents(taskId);
    }

    /**
     * GET /tasks/{taskId}/constraints
     * מחזיר את כל ה-Constraints של המשימה.
     *
     * Example response:
     * [
     *   {
     *     "constraintId": "constraint-1",
     *     "taskId": "33333333-3333-3333-3333-333333333333",
     *     "type": "APPROVAL_NEEDED",
     *     "status": "PENDING",
     *     "params": {
     *       "approver_id": "manager-12"
     *     },
     *     "sourceRef": "mail-456",
     *     "createdAt": "2026-08-19T10:00:00Z",
     *     "updatedAt": "2026-08-19T10:30:00Z"
     *   }
     * ]
     */
    @GetMapping("/{taskId}/constraints")
    public List<Constraint> getConstraints(@PathVariable UUID taskId) {
        return chainService.getConstraints(taskId);
    }

    /**
     * GET /tasks/{taskId}/blockers
     * מחזיר את המשימות שחוסמות כרגע את המשימה.
     *
     * Example response:
     * [
     *   {
     *     "id": "44444444-4444-4444-4444-444444444444",
     *     "title": "Complete review",
     *     "parentTaskId": null,
     *     "status": "ACTIVE",
     *     "ready": true,
     *     "blockingCount": 4
     *   }
     * ]
     */
    @GetMapping("/{taskId}/blockers")
    public List<Task> getBlockers(@PathVariable UUID taskId) {
        return chainService.getBlockers(taskId);
    }

    /**
     * GET /tasks/{taskId}/ancestors?maxDepth=2
     * מחזיר את המשימות שמעל המשימה בהיררכיה.
     *
     * Example response:
     * [
     *   {
     *     "id": "55555555-5555-5555-5555-555555555555",
     *     "title": "Parent task",
     *     "parentTaskId": "66666666-6666-6666-6666-666666666666",
     *     "status": "ACTIVE",
     *     "ready": true,
     *     "blockingCount": 1
     *   },
     *   {
     *     "id": "66666666-6666-6666-6666-666666666666",
     *     "title": "Root task",
     *     "parentTaskId": null,
     *     "status": "ACTIVE",
     *     "ready": true,
     *     "blockingCount": 3
     *   }
     * ]
     */
    @GetMapping("/{taskId}/ancestors")
    public List<Task> getAncestors(
            @PathVariable UUID taskId,
            @RequestParam(required = false) Integer maxDepth
    ) {
        return chainService.getAncestors(taskId, maxDepth);
    }

    /**
     * GET /tasks/{taskId}/descendants?maxDepth=2
     * מחזיר את המשימות שמתחת למשימה בהיררכיה.
     *
     * Example response:
     * [
     *   {
     *     "id": "77777777-7777-7777-7777-777777777777",
     *     "title": "Child task",
     *     "parentTaskId": "88888888-8888-8888-8888-888888888888",
     *     "status": "ACTIVE",
     *     "ready": true,
     *     "blockingCount": 0
     *   }
     * ]
     */
    @GetMapping("/{taskId}/descendants")
    public List<Task> getDescendants(
            @PathVariable UUID taskId,
            @RequestParam(required = false) Integer maxDepth
    ) {
        return chainService.getDescendants(taskId, maxDepth);
    }

    /**
     * PATCH /tasks/{taskId}/constraints/{constraintId}
     * מעדכן Constraint קיים.
     *
     * Example request:
     * {
     *   "status": "SATISFIED",
     *   "params": {
     *     "approver_id": "manager-12"
     *   }
     * }
     *
     * Example response:
     * {
     *   "constraintId": "constraint-1",
     *   "taskId": "33333333-3333-3333-3333-333333333333",
     *   "type": "APPROVAL_NEEDED",
     *   "status": "SATISFIED",
     *   "params": {
     *     "approver_id": "manager-12"
     *   },
     *   "sourceRef": "mail-456",
     *   "createdAt": "2026-08-19T10:00:00Z",
     *   "updatedAt": "2026-08-19T14:00:00Z"
     * }
     */
    @PatchMapping("/{taskId}/constraints/{constraintId}")
    public Constraint updateConstraint(
            @PathVariable UUID taskId,
            @PathVariable String constraintId,
            @RequestBody UpdateConstraintRequest request
    ) {
        return chainService.updateConstraint(taskId, constraintId, request);
    }

    /**
     * DELETE /tasks/{taskId}/constraints/{constraintId}
     * מוחק Constraint.
     *
     * Response:
     * 204 No Content
     */
    @DeleteMapping("/{taskId}/constraints/{constraintId}")
    public void deleteConstraint(
            @PathVariable UUID taskId,
            @PathVariable String constraintId
    ) {
        chainService.deleteConstraint(taskId, constraintId);
    }

    /**
     * DELETE /tasks/{taskId}/dependencies/{dependsOnTaskId}
     * מוחק קשר תלות בין שתי משימות.
     *
     * Response:
     * 204 No Content
     */
    @DeleteMapping("/{taskId}/dependencies/{dependsOnTaskId}")
    public void deleteDependency(
            @PathVariable UUID taskId,
            @PathVariable UUID dependsOnTaskId
    ) {
        chainService.deleteDependency(taskId, dependsOnTaskId);
    }
}