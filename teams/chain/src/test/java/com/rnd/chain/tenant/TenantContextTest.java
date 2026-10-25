package com.rnd.chain.tenant;

import com.rnd.chain.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantContextTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static TenantContext contextWithHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (value != null) {
            request.addHeader(TenantContext.HEADER, value);
        }
        return new TenantContext(request);
    }

    @Test
    void validHeader_isReturned() {
        assertEquals(TENANT, contextWithHeader(TENANT.toString()).requireTenantId());
    }

    @Test
    void validHeader_isCaseInsensitiveAndTrimmed() {
        assertEquals(TENANT, contextWithHeader("  " + TENANT.toString().toUpperCase() + " ").requireTenantId());
    }

    @Test
    void missingHeader_throwsInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> contextWithHeader(null).requireTenantId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-a-uuid", "1-1-1-1-1", "11111111-1111-1111-1111-11111111111"})
    void blankOrMalformedHeader_throwsInvalidRequest(String value) {
        assertThrows(InvalidRequestException.class, () -> contextWithHeader(value).requireTenantId());
    }

    @Test
    void headerIsReadOnlyOncePerRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantContext.HEADER, TENANT.toString());
        TenantContext context = new TenantContext(request);

        UUID first = context.requireTenantId();
        request.removeHeader(TenantContext.HEADER);

        assertEquals(first, context.requireTenantId());
    }
}
