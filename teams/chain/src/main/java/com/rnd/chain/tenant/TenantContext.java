package com.rnd.chain.tenant;

import com.rnd.chain.exception.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;
import java.util.regex.Pattern;

/** The caller's tenant, read once per request from the {@code X-Tenant-Id} header. */
@Component
@RequestScope
@RequiredArgsConstructor
public class TenantContext {

    public static final String HEADER = "X-Tenant-Id";

    // UUID.fromString alone accepts malformed input such as "1-1-1-1-1".
    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final HttpServletRequest request;

    private UUID tenantId;

    /** @throws InvalidRequestException (400) if the header is missing or not a valid UUID */
    public UUID requireTenantId() {
        if (tenantId == null) {
            tenantId = parse(request.getHeader(HEADER));
        }
        return tenantId;
    }

    private static UUID parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("Missing required header: " + HEADER);
        }
        String value = raw.trim();
        if (!UUID_FORMAT.matcher(value).matches()) {
            throw new InvalidRequestException(HEADER + " must be a valid UUID");
        }
        return UUID.fromString(value);
    }
}
