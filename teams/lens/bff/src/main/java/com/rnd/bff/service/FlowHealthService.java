package com.rnd.bff.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rnd.bff.client.ChainClient;
import com.rnd.bff.client.InboxClient;
import com.rnd.bff.dto.ChainFlowHealthFlagDTO;
import com.rnd.bff.dto.FlowHealthDTO;
import com.rnd.bff.dto.FlowHealthEntityDTO;
import com.rnd.bff.dto.FlowHealthFlagDTO;
import com.rnd.bff.dto.FlowHealthProjectDTO;
import com.rnd.bff.dto.FlowHealthTaskRefDTO;
import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.exception.InboxRejectedException;
import com.rnd.bff.exception.ServiceUnavailableException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The manager's flow-health page: Chain's flags (read-only, patterns over time) enriched with task
 * names and projects from Inbox. If Chain is down the whole call is a 503 - there is nothing to show
 * without the flags; Inbox failing is a 503 too, as in ManagerKpisService.
 */
@Service
public class FlowHealthService {

    static final List<String> PATTERNS = List.of("CHRONIC_BLOCKER", "RECURRING_CYCLE", "SLOW_CHAIN");

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String EDGE_SEPARATOR = ">";

    private final ChainClient chainClient;
    private final InboxClient inboxClient;

    public FlowHealthService(ChainClient chainClient, InboxClient inboxClient) {
        this.chainClient = chainClient;
        this.inboxClient = inboxClient;
    }

    /** @param userId the acting manager (X-User-Id); Inbox scopes the tasks to their tenant */
    public FlowHealthDTO getFlowHealth(String userId) {
        List<ChainFlowHealthFlagDTO> chainFlags = chainClient.getFlowHealthFlags();

        Map<String, TaskDTO> tasksById = chainFlags.isEmpty() ? Map.of() : loadTasks(userId);

        List<FlowHealthFlagDTO> flags = chainFlags.stream().map(flag -> toFlag(flag, tasksById)).toList();

        Map<String, Integer> counts = new LinkedHashMap<>();
        PATTERNS.forEach(pattern -> counts.put(pattern, 0));
        chainFlags.forEach(flag -> counts.merge(flag.getPattern(), 1, Integer::sum));

        return FlowHealthDTO.builder()
                .lastRunAt(lastRunAt(chainFlags))
                .counts(counts)
                .flags(flags)
                .taskNames(taskNames(chainFlags, tasksById))
                .projects(distinctProjects(flags))
                .build();
    }

    private Map<String, TaskDTO> loadTasks(String userId) {
        try {
            return inboxClient.getTenantTasks(userId).stream()
                    .filter(task -> task.getId() != null)
                    .collect(Collectors.toMap(TaskDTO::getId, Function.identity(), (first, second) -> first));
        } catch (InboxRejectedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Inbox", "Inbox is unavailable", ex);
        }
    }

    private FlowHealthFlagDTO toFlag(ChainFlowHealthFlagDTO flag, Map<String, TaskDTO> tasksById) {
        List<FlowHealthEntityDTO> entities = flag.getEntityIds().stream()
                .map(id -> toEntity(id, tasksById))
                .toList();
        return FlowHealthFlagDTO.builder()
                .flagId(flag.getFlagId())
                .rank(flag.getRank())
                .pattern(flag.getPattern())
                .entityType(flag.getEntityType())
                .entities(entities)
                .severity(flag.getSeverity())
                .score(flag.getScore())
                .explanation(flag.getExplanation())
                .evidence(flag.getEvidence())
                .projects(projectsOf(entities, tasksById))
                .build();
    }

    /** "a" is one task; "a>b" is an edge, kept dependent-first. */
    private FlowHealthEntityDTO toEntity(String entityId, Map<String, TaskDTO> tasksById) {
        List<FlowHealthTaskRefDTO> tasks = Arrays.stream(entityId.split(EDGE_SEPARATOR))
                .map(String::trim)
                .map(taskId -> new FlowHealthTaskRefDTO(taskId, titleOf(taskId, tasksById)))
                .toList();
        String label = tasks.stream().map(FlowHealthTaskRefDTO::getTitle).collect(Collectors.joining(" → "));
        return new FlowHealthEntityDTO(entityId, label, tasks);
    }

    private static String titleOf(String taskId, Map<String, TaskDTO> tasksById) {
        TaskDTO task = tasksById.get(taskId);
        return task == null || task.getTitle() == null || task.getTitle().isBlank() ? taskId : task.getTitle();
    }

    private static List<FlowHealthProjectDTO> projectsOf(List<FlowHealthEntityDTO> entities,
                                                         Map<String, TaskDTO> tasksById) {
        Map<String, FlowHealthProjectDTO> projects = new LinkedHashMap<>();
        for (FlowHealthEntityDTO entity : entities) {
            for (FlowHealthTaskRefDTO ref : entity.getTasks()) {
                TaskDTO task = tasksById.get(ref.getId());
                if (task != null && task.getProjectId() != null) {
                    projects.putIfAbsent(task.getProjectId(), new FlowHealthProjectDTO(
                            task.getProjectId(), task.getProject() == null ? task.getProjectId() : task.getProject()));
                }
            }
        }
        return new ArrayList<>(projects.values());
    }

    private static List<FlowHealthProjectDTO> distinctProjects(List<FlowHealthFlagDTO> flags) {
        Map<String, FlowHealthProjectDTO> projects = new LinkedHashMap<>();
        flags.forEach(flag -> flag.getProjects().forEach(project -> projects.putIfAbsent(project.getId(), project)));
        return new ArrayList<>(projects.values());
    }

    /** Titles for every task id the page may show: those in entityIds and any uuid inside the evidence. */
    private static Map<String, String> taskNames(List<ChainFlowHealthFlagDTO> flags, Map<String, TaskDTO> tasksById) {
        Set<String> ids = new LinkedHashSet<>();
        for (ChainFlowHealthFlagDTO flag : flags) {
            flag.getEntityIds().forEach(entityId ->
                    Arrays.stream(entityId.split(EDGE_SEPARATOR)).map(String::trim).forEach(ids::add));
            collectUuids(flag.getEvidence(), ids);
        }
        Map<String, String> names = new LinkedHashMap<>();
        ids.forEach(id -> names.put(id, titleOf(id, tasksById)));
        return names;
    }

    private static void collectUuids(JsonNode node, Set<String> into) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            var matcher = UUID_PATTERN.matcher(node.asText());
            while (matcher.find()) {
                into.add(matcher.group());
            }
        } else {
            node.forEach(child -> collectUuids(child, into));
        }
    }

    private static String lastRunAt(List<ChainFlowHealthFlagDTO> flags) {
        return flags.stream()
                .map(ChainFlowHealthFlagDTO::getRunAt)
                .filter(runAt -> runAt != null && !runAt.isBlank())
                .map(Instant::parse)
                .max(Instant::compareTo)
                .map(Instant::toString)
                .orElse(null);
    }
}
