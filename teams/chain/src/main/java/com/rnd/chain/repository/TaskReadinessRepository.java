package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskReadinessSnapshot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface TaskReadinessRepository {

    Map<UUID, TaskReadinessSnapshot> findByIds(List<UUID> ids);
}