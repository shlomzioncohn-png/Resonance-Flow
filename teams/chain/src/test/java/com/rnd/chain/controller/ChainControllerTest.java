package com.rnd.chain.controller;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskAvailability;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskEligibilityResponse;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.ConstraintNotFoundException;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidConstraintException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.service.ConstraintService;
import com.rnd.chain.service.DependencyGraphService;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.service.TaskEligibilityService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChainController.class)
class ChainControllerTest {

    private static final UUID TASK_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID OTHER_TASK_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111112");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskDependencyService taskDependencyService;

    @MockBean
    private DependencyGraphService dependencyGraphService;

    @MockBean
    private ConstraintService constraintService;

    @MockBean
    private TaskEligibilityService taskEligibilityService;

    // =========================================================
    // Stage 1 - Dependency Management
    // =========================================================

    @Test
    void addDependency_shouldReturnCreatedAndLocation()
            throws Exception {

        TaskDependency dependency =
                new TaskDependency(
                        TASK_ID,
                        OTHER_TASK_ID);

        when(taskDependencyService
                .addDependency(any(), any()))
                .thenReturn(dependency);

        String requestBody =
                "{\"dependsOnTaskId\":"
                        + "\"11111111-1111-1111-1111-111111111112\"}";

        mockMvc.perform(
                        post(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                "Location",
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies/"
                                        + OTHER_TASK_ID))
                .andExpect(
                        jsonPath("$.taskId")
                                .value(
                                        TASK_ID.toString()))
                .andExpect(
                        jsonPath("$.dependsOnTaskId")
                                .value(
                                        OTHER_TASK_ID.toString()));
    }

    @Test
    void removeDependency_shouldReturnNoContent()
            throws Exception {

        doNothing()
                .when(taskDependencyService)
                .removeDependency(any(), any());

        mockMvc.perform(
                        delete(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies/"
                                        + OTHER_TASK_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    void getDependencies_shouldReturnUuidList()
            throws Exception {

        UUID another =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111113");

        when(taskDependencyService
                .getDependencies(any()))
                .thenReturn(
                        List.of(
                                OTHER_TASK_ID,
                                another));

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.length()")
                                .value(2))
                .andExpect(
                        jsonPath("$[0]")
                                .value(
                                        OTHER_TASK_ID.toString()));
    }

    @Test
    void getDependents_shouldReturnUuidList()
            throws Exception {

        when(taskDependencyService
                .getDependents(any()))
                .thenReturn(
                        List.of(
                                OTHER_TASK_ID));

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependents"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.length()")
                                .value(1))
                .andExpect(
                        jsonPath("$[0]")
                                .value(
                                        OTHER_TASK_ID.toString()));
    }

