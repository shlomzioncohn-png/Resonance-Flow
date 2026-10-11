package com.rnd.inbox.taskselection;

import com.rnd.inbox.shared.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Task selection", description = "Pick the best tasks to work on within an available time window")
@RestController
@RequestMapping("/api/v1/tasks/selection")
public class TaskSelectionController {

    private final TaskSelectionService taskSelectionService;

    public TaskSelectionController(TaskSelectionService taskSelectionService) {
        this.taskSelectionService = taskSelectionService;
    }

    @Operation(summary = "Select the best tasks that fit into the available minutes",
            description = "Returns the selected tasks in suggested execution order. Pass userId to select for "
                    + "another user (SYSTEM_ADMIN: any user; everyone else: users in their own tenant); omit it to "
                    + "select for the actor. A userId outside the actor's scope, or of an unknown user, returns an "
                    + "empty selection. If no task fits the window, it is expanded to the shortest eligible task "
                    + "and used_fallback is true.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Selection computed (possibly empty)",
                    content = @Content(schema = @Schema(implementation = TaskSelectionResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "availableMinutes missing, not between 1 and "
                    + TaskSelectionRequestDto.MAX_AVAILABLE_MINUTES + ", or userId is not a UUID"),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity")
    })
    @GetMapping
    public TaskSelectionResponseDto selectTasks(@Valid TaskSelectionRequestDto request, @CurrentActor UUID actorId) {
        return taskSelectionService.selectTasksForUser(actorId, request.getUserId(), request.getAvailableMinutes());
    }
}
