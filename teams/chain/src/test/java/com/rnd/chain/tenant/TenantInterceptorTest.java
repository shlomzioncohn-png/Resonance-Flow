package com.rnd.chain.tenant;

import com.rnd.chain.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantInterceptorTest {

    private final TenantContext tenantContext = mock(TenantContext.class);
    private final TenantInterceptor interceptor = new TenantInterceptor(tenantContext);

    @Test
    void validTenant_letsTheRequestThrough() {
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
        verify(tenantContext).requireTenantId();
    }

    @Test
    void invalidTenant_propagatesTheBadRequest() {
        when(tenantContext.requireTenantId()).thenThrow(new InvalidRequestException("bad"));

        assertThrows(InvalidRequestException.class,
                () -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
    }
}
