package com.rnd.inbox.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = "SELECT * FROM outbox " +
            "WHERE status = 'pending' AND next_retry_at <= :now " +
            "ORDER BY created_at " +
            "LIMIT :batchSize " +
            "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> claimPendingBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);


    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM outbox WHERE status = 'published' AND created_at < :cutoff",
            nativeQuery = true)
    int deletePublishedOlderThan(@Param("cutoff") Instant cutoff);


    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM outbox WHERE status = 'failed' AND created_at < :cutoff",
            nativeQuery = true)
    int deleteFailedOlderThan(@Param("cutoff") Instant cutoff);
}