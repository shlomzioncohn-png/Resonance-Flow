package com.rnd.inbox.user.repository;
import com.rnd.inbox.team.member.TeamMember;
import com.rnd.inbox.user.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUsername(String username);

    // The list responses show each user's tenant. The relation is lazy, so without the entity graph
    // the tenant of every row would be loaded by a SELECT of its own; with it, it is joined in.
    @Override
    @EntityGraph(attributePaths = "tenant")
    Page<User> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "tenant")
    Page<User> findAllByTenantId(UUID tenantId, Pageable pageable);

    @Query(value = "SELECT DISTINCT tm.user FROM TeamMember tm "
            + "WHERE tm.team.teamLead.id = :teamLeadId AND tm.leftAt IS NULL",
            countQuery = "SELECT COUNT(DISTINCT tm.user) FROM TeamMember tm "
            + "WHERE tm.team.teamLead.id = :teamLeadId AND tm.leftAt IS NULL")
    Page<User> findAllByTeamLeadId(@Param("teamLeadId") UUID teamLeadId, Pageable pageable);

    @Query("SELECT COUNT(tm) > 0 FROM TeamMember tm "
            + "WHERE tm.user.id = :userId AND tm.team.teamLead.id = :teamLeadId AND tm.leftAt IS NULL")
    boolean existsInTeamLedBy(@Param("userId") UUID userId, @Param("teamLeadId") UUID teamLeadId);
}
