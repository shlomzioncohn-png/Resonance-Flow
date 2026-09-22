package com.rnd.inbox.team;

import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class TeamMembershipNotFoundException extends InboxException {

    public TeamMembershipNotFoundException(UUID teamId, UUID userId) {
        super("User " + userId + " is not an active member of team " + teamId,
                "TEAM_MEMBERSHIP_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
