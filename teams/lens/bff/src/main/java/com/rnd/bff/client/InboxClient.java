package com.rnd.bff.client;

import com.rnd.bff.dto.ConstraintDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskListResponseDTO;
import com.rnd.bff.exception.InboxRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client for Inbox — owns canonical task data (see
 * docs/lens-architecture-design.md).
 *
 * Two modes, switched by inbox.mock-enabled (INBOX_MOCK_ENABLED):
 * - true (default): an in-memory mock task set, no network calls — so the
 *   team can keep working without running Inbox locally.
 * - false: real HTTP calls to Inbox's Task API (/api/v1/tasks, currently on
 *   Inbox's inbox-team branch) at inbox.service.url. See
 *   lens/frontend/docs/api-integration-status.md for how to run Inbox locally.
 *
 * Inbox identifies the caller by an X-Actor-Id header holding a user UUID
 * from its own users table. The Gateway still sends a mock userId
 * ("emp_001"), so inbox.dev-actor-id can override it with a seeded Inbox user
 * for local testing.
 *
 * In mock mode, the mock is seeded as InboxTaskRecord — every field named and
 * typed after the real `tasks` table (shared/database/migrations) — and
 * only translated to TaskDTO (the BFF's frontend-facing contract) at the
 * method boundary. TaskDTO carries both the real schema fields and is
 * designed to surface the full 8-value status enum directly, with no
 * simplification or mapping. This ensures the API is a transparent window
 * into the canonical data model (see docs/employee-workspace-api-contract.md
 * §5.0 for the status model change).
 *
 * The mock ignores userId (single mock tenant) — every method still takes it
 * so callers don't change when Inbox becomes real and starts scoping by user.
 *
 * Backed by a mutable LinkedHashMap (not List.of(...) per call) so
 * updateStatus() actually persists for the lifetime of this instance —
 * getTask() after updateStatus() reflects the change, which a fresh literal
 * per call could never do.
 */
@Service
public class InboxClient {

    private static final Logger logger = LoggerFactory.getLogger(InboxClient.class);

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final String TASKS_PATH = "/api/v1/tasks";
    private static final int TENANT_TASKS_PAGE_SIZE = 200;
    /** A safety stop for getTenantTasks: 50 pages of 200 = 10,000 tasks. */
    private static final int TENANT_TASKS_MAX_PAGES = 50;

    private final String inboxServiceUrl;
    private final boolean mockEnabled;
    private final String devActorId;
    private final RestTemplate restTemplate;

    // Single mock tenant/user/project ids, reused across every seeded task -
    // this mock models one tenant with one employee (Alex Morgan), same as
    // before this class existed.
    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OWNER_USER_ID = "66666666-6666-6666-6666-666666666666";
    private static final String PROJECT_PLATFORM_SERVICE = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT_CUSTOMER_SUCCESS = "33333333-3333-3333-3333-333333333333";
    private static final String PROJECT_AUTH_SERVICE = "44444444-4444-4444-4444-444444444444";
    private static final String PROJECT_BILLING = "55555555-5555-5555-5555-555555555555";

    private final Map<String, InboxTaskRecord> tasksById = new LinkedHashMap<>();

    @Autowired
    public InboxClient(
            @Value("${inbox.service.url:http://inbox-service:8080}") String inboxServiceUrl,
            @Value("${inbox.mock-enabled:true}") boolean mockEnabled,
            @Value("${inbox.dev-actor-id:}") String devActorId,
            @Value("${inbox.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${inbox.read-timeout-ms:10000}") long readTimeoutMs) {
        this.inboxServiceUrl = inboxServiceUrl;
        this.mockEnabled = mockEnabled;
        this.devActorId = devActorId;

        // JDK HttpClient (not RestTemplate's default HttpURLConnection), because
        // HttpURLConnection can't send PATCH - which updateStatus() needs.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build());
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restTemplate = new RestTemplate(requestFactory);

        if (mockEnabled) {
            seedMockTasks();
        } else {
            logger.info("InboxClient using real Inbox at {} (dev actor: {})",
                    inboxServiceUrl, devActorId.isBlank() ? "none" : devActorId);
        }
    }

    /** Mock mode, for unit tests - no Inbox needed. */
    public InboxClient() {
        this("http://inbox-service:8080", true, "", 2000, 10000);
    }

    private void seedMockTasks() {
        // task_001: NEW status, high priority, available
        seed(InboxTaskRecord.builder()
                .id("task_001")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_PLATFORM_SERVICE)
                .title("Review API changes")
                .description("Review the latest API changes and update integration tests.")
                .sourceType("github_pr")
                .sourceRef("PR #4821")
                .tier("tactical")
                .roleContext("developer")
                .urgency(4)
                .effortEstimateMinutes(35)
                .effortEstimateConfidence(0.85)
                .deadline(LocalDateTime.of(2026, 8, 30, 17, 0))
                .status("new")
                .isAvailable(true)
                .priorityScore(8.0)
                .extractionProvenance("ml_inferred")
                .confidence(0.92)
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.90)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 5, 10, 9, 15))
                .updatedAt(LocalDateTime.of(2026, 5, 10, 9, 15))
                .owner("Alex Morgan")
                .project("Platform Service")
                .build());
        // task_002: IN_REVIEW status, snoozed/unavailable
        seed(InboxTaskRecord.builder()
                .id("task_002")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_CUSTOMER_SUCCESS)
                .title("Update customer proposal")
                .description("Incorporate legal's redlines into the Q3 proposal draft.")
                .sourceType("crm_deal")
                .sourceRef("DEAL-2291")
                .tier("strategic")
                .roleContext("developer")
                .urgency(5)
                .effortEstimateMinutes(60)
                .effortEstimateConfidence(0.70)
                .deadline(LocalDateTime.of(2026, 9, 6, 17, 0))
                .status("in_review")
                .isAvailable(false)
                .priorityScore(8.0)
                .extractionProvenance("etl")
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.80)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 20, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 8, 25, 14, 0))
                .owner("Alex Morgan")
                .project("Customer Success")
                .constraints(List.of(mockConstraint("c_002_1", "APPROVAL_NEEDED", "PENDING",
                        "Legal must sign off on the redlines", "2026-08-20T10:05:00")))
                .build());
        // task_003: APPROVED status, snoozed/unavailable with deadline
        seed(InboxTaskRecord.builder()
                .id("task_003")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_CUSTOMER_SUCCESS)
                .title("Prepare contract draft")
                .description("Draft the renewal contract for the Acme account.")
                .sourceType("crm_deal")
                .sourceRef("DEAL-2305")
                .tier("strategic")
                .roleContext("developer")
                .urgency(3)
                .effortEstimateMinutes(90)
                .effortEstimateConfidence(0.60)
                .deadline(LocalDateTime.of(2026, 9, 7, 17, 0))
                .status("approved")
                .isAvailable(false)
                .priorityScore(5.0)
                .snoozedUntil(LocalDateTime.of(2026, 9, 11, 9, 0))
                .extractionProvenance("etl")
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.75)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 22, 11, 0))
                .updatedAt(LocalDateTime.of(2026, 8, 27, 16, 0))
                .owner("Alex Morgan")
                .project("Customer Success")
                .constraints(List.of(
                        mockConstraint("c_003_1", "BLOCKED_BY_INFO", "PENDING",
                                "Waiting for the customer's renewal terms", "2026-08-22T11:10:00"),
                        mockConstraint("c_003_2", "APPROVAL_NEEDED", "SATISFIED",
                                "Pricing approved by finance", "2026-08-23T09:00:00")))
                .build());
        // task_004: IN_PROGRESS status, available
        seed(InboxTaskRecord.builder()
                .id("task_004")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_PLATFORM_SERVICE)
                .title("Update documentation")
                .description("Bring the API docs in line with the latest endpoint changes.")
                .sourceType("manual")
                .tier("operational")
                .roleContext("developer")
                .urgency(1)
                .effortEstimateMinutes(45)
                .deadline(LocalDateTime.of(2026, 9, 12, 17, 0))
                .status("in_progress")
                .isAvailable(true)
                .priorityScore(2.0)
                .extractionProvenance("manual")
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 9, 1, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 5, 9, 0))
                .owner("Alex Morgan")
                .project("Platform Service")
                .build());
        // task_005: DONE status (completed), available
        seed(InboxTaskRecord.builder()
                .id("task_005")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_AUTH_SERVICE)
                .title("Fix login bug")
                .description("Users on Safari intermittently can't complete SSO login.")
                .sourceType("github_issue")
                .sourceRef("ISSUE-512")
                .tier("operational")
                .roleContext("developer")
                .urgency(5)
                .effortEstimateMinutes(20)
                .effortEstimateConfidence(0.80)
                .deadline(LocalDateTime.of(2026, 9, 6, 12, 0))
                .status("done")
                .isAvailable(true)
                .priorityScore(8.0)
                .extractionProvenance("ml_inferred")
                .confidence(0.88)
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.85)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 9, 3, 8, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 3, 8, 0))
                .completedAt(LocalDateTime.of(2026, 9, 5, 14, 30))
                .owner("Alex Morgan")
                .project("Auth Service")
                .build());
        // task_006: SNOOZED status (explicitly paused), unavailable
        seed(InboxTaskRecord.builder()
                .id("task_006")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_BILLING)
                .title("Write unit tests for billing")
                .description("Cover the new proration logic added last sprint.")
                .sourceType("manual")
                .tier("operational")
                .roleContext("developer")
                .urgency(3)
                .effortEstimateMinutes(50)
                .deadline(LocalDateTime.of(2026, 9, 15, 17, 0))
                .status("snoozed")
                .isAvailable(false)
                .priorityScore(3.0)
                .snoozedUntil(LocalDateTime.of(2026, 9, 18, 9, 0))
                .extractionProvenance("manual")
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 28, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 2, 10, 0))
                .owner("Alex Morgan")
                .project("Billing")
                .build());
        // task_007: REJECTED status (not approved), unavailable
        seed(InboxTaskRecord.builder()
                .id("task_007")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_PLATFORM_SERVICE)
                .title("Implement new caching layer")
                .description("Add Redis caching to improve API response times.")
                .sourceType("github_issue")
                .sourceRef("ISSUE-445")
                .tier("tactical")
                .roleContext("developer")
                .urgency(2)
                .effortEstimateMinutes(120)
                .effortEstimateConfidence(0.75)
                .deadline(LocalDateTime.of(2026, 10, 1, 17, 0))
                .status("rejected")
                .isAvailable(false)
                .priorityScore(4.0)
                .extractionProvenance("ml_inferred")
                .confidence(0.80)
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.70)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 15, 14, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 8, 11, 30))
                .owner("Alex Morgan")
                .project("Platform Service")
                .constraints(List.of(mockConstraint("c_007_1", "DATE_WINDOW", "WAIVED",
                        "Out of scope for this quarter", "2026-08-15T14:30:00")))
                .build());
        // task_008: CANCELLED status (cancelled before completion), unavailable
        seed(InboxTaskRecord.builder()
                .id("task_008")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_CUSTOMER_SUCCESS)
                .title("Schedule Q4 planning meeting")
                .description("Coordinate with team leads for Q4 planning session.")
                .sourceType("manual")
                .tier("operational")
                .roleContext("manager")
                .urgency(1)
                .effortEstimateMinutes(30)
                .deadline(LocalDateTime.of(2026, 9, 30, 17, 0))
                .status("cancelled")
                .isAvailable(false)
                .priorityScore(1.0)
                .extractionProvenance("manual")
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 10, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 9, 16, 0))
                .owner("Alex Morgan")
                .project("Customer Success")
                .build());
    }

    private void seed(InboxTaskRecord task) {
        tasksById.put(task.getId(), task);
    }

    private static ConstraintDTO mockConstraint(String id, String type, String status, String note, String createdAt) {
        return ConstraintDTO.builder()
                .constraintId(id)
                .type(type)
                .status(status)
                .reason(Map.of("note", note))
                .createdAt(createdAt)
                .build();
    }

    /**
     * Candidate tasks for the recommendation flow. In real mode this is only
     * Inbox's first page (100 tasks) - enough for the recommendation demo, but
     * not "every task the user has".
     */
    public List<TaskDTO> getTasksForUser(String userId) {
        if (!mockEnabled) {
            return listTasks(userId, null, null, 0, 100).getItems();
        }
        List<TaskDTO> result = new ArrayList<>();
        for (InboxTaskRecord record : tasksById.values()) {
            result.add(toTaskDTO(record));
        }
        return result;
    }

    /**
     * Every task in the acting user's tenant, all pages - for organization-wide
     * counts (the Manager Dashboard's KPIs). No userId filter: Inbox scopes the
     * list to the actor's own tenant, so the tenant never comes from the request.
     */
    public List<TaskDTO> getTenantTasks(String userId) {
        if (mockEnabled) {
            return getTasksForUser(null);
        }
        List<TaskDTO> result = new ArrayList<>();
        for (int page = 0; page < TENANT_TASKS_MAX_PAGES; page++) {
            URI uri = UriComponentsBuilder.fromHttpUrl(inboxServiceUrl)
                    .path(TASKS_PATH)
                    .queryParam("page", page)
                    .queryParam("size", TENANT_TASKS_PAGE_SIZE)
                    .encode()
                    .build()
                    .toUri();
            InboxPageResponse body;
            try {
                body = restTemplate.exchange(
                        uri, HttpMethod.GET, new HttpEntity<>(actorHeaders(userId)), InboxPageResponse.class)
                        .getBody();
            } catch (HttpClientErrorException ex) {
                throw rejected(ex);
            }
            if (body == null) {
                throw new IllegalStateException("Inbox returned an empty task page");
            }
            if (body.getContent() != null) {
                body.getContent().stream().map(InboxClient::toTaskDTO).forEach(result::add);
            }
            if (page + 1 >= body.getTotalPages()) {
                return result;
            }
        }
        logger.warn("Tenant has more than {} tasks - KPIs count only the first {}",
                TENANT_TASKS_MAX_PAGES * TENANT_TASKS_PAGE_SIZE, result.size());
        return result;
    }

    /**
     * One page of the My Tasks list - only the user's own tasks. Inbox's userId
     * filter returns tasks the user owns, is assigned to, or that are assigned
     * to one of their teams with no one personally on them yet.
     *
     * @param status one of the 8 statuses in UPPERCASE, or null for all
     * @param search free text (Inbox matches title or description), or null
     */
    public TaskListResponseDTO listTasks(String userId, String status, String search, int page, int size) {
        if (mockEnabled) {
            return listMockTasks(status, search, page, size);
        }
        URI uri = UriComponentsBuilder.fromHttpUrl(inboxServiceUrl)
                .path(TASKS_PATH)
                .queryParam("userId", actorId(userId))
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParamIfPresent("search", Optional.ofNullable(search).filter(s -> !s.isBlank()))
                .queryParam("page", page)
                .queryParam("size", size)
                .encode()
                .build()
                .toUri();
        InboxPageResponse body;
        try {
            body = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(actorHeaders(userId)), InboxPageResponse.class)
                    .getBody();
        } catch (HttpClientErrorException ex) {
            throw rejected(ex);
        }
        if (body == null) {
            throw new IllegalStateException("Inbox returned an empty task page");
        }
        List<TaskDTO> items = body.getContent() == null
                ? List.of()
                : body.getContent().stream().map(InboxClient::toTaskDTO).toList();
        return TaskListResponseDTO.builder()
                .items(items)
                .total((int) body.getTotalElements())
                .page(body.getPage())
                .size(body.getSize())
                .build();
    }

    /** @return the task, or null if Inbox doesn't have it (404, or an id that isn't a UUID) */
    public TaskDTO getTaskById(String userId, String taskId) {
        if (mockEnabled) {
            InboxTaskRecord record = tasksById.get(taskId);
            return record == null ? null : toTaskDTO(record);
        }
        if (!isUuid(taskId)) {
            return null;
        }
        try {
            InboxTaskResponse task = restTemplate.exchange(
                    taskUri(taskId), HttpMethod.GET, new HttpEntity<>(actorHeaders(userId)),
                    InboxTaskResponse.class)
                    .getBody();
            return task == null ? null : toTaskDTO(task);
        } catch (HttpClientErrorException.NotFound ex) {
            return null;
        } catch (HttpClientErrorException ex) {
            throw rejected(ex);
        }
    }

    /**
     * @param snoozedUntil when a SNOOZED task wakes up; null for any other status
     * @return the updated task, or null if Inbox doesn't have it
     */
    public TaskDTO updateStatus(String userId, String taskId, String newStatus, Instant snoozedUntil) {
        if (mockEnabled) {
            InboxTaskRecord record = tasksById.get(taskId);
            if (record == null) {
                return null;
            }
            // newStatus is one of the 8 real status values (TaskStatusUpdateDTO's
            // @Pattern already validates this before this is called).
            // Store it as-is, converting only to lowercase for consistency with Inbox schema.
            record.setStatus(newStatus.toLowerCase());
            record.setSnoozedUntil(snoozedUntil == null ? null : toLocalDateTime(snoozedUntil));
            return toTaskDTO(record);
        }
        if (!isUuid(taskId)) {
            return null;
        }
        HttpHeaders headers = actorHeaders(userId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            InboxTaskResponse task = restTemplate.exchange(
                    URI.create(taskUri(taskId) + "/status"), HttpMethod.PATCH,
                    new HttpEntity<>(statusBody(newStatus, snoozedUntil), headers),
                    InboxTaskResponse.class)
                    .getBody();
            return task == null ? null : toTaskDTO(task);
        } catch (HttpClientErrorException.NotFound ex) {
            return null;
        } catch (HttpClientErrorException ex) {
            throw rejected(ex);
        }
    }

    /** True when running on the in-memory mock (no Inbox calls). */
    public boolean isMockEnabled() {
        return mockEnabled;
    }

    /**
     * Inbox's own pick of the best tasks for the user's available time
     * (GET /api/v1/tasks/selection). The mock does a simple stand-in, see
     * selectMockTasks.
     */
    public InboxSelectionResponse selectTasks(String userId, int availableMinutes) {
        if (mockEnabled) {
            return selectMockTasks(availableMinutes);
        }
        URI uri = UriComponentsBuilder.fromHttpUrl(inboxServiceUrl)
                .path(TASKS_PATH + "/selection")
                .queryParam("availableMinutes", availableMinutes)
                .queryParam("userId", actorId(userId))
                .encode()
                .build()
                .toUri();
        try {
            InboxSelectionResponse body = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(actorHeaders(userId)), InboxSelectionResponse.class)
                    .getBody();
            if (body == null) {
                throw new IllegalStateException("Inbox returned an empty task selection");
            }
            return body;
        } catch (HttpClientErrorException ex) {
            throw rejected(ex);
        }
    }

    // -------------------------------------------------------------------------
    // Real-mode helpers
    // -------------------------------------------------------------------------

    /**
     * Inbox answered 4xx (other than 404). Keeps Inbox's own message when its
     * error body has one ({ "message": ... }), so a 400 says *why*.
     * Only 401/403 are kept as-is; any other 4xx is reported as 400.
     */
    private static InboxRejectedException rejected(HttpClientErrorException ex) {
        int status = ex.getStatusCode().value();
        String message = ex.getStatusText();
        try {
            Map<?, ?> body = ex.getResponseBodyAs(Map.class);
            if (body != null && body.get("message") instanceof String inboxMessage) {
                message = inboxMessage;
            }
        } catch (RuntimeException ignored) {
            // Not JSON - keep the status text
        }
        logger.warn("Inbox rejected request: {} {}", status, message);
        return new InboxRejectedException(status == 401 || status == 403 ? status : 400, message);
    }

    private URI taskUri(String taskId) {
        return UriComponentsBuilder.fromHttpUrl(inboxServiceUrl)
                .path(TASKS_PATH + "/{id}")
                .buildAndExpand(taskId)
                .encode()
                .toUri();
    }

    /**
     * Inbox task ids are UUIDs. Inbox answers a non-UUID id with 500 (not
     * 404), so it's checked here and treated as "not found" without calling Inbox.
     */
    private static boolean isUuid(String id) {
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** The Inbox user we act as - the dev override if set, otherwise the userId from the Gateway. */
    private String actorId(String userId) {
        return devActorId.isBlank() ? userId : devActorId;
    }

    /** Inbox's PATCH .../status body - snoozed_until only when there is one. */
    private static Map<String, Object> statusBody(String newStatus, Instant snoozedUntil) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", newStatus.toUpperCase());
        if (snoozedUntil != null) {
            body.put("snoozed_until", snoozedUntil.toString());
        }
        return body;
    }

    /** Inbox's X-Actor-Id header. */
    private HttpHeaders actorHeaders(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ACTOR_HEADER, actorId(userId));
        return headers;
    }

    // -------------------------------------------------------------------------
    // Mock-mode helpers
    // -------------------------------------------------------------------------

    /**
     * A simple stand-in for Inbox's selection, so the endpoint works without
     * Inbox: workable, unblocked tasks with an effort estimate, most important
     * first, added while they still fit. If none fits, the shortest one is
     * picked anyway (usedFallback), like Inbox does. Not Inbox's real algorithm.
     */
    private InboxSelectionResponse selectMockTasks(int availableMinutes) {
        List<InboxTaskRecord> candidates = tasksById.values().stream()
                .filter(t -> List.of("new", "in_review", "approved", "in_progress").contains(t.getStatus()))
                .filter(t -> !Boolean.FALSE.equals(t.getIsAvailable()))
                .filter(t -> t.getEffortEstimateMinutes() != null)
                .sorted((a, b) -> Double.compare(
                        b.getPriorityScore() == null ? 0 : b.getPriorityScore(),
                        a.getPriorityScore() == null ? 0 : a.getPriorityScore()))
                .toList();

        List<InboxSelectionResponse.SelectedTask> selected = new ArrayList<>();
        int used = 0;
        for (InboxTaskRecord task : candidates) {
            if (used + task.getEffortEstimateMinutes() <= availableMinutes) {
                selected.add(toSelectedTask(task));
                used += task.getEffortEstimateMinutes();
            }
        }
        if (!selected.isEmpty() || candidates.isEmpty()) {
            return new InboxSelectionResponse(selected, used, 0, availableMinutes - used, false, null);
        }
        InboxTaskRecord shortest = candidates.stream()
                .min((a, b) -> Integer.compare(a.getEffortEstimateMinutes(), b.getEffortEstimateMinutes()))
                .orElseThrow();
        int effort = shortest.getEffortEstimateMinutes();
        return new InboxSelectionResponse(List.of(toSelectedTask(shortest)), effort, 0, 0, true,
                "No task fit the original " + availableMinutes + " minutes; window was automatically expanded to "
                        + effort + " minutes");
    }

    private static InboxSelectionResponse.SelectedTask toSelectedTask(InboxTaskRecord task) {
        return new InboxSelectionResponse.SelectedTask(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getEffortEstimateMinutes(),
                DisplayDates.date(task.getDeadline()),
                task.getPriorityScore() == null ? 0 : task.getPriorityScore() / 10);
    }

    private TaskListResponseDTO listMockTasks(String status, String search, int page, int size) {
        List<TaskDTO> matching = getTasksForUser(null).stream()
                .filter(t -> status == null || status.equals(t.getStatus()))
                .filter(t -> search == null || search.isBlank()
                        || (t.getTitle() != null && t.getTitle().toLowerCase().contains(search.toLowerCase())))
                .toList();
        int from = Math.min(page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        return TaskListResponseDTO.builder()
                .items(matching.subList(from, to))
                .total(matching.size())
                .page(page)
                .size(size)
                .build();
    }

    // -------------------------------------------------------------------------
    // InboxTaskRecord (real schema shape) <-> TaskDTO (frontend contract + real fields)
    // -------------------------------------------------------------------------

    private static TaskDTO toTaskDTO(InboxTaskRecord record) {
        return TaskDTO.builder()
                .id(record.getId())
                .title(record.getTitle())
                .description(record.getDescription())
                // Status is no longer simplified: pass through all 8 real values directly.
                // See docs/employee-workspace-api-contract.md §5.0 for the status model change.
                .status(toUpperCase(record.getStatus()))
                .priority(toFrontendPriority(record.getPriorityScore()))
                .effortEstimate(record.getEffortEstimateMinutes())
                .dueDate(DisplayDates.date(record.getDeadline()))
                .owner(record.getOwner())
                .project(record.getProject())
                .dependencies(record.getDependencies())
                .constraints(record.getConstraints())
                .openConstraintCount(record.getConstraints() == null
                        ? null : ConstraintDTO.countOpen(record.getConstraints()))
                // Real schema fields, surfaced as-is alongside their derived
                // equivalents above - see TaskDTO's javadoc.
                .tenantId(record.getTenantId())
                .projectId(record.getProjectId())
                .sourceType(record.getSourceType())
                .sourceRef(record.getSourceRef())
                .tier(record.getTier())
                .roleContext(record.getRoleContext())
                .urgency(record.getUrgency())
                .effortEstimateConfidence(record.getEffortEstimateConfidence())
                .inboxStatus(toUpperCase(record.getStatus()))
                .isAvailable(record.getIsAvailable())
                .priorityScore(record.getPriorityScore())
                .snoozedUntil(DisplayDates.dateTime(record.getSnoozedUntil()))
                .extractionProvenance(record.getExtractionProvenance())
                .confidence(record.getConfidence())
                .suggestedOwnerUserId(record.getSuggestedOwnerUserId())
                .suggestedOwnerConfidence(record.getSuggestedOwnerConfidence())
                .metadata(record.getMetadata())
                .createdBy(record.getCreatedBy())
                .createdAt(DisplayDates.dateTime(record.getCreatedAt()))
                .updatedAt(DisplayDates.dateTime(record.getUpdatedAt()))
                .completedAt(DisplayDates.dateTime(record.getCompletedAt()))
                .build();
    }

    /**
     * Inbox's JSON -> TaskDTO. Enum values are lowercased to match the DB
     * vocabulary the mock (and so the frontend) already uses - except status,
     * which TaskDTO keeps UPPERCASE. owner/project come from
     * Inbox's display names (PR #591). Dates are already formatted by Inbox.
     */
    private static TaskDTO toTaskDTO(InboxTaskResponse task) {
        return TaskDTO.builder()
                .id(task.getId())
                .title(task.getTitle())
                .description(task.getDescription())
                .status(task.getStatus())
                .priority(toFrontendPriority(task.getPriorityScore()))
                .effortEstimate(task.getEffortEstimateMinutes())
                .dueDate(task.getDeadline())
                .owner(task.getOwnerDisplayName())
                .project(task.getProjectName())
                .tenantId(task.getTenant() == null ? null : task.getTenant().getId())
                .projectId(task.getProjectId())
                .sourceType(toLowerCase(task.getSourceType()))
                .sourceRef(task.getSourceRef())
                .tier(toLowerCase(task.getTier()))
                .roleContext(toLowerCase(task.getRoleContext()))
                .urgency(task.getUrgency())
                .effortEstimateConfidence(task.getEffortEstimateConfidence())
                .inboxStatus(task.getStatus())
                .isAvailable(task.getAvailable())
                .priorityScore(task.getPriorityScore())
                .snoozedUntil(task.getSnoozedUntil())
                .extractionProvenance(toLowerCase(task.getExtractionProvenance()))
                .confidence(task.getConfidence())
                .suggestedOwnerUserId(task.getSuggestedOwnerUserId())
                .suggestedOwnerConfidence(task.getSuggestedOwnerConfidence())
                .metadata(task.getMetadata())
                .createdBy(task.getCreatedById())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .completedAt(task.getCompletedAt())
                .build();
    }

    /** For storing an Instant on a mock record (zone-less, UTC). */
    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String toLowerCase(String value) {
        return value == null ? null : value.toLowerCase();
    }

    /**
     * Real `priority_score` is a REAL column, 1-10 (see
     * chk_tasks_priority_score_range). TaskDTO's priority is a 3-bucket
     * LOW/MEDIUM/HIGH string. Thresholds below are an unconfirmed judgment
     * call, not a product decision — flag before relying on the exact cutoffs.
     */
    private static String toFrontendPriority(Double priorityScore) {
        if (priorityScore == null) {
            // No score -> no priority. Showing MEDIUM here would be made up.
            return null;
        }
        if (priorityScore >= 7) {
            return "HIGH";
        }
        if (priorityScore >= 4) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /** Utility to convert status to uppercase (no transformation of values). */
    private static String toUpperCase(String status) {
        return status == null ? null : status.toUpperCase();
    }
}
