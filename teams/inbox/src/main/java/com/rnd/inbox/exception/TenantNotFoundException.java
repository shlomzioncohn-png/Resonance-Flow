package com.rnd.inbox.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class TenantNotFoundException extends InboxException {

    public TenantNotFoundException(UUID id) {
        super("Tenant " + id + " not found", "TENANT_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
