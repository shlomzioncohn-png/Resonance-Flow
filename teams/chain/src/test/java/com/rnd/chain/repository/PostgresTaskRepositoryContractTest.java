package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Proves {@link PostgresTaskRepository} satisfies the same contract as the in-memory
 * implementation, against a real PostgreSQL instance. Each test runs in its own transaction,
 * rolled back afterwards, so tests never see each other's data. */
@Tag("postgres")
@SpringBootTest
@Transactional
class PostgresTaskRepositoryContractTest extends TaskRepositoryContractTest {

    @Autowired
    private PostgresTaskRepository postgresTaskRepository;

    @Override
    protected TaskRepository createRepository(List<Task> seed) {
        seed.forEach(postgresTaskRepository::save);
        return postgresTaskRepository;
    }
}
