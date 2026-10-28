package com.rnd.chain.config;

import com.rnd.chain.tenant.TenantInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    // Paths under /tasks that may be called without X-Tenant-Id; empty = every path requires it.
    private static final String[] TENANT_EXEMPT_PATHS = {};

    private final TenantInterceptor tenantInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/tasks/**")
                .excludePathPatterns(TENANT_EXEMPT_PATHS);
    }
}
