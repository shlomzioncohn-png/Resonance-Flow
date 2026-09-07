package com.rnd.inbox.project;

import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class ProjectNotFoundException extends InboxException {

    public ProjectNotFoundException(UUID id) {
        super("Project " + id + " not found", "PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
