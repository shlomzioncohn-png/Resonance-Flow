package com.rnd.chain.testsupport;

import com.rnd.chain.repository.InMemoryTaskStateHistoryRepository;
import com.rnd.chain.service.TaskStateHistoryRecorder;

import java.time.Clock;

/** Builds a {@link TaskStateHistoryRecorder} for tests that construct services by hand. */
public final class TaskStateHistoryFixtures {

    private TaskStateHistoryFixtures() {
    }

    /** A recorder writing into a throwaway in-memory repository - for tests that don't assert on history. */
    public static TaskStateHistoryRecorder newRecorder() {
        return new TaskStateHistoryRecorder(new InMemoryTaskStateHistoryRepository(), Clock.systemUTC());
    }
}
