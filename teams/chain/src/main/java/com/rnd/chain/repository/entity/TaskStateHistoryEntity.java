package com.rnd.chain.repository.entity;

import com.rnd.chain.dto.TaskStateEventType;
import com.rnd.chain.dto.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code task_state_history}. {@code sequence} is DB-generated (BIGSERIAL),
 * read-only from JPA's side - the ordering tiebreak and cursor key, never part of
 * {@link com.rnd.chain.dto.TaskStateHistoryEntry}. */
@Entity
@Table(name = "task_state_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskStateHistoryEntity {

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private TaskStateEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private TaskStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private TaskStatus toStatus;

    @Column(name = "blocking_count_before")
    private Integer blockingCountBefore;

    @Column(name = "blocking_count_after")
    private Integer blockingCountAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Long sequence;
}
