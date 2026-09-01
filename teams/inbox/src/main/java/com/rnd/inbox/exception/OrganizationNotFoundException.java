package com.rnd.inbox.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class OrganizationNotFoundException extends InboxException {

    public OrganizationNotFoundException(UUID id) {
        super("Organization " + id + " not found", "ORGANIZATION_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
