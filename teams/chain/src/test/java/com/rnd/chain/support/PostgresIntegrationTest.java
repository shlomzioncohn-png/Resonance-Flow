package com.rnd.chain.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for tests that need the outbox subsystem's DataSource/JPA/Flyway
 * beans actually running - see application.yml's spring.autoconfigure.exclude
 * comment for why every other test does not get them by default.
 *
 * @ServiceConnection on the container wires spring.datasource.* to this
 * container automatically, overriding the placeholder values in
 * application.yml for the duration of the test.
 *
 * Turning JPA on also turns on the real ChainOutboxWorker/
 * ChainOutboxCleanupJob @Scheduled beans (@EnableScheduling is global on
 * ChainApplication) - their poll/cleanup intervals are pushed out to
 * effectively "never" here so they cannot race a test's own direct
 * repository calls and mutate rows out from under its assertions.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "chain.outbox.worker.poll-interval-ms=999999999",
        "chain.outbox.cleanup.interval-ms=999999999"
})
public abstract class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
}
