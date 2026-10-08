package com.rnd.chain.event;

import com.rnd.chain.dto.Task;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.service.SchedulingUpdateService;
import com.rnd.chain.testsupport.TaskFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SchedulingPublishListenerTest {

    @Test
    void onCommit_publishesTheTasksCurrentState() {
        UUID taskId = UUID.randomUUID();
        Task task = TaskFixtures.newTask(taskId);
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(List.of(task));
        List<TaskSchedulingUpdatedEvent> published = new ArrayList<>();
        SchedulingUpdateService schedulingUpdateService = new SchedulingUpdateService(published::add);
        SchedulingPublishListener listener = new SchedulingPublishListener(taskRepository, schedulingUpdateService);

        listener.onCommit(taskId);

        assertThat(published).hasSize(1);
        assertThat(published.get(0).getTaskId()).isEqualTo(taskId);
    }

    @Test
    void onCommit_taskNoLongerExists_doesNotThrow() {
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(List.of());
        SchedulingUpdateService schedulingUpdateService = new SchedulingUpdateService(event -> {
            throw new AssertionError("must not publish for a task that no longer exists");
        });
        SchedulingPublishListener listener = new SchedulingPublishListener(taskRepository, schedulingUpdateService);

        assertThatCode(() -> listener.onCommit(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void onCommit_succeedsOnFirstTry_publishesOnceWithNoBackoff() {
        UUID taskId = UUID.randomUUID();
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(List.of(TaskFixtures.newTask(taskId)));
        AtomicInteger attempts = new AtomicInteger();
        SchedulingUpdateService schedulingUpdateService = new SchedulingUpdateService(event -> attempts.incrementAndGet());
        RecordingSleeper sleeper = new RecordingSleeper();
        SchedulingPublishListener listener =
                new SchedulingPublishListener(taskRepository, schedulingUpdateService, sleeper);

        listener.onCommit(taskId);

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(sleeper.delaysMillis).isEmpty();
    }

    @Test
    void onCommit_failsTwiceThenSucceeds_retriesWithExponentialBackoffThenPublishes() {
        UUID taskId = UUID.randomUUID();
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(List.of(TaskFixtures.newTask(taskId)));
        AtomicInteger attempts = new AtomicInteger();
        SchedulingUpdateService schedulingUpdateService = new SchedulingUpdateService(event -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("transient publish failure");
            }
        });
        RecordingSleeper sleeper = new RecordingSleeper();
        SchedulingPublishListener listener =
                new SchedulingPublishListener(taskRepository, schedulingUpdateService, sleeper);

        listener.onCommit(taskId);

        assertThat(attempts.get()).isEqualTo(3);
        // Locked decision: 3 attempts, exponential backoff starting at 200ms -> 200ms, then 400ms
        // between the 3 attempts (2 waits for 3 tries).
        assertThat(sleeper.delaysMillis).containsExactly(200L, 400L);
    }

    @Test
    void onCommit_persistentFailure_exhaustsRetriesAndDoesNotThrow() {
        UUID taskId = UUID.randomUUID();
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(List.of(TaskFixtures.newTask(taskId)));
        AtomicInteger attempts = new AtomicInteger();
        SchedulingUpdateService schedulingUpdateService = new SchedulingUpdateService(event -> {
            attempts.incrementAndGet();
            throw new RuntimeException("permanently broken");
        });
        RecordingSleeper sleeper = new RecordingSleeper();
        SchedulingPublishListener listener =
                new SchedulingPublishListener(taskRepository, schedulingUpdateService, sleeper);

        // Locked decision: a persistent failure is logged, not thrown - the message is lost, but
        // it must not take the caller (the committing transaction's thread) down with it.
        assertThatCode(() -> listener.onCommit(taskId)).doesNotThrowAnyException();
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(sleeper.delaysMillis).containsExactly(200L, 400L);
    }

    private static class RecordingSleeper implements SchedulingPublishListener.Sleeper {
        private final List<Long> delaysMillis = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            delaysMillis.add(millis);
        }
    }
}
