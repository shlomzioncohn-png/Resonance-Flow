package com.rnd.inbox.team.repository;
import com.rnd.inbox.team.model.Team;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    // The list responses show each team's tenant. The relation is lazy, so without the entity graph
    // the tenant of every row would be loaded by a SELECT of its own; with it, it is joined in.
    @Override
    @EntityGraph(attributePaths = "tenant")
    Page<Team> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "tenant")
    Page<Team> findAllByTenant_Id(UUID tenantId, Pageable pageable);
}
