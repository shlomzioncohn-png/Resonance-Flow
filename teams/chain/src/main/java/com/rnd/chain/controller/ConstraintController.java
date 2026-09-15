package com.rnd.chain.controller;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.CreateConstraintRequest;
import com.rnd.chain.dto.UpdateConstraintStatusRequest;
import com.rnd.chain.service.ConstraintService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import javax.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tasks")
@Tag(
        name = "Task Constraints",
        description = "Manage constraints associated with tasks"
)
public class ConstraintController {

    private final ConstraintService constraintService;

    public ConstraintController(
            ConstraintService constraintService) {

        this.constraintService = constraintService;
    }

    @Operation(
            summary = "Create task constraint",
            description = "Creates a new constraint for the specified task"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Constraint created successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid constraint request"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Task not found"
            )
    })
    @PostMapping("/{taskId}/constraints")
    public ResponseEntity<Constraint> createConstraint(
            @PathVariable UUID taskId,
            @Valid @RequestBody CreateConstraintRequest request) {

        Constraint createdConstraint =
                constraintService.createConstraint(
                        taskId,
                        request.getType(),
                        request.getReason(),
                        request.getSourceRef());

        URI location = URI.create(
                "/tasks/"
                        + taskId
                        + "/constraints/"
                        + createdConstraint.getConstraintId());

        return ResponseEntity
                .created(location)
                .body(createdConstraint);
    }

    @Operation(
            summary = "Get task constraints",
            description = "Returns all constraints associated with the specified task"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Constraints returned successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Task not found"
            )
    })
    @GetMapping("/{taskId}/constraints")
    public ResponseEntity<List<Constraint>> getTaskConstraints(
            @PathVariable UUID taskId) {

        List<Constraint> constraints =
                constraintService.getTaskConstraints(taskId);

        return ResponseEntity.ok(constraints);
    }

    @Operation(
            summary = "Update constraint status",
            description = "Updates the status of an existing task constraint"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Constraint updated successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid status request"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Constraint not found"
            )
    })
    @PatchMapping("/{taskId}/constraints/{constraintId}")
    public ResponseEntity<Constraint> updateConstraintStatus(
            @PathVariable UUID taskId,
            @PathVariable String constraintId,
            @Valid @RequestBody UpdateConstraintStatusRequest request) {

        Constraint updatedConstraint =
                constraintService.updateConstraintStatus(
                        taskId,
                        constraintId,
                        request.getStatus());

        return ResponseEntity.ok(updatedConstraint);
    }
}