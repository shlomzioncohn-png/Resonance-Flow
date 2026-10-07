package com.rnd.inbox.team.member;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class DuplicateTeamMembershipException extends InboxException {

    public DuplicateTeamMembershipException(UUID teamId, UUID userId) {
        super("User " + userId + " is already an active member of team " + teamId,
                "DUPLICATE_TEAM_MEMBERSHIP", HttpStatus.CONFLICT);
    }
}
