package com.rnd.inbox.team;

import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class TeamNotFoundException extends InboxException {

    public TeamNotFoundException(UUID id) {
        super("Team " + id + " not found", "TEAM_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
