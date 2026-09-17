package com.rnd.chain.repository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** Real ACID transaction boundary, replacing {@link InMemoryTransactionRunner}'s single-lock,
 * no-rollback stand-in - exactly what {@link TransactionRunner}'s own Javadoc asks for. Backed by
 * the {@link PlatformTransactionManager} Spring Boot auto-configures once a real DataSource
 * exists - no manual bean needed. */
@Component
public class JpaTransactionRunner implements TransactionRunner {

    private final TransactionTemplate transactionTemplate;

    public JpaTransactionRunner(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T runInTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }
}
