package com.rnd.chain.repository.jpa;

import com.rnd.chain.repository.entity.TaskDependencyEntity;
import com.rnd.chain.repository.entity.TaskDependencyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpringDataTaskDependencyJpaRepository extends JpaRepository<TaskDependencyEntity, TaskDependencyId> {

    List<TaskDependencyEntity> findByIdTaskId(UUID taskId);

    List<TaskDependencyEntity> findByIdDependsOnTaskId(UUID dependsOnTaskId);

    long deleteByIdTaskIdAndIdDependsOnTaskId(UUID taskId, UUID dependsOnTaskId);

    /** Native insert; no-ops on a PK collision instead of throwing. Returns 1 if inserted, 0 if
     * it already existed. */
    @Modifying
    @Query(value = """
        INSERT INTO task_dependency (task_id, depends_on_task_id)
        VALUES (:taskId, :dependsOnTaskId)
        ON CONFLICT (task_id, depends_on_task_id) DO NOTHING
        """, nativeQuery = true)
    int insertIgnoringConflict(@Param("taskId") UUID taskId, @Param("dependsOnTaskId") UUID dependsOnTaskId);
}
