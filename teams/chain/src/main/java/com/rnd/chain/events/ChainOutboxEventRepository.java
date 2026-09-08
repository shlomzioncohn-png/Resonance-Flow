package com.rnd.chain.events;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChainOutboxEventRepository extends JpaRepository<ChainOutboxEvent, UUID> {

    @Query(value = "SELECT * FROM chain_outbox " +
            "WHERE status = 'PENDING' AND next_retry_at <= :now " +
            "ORDER BY created_at " +
            "LIMIT :batchSize " +
            "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<ChainOutboxEvent> claimPendingBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM chain_outbox WHERE status = 'PUBLISHED' AND created_at < :cutoff",
            nativeQuery = true)
    int deletePublishedOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM chain_outbox WHERE status = 'FAILED' AND created_at < :cutoff",
            nativeQuery = true)
    int deleteFailedOlderThan(@Param("cutoff") Instant cutoff);
}
