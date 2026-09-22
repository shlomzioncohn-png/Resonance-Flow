package com.rnd.inbox.team;

import java.time.Instant;
import java.util.UUID;

public final class TeamMemberResponseDto {

    private final UUID userId;
    private final Instant joinedAt;
    private final Instant leftAt;

    public TeamMemberResponseDto(UUID userId, Instant joinedAt, Instant leftAt) {
        this.userId = userId;
        this.joinedAt = joinedAt;
        this.leftAt = leftAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getLeftAt() {
        return leftAt;
    }
}
