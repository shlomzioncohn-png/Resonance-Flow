package com.rnd.bridge.enrichment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean wiring for the enrichment package - {@link NoOpEnrichmentService} is
 * a plain, framework-free class wired explicitly here, the same convention
 * {@code CrmLaneConfig} uses for {@code TaskValidator}/{@code
 * DealToTaskMapper}. A real provider bean gets added here behind a
 * {@code @Profile} once one is chosen, same as {@code hubspot}/{@code kafka}
 * elsewhere in Bridge.
 */
@Configuration
class EnrichmentConfig {

    @Bean
    EnrichmentService enrichmentService() {
        return new NoOpEnrichmentService();
    }
}
