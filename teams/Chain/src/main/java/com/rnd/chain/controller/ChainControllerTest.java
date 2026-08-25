package com.rnd.chain.controller;

import com.rnd.chain.exception.ConstraintNotFoundException;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidConstraintException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;

import com.rnd.chain.model.Constraint;
import com.rnd.chain.model.Task;
import com.rnd.chain.model.TaskDependency;

import com.rnd.chain.service.ChainService;
import com.rnd.chain.service.ConstraintService;
import com.rnd.chain.service.DependencyGraphService;
import com.rnd.chain.dto.TaskEligibilityResponse;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(ChainController.class)
class ChainControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChainService chainService;

    @MockBean
    private DependencyGraphService dependencyGraphService;

    @MockBean
    private ConstraintService constraintService;


    // =========================================================
    // Stage 1 - Dependency Management Tests
    // =========================================================


    // 1. Create dependency -> 201 Created
    @Test
    void addDependency_shouldReturnCreated() throws Exception {

        TaskDependency dependency =
                new TaskDependency(null, null);

        when(chainService.addDependency(any(), any()))
                .thenReturn(dependency);

        String requestBody = """
                {
                  "dependsOnTaskId": "11111111-1111-1111-1111-111111111112"
                }
                """;

        mockMvc.perform(
                        post(
                                "/tasks/11111111-1111-1111-1111-111111111111/dependencies"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isCreated());
    }


    // 2. Delete dependency -> 204 No Content
    @Test
    void removeDependency_shouldReturnNoContent() throws Exception {

        doNothing()
                .when(chainService)
                .removeDependency(any(), any());

        mockMvc.perform(
                        delete(
                                "/tasks/11111111-1111-1111-1111-111111111111"
                                        + "/dependencies/"
                                        + "11111111-1111-1111-1111-111111111112"
                        )
                )
                .andExpect(status().isNoContent());
    }


    // 3. Get dependencies -> 200 OK + payload size
    @Test
    void getDependencies_shouldReturnDependencies() throws Exception {

        TaskDependency dependency1 =
                new TaskDependency(null, null);

        TaskDependency dependency2 =
                new TaskDependency(null, null);

        List<TaskDependency> dependencies =
                List.of(dependency1, dependency2);

        when(chainService.getDependencies(any()))
                .thenReturn(dependencies);

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/dependencies"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }


    // 4. Get dependents -> 200 OK + payload size
    @Test
    void getDependents_shouldReturnDependents() throws Exception {

        TaskDependency dependent1 =
                new TaskDependency(null, null);

        TaskDependency dependent2 =
                new TaskDependency(null, null);

        List<TaskDependency> dependents =
                List.of(dependent1, dependent2);

        when(chainService.getDependents(any()))
                .thenReturn(dependents);

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/dependents"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }


    // 5. Duplicate dependency -> 409 Conflict
    @Test
    void addDependency_whenDependencyAlreadyExists_shouldReturnConflict()
            throws Exception {

        when(chainService.addDependency(any(), any()))
                .thenThrow(
                        new DuplicateDependencyException(
                                "Dependency already exists"
                        )
                );

        String requestBody = """
                {
                  "dependsOnTaskId": "11111111-1111-1111-1111-111111111112"
                }
                """;

        mockMvc.perform(
                        post(
                                "/tasks/11111111-1111-1111-1111-111111111111/dependencies"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(
                        jsonPath("$.message")
                                .value("Dependency already exists")
                );
    }


    // 6. Invalid dependency -> 400 Bad Request
    @Test
    void addDependency_whenDependencyIsInvalid_shouldReturnBadRequest()
            throws Exception {

        when(chainService.addDependency(any(), any()))
                .thenThrow(
                        new InvalidDependencyException(
                                "Task cannot depend on itself"
                        )
                );

        String requestBody = """
                {
                  "dependsOnTaskId": "11111111-1111-1111-1111-111111111111"
                }
                """;

        mockMvc.perform(
                        post(
                                "/tasks/11111111-1111-1111-1111-111111111111/dependencies"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value("Task cannot depend on itself")
                );
    }


    // 7. Task not found -> 404 Not Found
    @Test
    void getDependencies_whenTaskDoesNotExist_shouldReturnNotFound()
            throws Exception {

        UUID missingTaskId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        when(chainService.getDependencies(any()))
                .thenThrow(
                        new TaskNotFoundException(missingTaskId)
                );

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/dependencies"
                        )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }


    // 8. Dependency not found -> 404 Not Found
    @Test
    void removeDependency_whenDependencyDoesNotExist_shouldReturnNotFound()
            throws Exception {

        doThrow(
                new DependencyNotFoundException(
                        "Dependency not found"
                )
        )
                .when(chainService)
                .removeDependency(any(), any());

        mockMvc.perform(
                        delete(
                                "/tasks/11111111-1111-1111-1111-111111111111"
                                        + "/dependencies/"
                                        + "11111111-1111-1111-1111-111111111112"
                        )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(
                        jsonPath("$.message")
                                .value("Dependency not found")
                );
    }


    // 9. Missing dependsOnTaskId -> 400 Bad Request
    @Test
    void addDependency_whenBodyIsInvalid_shouldReturnBadRequest()
            throws Exception {

        String requestBody = """
                {
                }
                """;

        mockMvc.perform(
                        post(
                                "/tasks/11111111-1111-1111-1111-111111111111/dependencies"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }


    // 10. No dependencies -> 200 OK with []
    @Test
    void getDependencies_whenNoDependencies_shouldReturnEmptyList()
            throws Exception {

        when(chainService.getDependencies(any()))
                .thenReturn(List.of());

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/dependencies"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }


    // 11. No dependents -> 200 OK with []
    @Test
    void getDependents_whenNoDependents_shouldReturnEmptyList()
            throws Exception {

        when(chainService.getDependents(any()))
                .thenReturn(List.of());

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/dependents"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }


    // =========================================================
    // Stage 2 - Dependency Traversal Tests
    // =========================================================


    // 12. Get blockers -> 200 OK
    @Test
    void getBlockers_shouldReturnBlockers() throws Exception {

        Task blocker1 = mock(Task.class);
        Task blocker2 = mock(Task.class);

        when(dependencyGraphService.getBlockers(any()))
                .thenReturn(List.of(blocker1, blocker2));

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/blockers"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }


    // 13. Get ancestors -> 200 OK
    @Test
    void getAncestors_shouldReturnAncestors() throws Exception {

        Task parent = mock(Task.class);
        Task grandParent = mock(Task.class);

        when(dependencyGraphService.getAncestors(any()))
                .thenReturn(List.of(parent, grandParent));

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/ancestors"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }


    // 14. Get descendants -> 200 OK
    @Test
    void getDescendants_shouldReturnDescendants() throws Exception {

        Task child = mock(Task.class);
        Task grandChild = mock(Task.class);

        when(dependencyGraphService.getDescendants(any()))
                .thenReturn(List.of(child, grandChild));

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/descendants"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }


    // 15. No blockers -> 200 OK with []
    @Test
    void getBlockers_whenNoBlockers_shouldReturnEmptyList()
            throws Exception {

        when(dependencyGraphService.getBlockers(any()))
                .thenReturn(List.of());

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/blockers"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }


    // 16. No ancestors -> 200 OK with []
    @Test
    void getAncestors_whenNoAncestors_shouldReturnEmptyList()
            throws Exception {

        when(dependencyGraphService.getAncestors(any()))
                .thenReturn(List.of());

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/ancestors"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }


    // 17. No descendants -> 200 OK with []
    @Test
    void getDescendants_whenNoDescendants_shouldReturnEmptyList()
            throws Exception {

        when(dependencyGraphService.getDescendants(any()))
                .thenReturn(List.of());

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/descendants"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }


    // 18. Task not found during traversal -> 404
    @Test
    void getAncestors_whenTaskDoesNotExist_shouldReturnNotFound()
            throws Exception {

        UUID missingTaskId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        when(dependencyGraphService.getAncestors(any()))
                .thenThrow(
                        new TaskNotFoundException(missingTaskId)
                );

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/ancestors"
                        )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }


    // 19. Invalid task ID -> 400 Bad Request
    @Test
    void getAncestors_whenTaskIdIsInvalid_shouldReturnBadRequest()
            throws Exception {

        mockMvc.perform(
                        get("/tasks/not-a-valid-uuid/ancestors")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value("Invalid task ID")
                );
    }


    // =========================================================
    // Stage 3 - Constraint Management Tests
    // =========================================================


    // 20. Create constraint -> 201 Created
    @Test
    void createConstraint_shouldReturnCreated() throws Exception {

        Constraint constraint = mock(Constraint.class);

        when(constraintService.createConstraint(any(), any()))
                .thenReturn(constraint);

        String requestBody = """
                {
                  "type": "APPROVAL_NEEDED",
                  "reason": {
                    "approver": "manager"
                  },
                  "sourceRef": "email-123"
                }
                """;

        mockMvc.perform(
                        post(
                                "/tasks/11111111-1111-1111-1111-111111111111/constraints"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isCreated());
    }


    // 21. Get constraints -> 200 OK + payload size
    @Test
    void getTaskConstraints_shouldReturnConstraints() throws Exception {

        Constraint constraint1 = mock(Constraint.class);
        Constraint constraint2 = mock(Constraint.class);

        when(constraintService.getTaskConstraints(any()))
                .thenReturn(List.of(constraint1, constraint2));

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/constraints"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }


    // 22. No constraints -> 200 OK with []
    @Test
    void getTaskConstraints_whenNoConstraints_shouldReturnEmptyList()
            throws Exception {

        when(constraintService.getTaskConstraints(any()))
                .thenReturn(List.of());

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/constraints"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }


    // 23. Update constraint status -> 200 OK
    @Test
    void updateConstraintStatus_shouldReturnUpdatedConstraint()
            throws Exception {

        Constraint updatedConstraint = mock(Constraint.class);

        when(constraintService.updateConstraintStatus(
                any(),
                any(),
                any()
        )).thenReturn(updatedConstraint);

        String requestBody = """
                {
                  "status": "SATISFIED"
                }
                """;

        mockMvc.perform(
                        patch(
                                "/tasks/11111111-1111-1111-1111-111111111111"
                                        + "/constraints/c1"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isOk());
    }


    // 24. Constraint not found -> 404 Not Found
    @Test
    void updateConstraintStatus_whenConstraintDoesNotExist_shouldReturnNotFound()
            throws Exception {

        when(constraintService.updateConstraintStatus(
                any(),
                any(),
                any()
        )).thenThrow(
                new ConstraintNotFoundException(
                        "Constraint not found"
                )
        );

        String requestBody = """
                {
                  "status": "SATISFIED"
                }
                """;

        mockMvc.perform(
                        patch(
                                "/tasks/11111111-1111-1111-1111-111111111111"
                                        + "/constraints/c1"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(
                        jsonPath("$.message")
                                .value("Constraint not found")
                );
    }


    // 25. Invalid constraint -> 400 Bad Request
    @Test
    void createConstraint_whenConstraintIsInvalid_shouldReturnBadRequest()
            throws Exception {

        when(constraintService.createConstraint(any(), any()))
                .thenThrow(
                        new InvalidConstraintException(
                                "Invalid constraint"
                        )
                );

        String requestBody = """
                {
                  "type": "DATE_WINDOW"
                }
                """;

        mockMvc.perform(
                        post(
                                "/tasks/11111111-1111-1111-1111-111111111111/constraints"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value("Invalid constraint")
                );
    }


    // 26. Missing constraint type -> 400 Bad Request
    @Test
    void createConstraint_whenTypeIsMissing_shouldReturnBadRequest()
            throws Exception {

        String requestBody = """
                {
                  "sourceRef": "email-123"
                }
                """;

        mockMvc.perform(
                        post(
                                "/tasks/11111111-1111-1111-1111-111111111111/constraints"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }


    // 27. Missing constraint status -> 400 Bad Request
    @Test
    void updateConstraintStatus_whenStatusIsMissing_shouldReturnBadRequest()
            throws Exception {

        String requestBody = """
                {
                }
                """;

        mockMvc.perform(
                        patch(
                                "/tasks/11111111-1111-1111-1111-111111111111"
                                        + "/constraints/c1"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }


    // 28. Task not found while getting constraints -> 404
    @Test
    void getTaskConstraints_whenTaskDoesNotExist_shouldReturnNotFound()
            throws Exception {

        UUID missingTaskId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        when(constraintService.getTaskConstraints(any()))
                .thenThrow(
                        new TaskNotFoundException(missingTaskId)
                );

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/constraints"
                        )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // =========================================================
    // Stage 4 - Task Eligibility Tests
    // =========================================================


    // 29. Ready task -> 200 OK
    @Test
    void getTaskEligibility_whenTaskIsReady_shouldReturnReadyState()
            throws Exception {

        UUID taskId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        TaskEligibilityResponse response =
                new TaskEligibilityResponse(
                        taskId,
                        true,
                        0,
                        0
                );

        when(chainService.getTaskEligibility(any()))
                .thenReturn(response);

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/eligibility"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.taskId")
                                .value(
                                        "11111111-1111-1111-1111-111111111111"
                                )
                )
                .andExpect(jsonPath("$.isReady").value(true))
                .andExpect(jsonPath("$.blockingCount").value(0))
                .andExpect(jsonPath("$.openConstraintCount").value(0));
    }


    // 30. Blocked task -> 200 OK
    @Test
    void getTaskEligibility_whenTaskIsBlocked_shouldReturnBlockedState()
            throws Exception {

        UUID taskId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        TaskEligibilityResponse response =
                new TaskEligibilityResponse(
                        taskId,
                        false,
                        2,
                        1
                );

        when(chainService.getTaskEligibility(any()))
                .thenReturn(response);

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/eligibility"
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isReady").value(false))
                .andExpect(jsonPath("$.blockingCount").value(2))
                .andExpect(jsonPath("$.openConstraintCount").value(1));
    }


    // 31. Task not found -> 404
    @Test
    void getTaskEligibility_whenTaskDoesNotExist_shouldReturnNotFound()
            throws Exception {

        UUID missingTaskId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111"
                );

        when(chainService.getTaskEligibility(any()))
                .thenThrow(
                        new TaskNotFoundException(missingTaskId)
                );

        mockMvc.perform(
                        get(
                                "/tasks/11111111-1111-1111-1111-111111111111/eligibility"
                        )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }


    // 32. Invalid task ID -> 400
    @Test
    void getTaskEligibility_whenTaskIdIsInvalid_shouldReturnBadRequest()
            throws Exception {

        mockMvc.perform(
                        get("/tasks/not-a-valid-uuid/eligibility")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value("Invalid task ID")
                );
    }
}