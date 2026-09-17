package com.rnd.chain.repository.jpa;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.repository.entity.DependencyHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataDependencyHistoryJpaRepository extends JpaRepository<DependencyHistoryEntity, UUID> {

    Optional<DependencyHistoryEntity> findBySourceAndSourceRef(DependencyChangeSource source, String sourceRef);

    Page<DependencyHistoryEntity> findByTaskId(UUID taskId, Pageable pageable);
}
