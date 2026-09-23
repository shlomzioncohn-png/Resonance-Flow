package com.rnd.bff.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client for Mind — scores/ranks candidate tasks for a user and available
 * time window (see docs/lens-architecture-design.md). Mind's API isn't live
 * yet, so this returns a fixed confidence score for every task id.
 */
@Service
public class MindClient {

    @Value("${mind.service.url:http://mind-service:8000}")
    private String mindServiceUrl;

    // TODO: replace with POST {mindServiceUrl}/v1/score once Mind exposes it
    public Map<String, Integer> getScores(String userId, List<String> taskIds, Integer availableTimeMinutes) {
        return taskIds.stream().collect(Collectors.toMap(id -> id, id -> 85));
    }
}
