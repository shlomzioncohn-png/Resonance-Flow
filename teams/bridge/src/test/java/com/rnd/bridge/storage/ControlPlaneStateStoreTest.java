package com.rnd.bridge.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.KeyedLock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlPlaneStateStoreTest {

    private final Map<String, byte[]> fakeObjects = new ConcurrentHashMap<>();
    private ControlPlaneStateStore store;

    @BeforeEach
    void setUp() {
        store = new ControlPlaneStateStore(new InMemoryObjectStorageClient(fakeObjects), new ObjectMapper(), new KeyedLock());
    }

    @Test
    void updateStartsFromEmptyValueWhenKeyDoesNotExistYet() {
        String[] result = store.update("_dedup/MEETING/demo-tenant.json", String[].class, new String[0],
                current -> new String[] {"sha256:aaa"});

        assertThat(result).containsExactly("sha256:aaa");
        assertThat(fakeObjects).containsKey("_dedup/MEETING/demo-tenant.json");
    }

    @Test
    void updateLoadsThePreviouslySavedValueBeforeMutating() {
        store.update("key-1", String[].class, new String[0], current -> new String[] {"a"});

        String[] result = store.update("key-1", String[].class, new String[0],
                current -> java.util.stream.Stream.concat(java.util.Arrays.stream(current), java.util.stream.Stream.of("b"))
                        .toArray(String[]::new));

        assertThat(result).containsExactly("a", "b");
    }

    @Test
    void concurrentUpdatesOnTheSameKeyNeverLoseAWrite() throws InterruptedException {
        int callers = 20;
        int updatesPerCaller = 25;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch done = new CountDownLatch(callers);

        for (int i = 0; i < callers; i++) {
            int callerId = i;
            pool.submit(() -> {
                try {
                    for (int j = 0; j < updatesPerCaller; j++) {
                        String hash = "sha256:" + callerId + "-" + j;
                        store.update("_dedup/MEETING/demo-tenant.json", String[].class, new String[0],
                                current -> appendIfAbsent(current, hash));
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        String[] finalState = store.update("_dedup/MEETING/demo-tenant.json", String[].class, new String[0],
                current -> current);
        assertThat(finalState).hasSize(callers * updatesPerCaller);
    }

    private static String[] appendIfAbsent(String[] current, String hash) {
        for (String existing : current) {
            if (existing.equals(hash)) {
                return current;
            }
        }
        String[] updated = java.util.Arrays.copyOf(current, current.length + 1);
        updated[current.length] = hash;
        return updated;
    }
}
