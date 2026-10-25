package com.rnd.chain.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PostgresGraphLockTest {

    private final JdbcTemplate jdbcTemplate =
            mock(JdbcTemplate.class);

    private final PostgresGraphLock graphLock =
            new PostgresGraphLock(jdbcTemplate);

    @AfterEach
    void cleanUpTransactionState() {
        TransactionSynchronizationManager
                .setActualTransactionActive(false);
    }

    @Test
    void sameTenant_returnsSameLockKey() {

        UUID tenantId =
                UUID.randomUUID();

        long first =
                PostgresGraphLock.lockKeyForTenant(
                        tenantId);

        long second =
                PostgresGraphLock.lockKeyForTenant(
                        tenantId);

        assertThat(first)
                .isEqualTo(second);
    }

    @Test
    void differentTenants_returnDifferentLockKeys() {

        UUID firstTenant =
                UUID.randomUUID();

        UUID secondTenant =
                UUID.randomUUID();

        long first =
                PostgresGraphLock.lockKeyForTenant(
                        firstTenant);

        long second =
                PostgresGraphLock.lockKeyForTenant(
                        secondTenant);

        assertThat(first)
                .isNotEqualTo(second);
    }

    @Test
    void nullTenant_returnsLegacyGraphLockKey() {

        long key =
                PostgresGraphLock.lockKeyForTenant(
                        null);

        assertThat(key)
                .isEqualTo(
                        PostgresGraphLock.GRAPH_LOCK_KEY);
    }

    @Test
    void noActiveTransaction_throwsIllegalStateException() {

        assertThatThrownBy(
                () -> graphLock.runExclusive(
                        UUID.randomUUID(),
                        () -> "action"))
                .isInstanceOf(
                        IllegalStateException.class)
                .hasMessageContaining(
                        "inside a transaction");
    }

    @Test
    void activeTransaction_acquiresLockAndRunsAction() {

        TransactionSynchronizationManager
                .setActualTransactionActive(true);

        UUID tenantId =
                UUID.randomUUID();

        String result =
                graphLock.runExclusive(
                        tenantId,
                        () -> "done");

        assertThat(result)
                .isEqualTo("done");

        verify(jdbcTemplate)
                .query(
                        anyString(),
                        any(RowCallbackHandler.class),
                        any(Object[].class));
    }
}