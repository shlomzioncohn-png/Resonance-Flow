package com.rnd.inbox.repository;

import com.rnd.inbox.model.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of pending events for this worker to process.
     * <p>
     * Must be called from within an active transaction - the row lock taken by
     * FOR UPDATE only lasts as long as that transaction is open. SKIP LOCKED lets
     * other INBOX instances running the same query at the same time skip rows
     * already claimed here, instead of blocking and waiting for them.
     */
    @Query(value = "SELECT * FROM outbox " +
            "WHERE status = 'pending' AND next_retry_at <= :now " +
            "ORDER BY created_at " +
            "LIMIT :batchSize " +
            "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> claimPendingBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);
}