    @Test
    void addDependency_whenDuplicate_shouldReturnConflict()
            throws Exception {

        when(taskDependencyService
                .addDependency(any(), any()))
                .thenThrow(
                        new DuplicateDependencyException(
                                "Dependency already exists"));

        String requestBody =
                "{\"dependsOnTaskId\":"
                        + "\"11111111-1111-1111-1111-111111111112\"}";

        mockMvc.perform(
                        post(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.status")
                                .value(409))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Dependency already exists"));
    }

    @Test
    void addDependency_whenSelfDependency_shouldReturnBadRequest()
            throws Exception {

        when(taskDependencyService
                .addDependency(any(), any()))
                .thenThrow(
                        new InvalidDependencyException(
                                "Task cannot depend on itself"));

        String requestBody =
                "{\"dependsOnTaskId\":"
                        + "\"11111111-1111-1111-1111-111111111111\"}";

        mockMvc.perform(
                        post(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.status")
                                .value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Task cannot depend on itself"));
    }

    @Test
    void addDependency_whenCycleDetected_shouldReturnBadRequest()
            throws Exception {

        when(taskDependencyService
                .addDependency(any(), any()))
                .thenThrow(
                        new InvalidDependencyException(
                                "Dependency would create a cycle"));

        String requestBody =
                "{\"dependsOnTaskId\":"
                        + "\"11111111-1111-1111-1111-111111111112\"}";

        mockMvc.perform(
                        post(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Dependency would create a cycle"));
    }

    @Test
    void removeDependency_whenMissing_shouldReturnNotFound()
            throws Exception {

        doThrow(
                new DependencyNotFoundException(
                        "Dependency not found"))
                .when(taskDependencyService)
                .removeDependency(any(), any());

        mockMvc.perform(
                        delete(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies/"
                                        + OTHER_TASK_ID))
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.status")
                                .value(404))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Dependency not found"));
    }

    @Test
    void addDependency_whenBodyMissingId_shouldReturnBadRequest()
            throws Exception {

        mockMvc.perform(
                        post(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.status")
                                .value(400));
    }

    @Test
    void getDependencies_whenEmpty_shouldReturnEmptyArray()
            throws Exception {

        when(taskDependencyService
                .getDependencies(any()))
                .thenReturn(List.of());

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // =========================================================
    // Stage 2 - Traversal
    // =========================================================

    @Test
    void getBlockers_shouldReturnAvailabilityObject()
            throws Exception {

        Task blocker =
                createTask(OTHER_TASK_ID);

        TaskAvailability availability =
                new TaskAvailability(
                        List.of(blocker),
                        List.of());

        when(dependencyGraphService
                .getBlockers(any()))
                .thenReturn(availability);

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/blockers"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.blockingTasks.length()")
                                .value(1))
                .andExpect(
                        jsonPath(
                                "$.openConstraints.length()")
                                .value(0));
    }

    @Test
    void getAncestors_shouldReturnList()
            throws Exception {

        when(dependencyGraphService
                .getAncestors(any()))
                .thenReturn(
                        List.of(
                                createTask(
                                        OTHER_TASK_ID)));

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/ancestors"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.length()")
                                .value(1));
    }

    @Test
    void getDescendants_shouldReturnList()
            throws Exception {

        when(dependencyGraphService
                .getDescendants(any()))
                .thenReturn(
                        List.of(
                                createTask(
                                        OTHER_TASK_ID)));

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/descendants"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.length()")
                                .value(1));
    }

