package com.rnd.bff.controller;

import com.rnd.bff.dto.TaskRecommendationDTO;
import com.rnd.bff.dto.WorkspaceRequestDTO;
import com.rnd.bff.service.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee workspace recommendations.
 *
 * Gateway forwards POST /api/workspace/recommend here as POST
 * /workspace/recommend (StripPrefix=1), with the caller's identity in
 * X-User-Id — see docs/workspace-screen-integration-plan.md.
 */
@RestController
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping("/workspace/recommend")
    public ResponseEntity<TaskRecommendationDTO> recommend(
            @Valid @RequestBody WorkspaceRequestDTO request,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(workspaceService.recommend(request, userId));
    }
}
