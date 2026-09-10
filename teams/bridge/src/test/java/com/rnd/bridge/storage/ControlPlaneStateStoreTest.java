package com.rnd.bridge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.KeyedLock;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlPlaneStateStoreTest {

    private static final String BUCKET = "bridge-raw";

    private final MinioClient minioClient = mock(MinioClient.class);
    private final Map<String, byte[]> fakeObjects = new ConcurrentHashMap<>();
    private ControlPlaneStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        // Backs the mocked MinioClient with a real in-memory map, so getObject/putObject behave
        // like a tiny fake MinIO instead of returning canned single responses - needed for the
        // concurrency test below, where update() is called many times against the same key.
        when(minioClient.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> {
            GetObjectArgs args = invocation.getArgument(0);
            byte[] content = fakeObjects.get(args.object());
            if (content == null) {
                ErrorResponse notFound = new ErrorResponse(
                        "NoSuchKey", "not found", BUCKET, args.object(), null, null, null);
                throw new ErrorResponseException(notFound, null, "req-1");
            }
            return new GetObjectResponse(new Headers.Builder().build(), BUCKET, "us-east-1",
                    args.object(), new ByteArrayInputStream(content));
        });
        when(minioClient.putObject(any(PutObjectArgs.class))).thenAnswer(invocation -> {
            PutObjectArgs args = invocation.getArgument(0);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            args.stream().transferTo(buffer);
            fakeObjects.put(args.object(), buffer.toByteArray());
            return null;
        });

        store = new ControlPlaneStateStore(minioClient, new ObjectMapper(), new KeyedLock(), BUCKET);
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
