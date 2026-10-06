package com.rnd.inbox.team.member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMemberId> {

    Optional<TeamMember> findByTeam_IdAndUser_IdAndLeftAtIsNull(UUID teamId, UUID userId);

    List<TeamMember> findAllByTeam_IdAndLeftAtIsNull(UUID teamId);
}
