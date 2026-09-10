package com.rnd.bff.client;

import com.rnd.bff.dto.TaskDependenciesDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client for Chain — owns dependency/blocker data and feasibility (see
 * docs/lens-architecture-design.md). Chain's API isn't live yet, so this
 * returns a fixed mock (no dependencies, no constraints) for every task id.
 */
@Service
public class ChainClient {

    @Value("${chain.service.url:http://chain-service:8081}")
    private String chainServiceUrl;

    // TODO: replace with GET {chainServiceUrl}/api/v1/dependencies?taskIds=... once Chain exposes it
    public Map<String, TaskDependenciesDTO> getFeasibility(String userId, List<String> taskIds) {
        return taskIds.stream()
                .collect(Collectors.toMap(id -> id, id -> new TaskDependenciesDTO(List.of(), List.of())));
    }
}
