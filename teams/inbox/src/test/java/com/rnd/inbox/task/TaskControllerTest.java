package com.rnd.inbox.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.shared.config.AppConfig;
import com.rnd.inbox.shared.config.WebMvcConfig;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.tenant.TenantNotFoundException;
import com.rnd.inbox.user.User;
import com.rnd.inbox.user.UserRepository;
import com.rnd.inbox.user.UserRole;
import com.rnd.inbox.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
@Import({AppConfig.class, WebMvcConfig.class})
class TaskControllerTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TaskService taskService;

    @MockBean
    private UserRepository userRepository;

    private UUID taskId;
    private UUID tenantId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        taskId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        // No @RequireRole on TaskController, but WebMvcConfig still needs UserRepository wired
        // for RoleAuthorizationInterceptor - default to a role, unused by any test here since
        // no endpoint checks it.
        User actorEntity = new User();
        actorEntity.setId(actorId);
        actorEntity.setRole(UserRole.EMPLOYEE);
        actorEntity.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actorEntity));
    }

    private TaskResponseDto sampleTask() {
        return new TaskResponseDto(taskId, null, null, "Fix flaky test", null, null, null, null, null,
                null, null, null, TaskStatus.NEW, false, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private TaskCreateRequestDto validCreateRequest() {
        TaskCreateRequestDto request = new TaskCreateRequestDto();
        request.setTenantId(tenantId.toString());
        request.setTitle("Fix flaky test");
        return request;
    }

    private TaskUpdateRequestDto validUpdateRequest() {
        TaskUpdateRequestDto request = new TaskUpdateRequestDto();
        request.setTitle("New title");
        return request;
    }

    @Test
    void createTask_withValidRequest_returns201WithLocationHeader() throws Exception {
        when(taskService.createTaskFromRequest(any(), eq(actorId))).thenReturn(sampleTask());

        mockMvc.perform(post("/api/v1/tasks")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/tasks/" + taskId)))
                .andExpect(jsonPath("$.id").value(taskId.toString()))
                .andExpect(jsonPath("$.title").value("Fix flaky test"));
    }

    @Test
    void createTask_withMissingRequiredFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TaskCreateRequestDto())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void createTask_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void createTask_whenTenantNotFound_returns404() throws Exception {
        when(taskService.createTaskFromRequest(any(), eq(actorId))).thenThrow(new TenantNotFoundException(tenantId));

        mockMvc.perform(post("/api/v1/tasks")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    void createTask_whenActorOutsideRequestedTenant_returns403() throws Exception {
        when(taskService.createTaskFromRequest(any(), eq(actorId)))
                .thenThrow(new ForbiddenActionException("Actor cannot act on tenant " + tenantId));

        mockMvc.perform(post("/api/v1/tasks")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void getTaskById_whenFound_returns200() throws Exception {
        when(taskService.getTaskById(taskId, actorId)).thenReturn(sampleTask());

        mockMvc.perform(get("/api/v1/tasks/{id}", taskId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId.toString()));
    }

    @Test
    void getTaskById_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/{id}", taskId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void getTaskById_whenNotFound_returns404() throws Exception {
        when(taskService.getTaskById(taskId, actorId)).thenThrow(new TaskNotFoundException(taskId));

        mockMvc.perform(get("/api/v1/tasks/{id}", taskId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));
    }

    @Test
    void getTaskById_whenActorOutsideTargetTenant_returns403() throws Exception {
        when(taskService.getTaskById(taskId, actorId))
                .thenThrow(new ForbiddenActionException("Actor cannot act on task " + taskId));

        mockMvc.perform(get("/api/v1/tasks/{id}", taskId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void listTasks_returns200WithPageResponse() throws Exception {
        PageResponseDto<TaskResponseDto> page = new PageResponseDto<>(List.of(sampleTask()), 0, 20, 1, 1);
        when(taskService.listTasks(any(), eq(actorId))).thenReturn(page);

        mockMvc.perform(get("/api/v1/tasks").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(taskId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listTasks_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void updateTask_withValidRequest_returns200() throws Exception {
        when(taskService.updateTaskFromRequest(eq(taskId), any(), eq(actorId))).thenReturn(sampleTask());

        mockMvc.perform(patch("/api/v1/tasks/{id}", taskId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId.toString()));
    }

    @Test
    void updateTask_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/tasks/{id}", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void updateTask_whenNotFound_returns404() throws Exception {
        when(taskService.updateTaskFromRequest(eq(taskId), any(), eq(actorId)))
                .thenThrow(new TaskNotFoundException(taskId));

        mockMvc.perform(patch("/api/v1/tasks/{id}", taskId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));
    }

    @Test
    void updateTask_whenActorOutsideTargetTenant_returns403() throws Exception {
        when(taskService.updateTaskFromRequest(eq(taskId), any(), eq(actorId)))
                .thenThrow(new ForbiddenActionException("Actor cannot act on task " + taskId));

        mockMvc.perform(patch("/api/v1/tasks/{id}", taskId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void updateStatus_withValidStatus_returns200() throws Exception {
        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("DONE");
        when(taskService.updateTaskStatus(eq(taskId), any(), eq(actorId))).thenReturn(sampleTask());

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", taskId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId.toString()));
    }

    @Test
    void updateStatus_withInvalidStatusValue_returns400() throws Exception {
        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("NOT_A_REAL_STATUS");

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", taskId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void updateStatus_whenSnoozedWithoutSnoozedUntil_returns400() throws Exception {
        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("SNOOZED");
        when(taskService.updateTaskStatus(eq(taskId), any(), eq(actorId)))
                .thenThrow(new InvalidTaskStatusTransitionException("snoozedUntil is required when status is SNOOZED"));

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", taskId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TASK_STATUS_TRANSITION"));
    }

    @Test
    void updateStatus_whenNotFound_returns404() throws Exception {
        TaskStatusUpdateRequestDto request = new TaskStatusUpdateRequestDto();
        request.setStatus("DONE");
        when(taskService.updateTaskStatus(eq(taskId), any(), eq(actorId)))
                .thenThrow(new TaskNotFoundException(taskId));

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", taskId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"));
    }
}
