package com.rnd.inbox.insight.repository;

import com.rnd.inbox.insight.model.Insight;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InsightRepository extends JpaRepository<Insight, UUID> {

    // Every read is scoped to the owning user - an insight is visible only to the employee it is about.
    Page<Insight> findByUserId(UUID userId, Pageable pageable);
}
