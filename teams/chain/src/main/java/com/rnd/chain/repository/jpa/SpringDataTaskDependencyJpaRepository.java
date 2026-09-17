package com.rnd.chain.repository.jpa;

import com.rnd.chain.repository.entity.TaskDependencyEntity;
import com.rnd.chain.repository.entity.TaskDependencyId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataTaskDependencyJpaRepository extends JpaRepository<TaskDependencyEntity, TaskDependencyId> {

    List<TaskDependencyEntity> findByIdTaskId(UUID taskId);

    List<TaskDependencyEntity> findByIdDependsOnTaskId(UUID dependsOnTaskId);

    long deleteByIdTaskIdAndIdDependsOnTaskId(UUID taskId, UUID dependsOnTaskId);
}
