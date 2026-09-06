package com.rnd.inbox.repository;

import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByTenant_IdAndSourceTypeAndSourceRef(
            UUID tenantId, SourceType sourceType, String sourceRef);
}
