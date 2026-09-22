package com.rnd.inbox.tenant;

import com.rnd.inbox.shared.exception.InboxException;

import org.springframework.http.HttpStatus;

public class DuplicateSlugException extends InboxException {

    public DuplicateSlugException(String slug) {
        super("Slug " + slug + " already exists", "DUPLICATE_SLUG", HttpStatus.CONFLICT);
    }
}
