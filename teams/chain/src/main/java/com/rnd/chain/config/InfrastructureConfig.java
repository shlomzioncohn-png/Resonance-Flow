package com.rnd.chain.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**

 * Default beans for general-purpose infrastructure that every service needs.
 *
 * <p>Each bean is guarded with {@link ConditionalOnMissingBean} so a
 * consumer that registers its own is not overridden by this default.
 */
@Configuration
public class InfrastructureConfig {

    // A @SpringBootTest overriding this must use a differently-named @Primary bean, not
    // just @Bean Clock clock() - @ConditionalOnMissingBean here runs before a nested
    // @TestConfiguration is parsed, so a same-named bean collides instead of being skipped.
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}