    @Test
    void traversal_whenTaskMissing_shouldReturnNotFound()
            throws Exception {

        when(dependencyGraphService
                .getAncestors(any()))
                .thenThrow(
                        new TaskNotFoundException(
                                TASK_ID));

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/ancestors"))
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.status")
                                .value(404))
                .andExpect(
                        jsonPath("$.error")
                                .exists())
                .andExpect(
                        jsonPath("$.path")
                                .value(
                                        "/tasks/"
                                                + TASK_ID
                                                + "/ancestors"));
    }

    @Test
    void traversal_whenTaskIdInvalid_shouldReturnBadRequest()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + "not-a-valid-uuid"
                                        + "/ancestors"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.status")
                                .value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Invalid task ID"));
    }

    // =========================================================
    // Stage 3 - Constraints
    // =========================================================

    @Test
    void createConstraint_shouldReturnCreatedAndLocation()
            throws Exception {

        Constraint constraint =
                createConstraint("c1");

        when(constraintService
                .createConstraint(
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(constraint);

        String requestBody =
                "{"
                        + "\"type\":\"APPROVAL_NEEDED\","
                        + "\"reason\":"
                        + "{\"approver\":\"manager\"},"
                        + "\"sourceRef\":\"email-123\""
                        + "}";

        mockMvc.perform(
                        post(
                                "/tasks/"
                                        + TASK_ID
                                        + "/constraints")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                "Location",
                                "/tasks/"
                                        + TASK_ID
                                        + "/constraints/c1"));
    }

    @Test
    void getTaskConstraints_shouldReturnList()
            throws Exception {

        when(constraintService
                .getTaskConstraints(any()))
                .thenReturn(
                        List.of(
                                createConstraint("c1"),
                                createConstraint("c2")));

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/constraints"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.length()")
                                .value(2));
    }

    @Test
    void updateConstraintStatus_shouldReturnOk()
            throws Exception {

        when(constraintService
                .updateConstraintStatus(
                        any(),
                        any()))
                .thenReturn(
                        createConstraint("c1"));

        mockMvc.perform(
                        patch(
                                "/tasks/"
                                        + TASK_ID
                                        + "/constraints/c1")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"status\":"
                                                + "\"SATISFIED\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateConstraintStatus_whenMissing_shouldReturnNotFound()
            throws Exception {

        when(constraintService
                .updateConstraintStatus(
                        any(),
                        any()))
                .thenThrow(
                        new ConstraintNotFoundException(
                                "Constraint not found"));

        mockMvc.perform(
                        patch(
                                "/tasks/"
                                        + TASK_ID
                                        + "/constraints/c1")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"status\":"
                                                + "\"SATISFIED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.status")
                                .value(404));
    }

    @Test
    void createConstraint_whenInvalid_shouldReturnBadRequest()
            throws Exception {

        when(constraintService
                .createConstraint(
                        any(),
                        any(),
                        any(),
                        any()))
                .thenThrow(
                        new InvalidConstraintException(
                                "Invalid constraint"));

        mockMvc.perform(
                        post(
                                "/tasks/"
                                        + TASK_ID
                                        + "/constraints")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"type\":"
                                                + "\"DATE_WINDOW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Invalid constraint"));
    }

    @Test
    void createConstraint_whenTypeMissing_shouldReturnBadRequest()
            throws Exception {

        mockMvc.perform(
                        post(
                                "/tasks/"
                                        + TASK_ID
                                        + "/constraints")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"sourceRef\":"
                                                + "\"email-123\"}"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================
    // Stage 4 - Eligibility
    // =========================================================

    @Test
    void getTaskEligibility_whenReady_shouldReturnReadyState()
            throws Exception {

        TaskEligibilityResponse response =
                new TaskEligibilityResponse(
                        TASK_ID,
                        true,
                        0,
                        0);

        when(taskEligibilityService
                .getTaskEligibility(any()))
                .thenReturn(response);

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/eligibility"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.taskId")
                                .value(
                                        TASK_ID.toString()))
                .andExpect(
                        jsonPath("$.isReady")
                                .value(true))
                .andExpect(
                        jsonPath("$.blockingCount")
                                .value(0))
                .andExpect(
                        jsonPath("$.openConstraintCount")
                                .value(0));
    }

    @Test
    void getTaskEligibility_whenBlocked_shouldReturnBlockedState()
            throws Exception {

        TaskEligibilityResponse response =
                new TaskEligibilityResponse(
                        TASK_ID,
                        false,
                        2,
                        1);

        when(taskEligibilityService
                .getTaskEligibility(any()))
                .thenReturn(response);

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/eligibility"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.isReady")
                                .value(false))
                .andExpect(
                        jsonPath("$.blockingCount")
                                .value(2))
                .andExpect(
                        jsonPath("$.openConstraintCount")
                                .value(1));
    }

    @Test
    void getTaskEligibility_whenTaskMissing_shouldReturnNotFound()
            throws Exception {

        when(taskEligibilityService
                .getTaskEligibility(any()))
                .thenThrow(
                        new TaskNotFoundException(
                                TASK_ID));

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/eligibility"))
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.status")
                                .value(404));
    }

    // =========================================================
    // Error handling
    // =========================================================

    @Test
    void unexpectedException_shouldReturn500()
            throws Exception {

        when(taskDependencyService
                .getDependencies(any()))
                .thenThrow(
                        new RuntimeException(
                                "boom"));

        mockMvc.perform(
                        get(
                                "/tasks/"
                                        + TASK_ID
                                        + "/dependencies"))
                .andExpect(
                        status()
                                .isInternalServerError())
                .andExpect(
                        jsonPath("$.status")
                                .value(500))
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "INTERNAL_SERVER_ERROR"))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Internal server error"))
                .andExpect(
                        jsonPath("$.path")
                                .value(
                                        "/tasks/"
                                                + TASK_ID
                                                + "/dependencies"));
    }

    private Task createTask(UUID id) {

        LocalDateTime now =
                LocalDateTime.now();

        return Task.builder()
                .id(id)
                .parentTaskId(null)
                .ownerId("test-owner")
                .status(TaskStatus.ACTIVE)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .estimatedDurationMin(null)
                .score(null)
                .createdAt(now)
                .updatedAt(now)
                .deletedAt(null)
                .build();
    }

    private Constraint createConstraint(
            String constraintId) {

        LocalDateTime now =
                LocalDateTime.now();

        return new Constraint(
                constraintId,
                TASK_ID,
                Constraint.ConstraintType.APPROVAL_NEEDED,
                Constraint.ConstraintStatus.PENDING,
                Map.of(
                        "approver",
                        "manager"),
                "email-123",
                now,
                now);
    }
}