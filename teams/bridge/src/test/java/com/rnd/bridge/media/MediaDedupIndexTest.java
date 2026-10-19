package com.rnd.bridge.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.storage.ControlPlaneStateStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MediaDedupIndexTest {

    private final ControlPlaneStateStore stateStore = mock(ControlPlaneStateStore.class);
    private final Map<String, String[]> fakeStorage = new ConcurrentHashMap<>();
    private final MediaDedupIndex dedupIndex = new MediaDedupIndex(stateStore);

    @BeforeEach
    void backStateStoreWithAnInMemoryMap() {
        // MediaDedupIndex's own correctness (does it find/append the right hash) is what these
        // tests target - ControlPlaneStateStore's atomicity is already covered by its own
        // concurrency test, so a simple (non-concurrent) fake stands in for it here.
        when(stateStore.update(any(), eq(String[].class), any(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String[] emptyValue = invocation.getArgument(2);
            UnaryOperator<String[]> mutator = invocation.getArgument(3);
            String[] current = fakeStorage.getOrDefault(key, emptyValue);
            String[] updated = mutator.apply(current);
            fakeStorage.put(key, updated);
            return updated;
        });
    }

    @Test
    void firstSightingOfAHashIsNotADuplicateAndGetsRecorded() {
        boolean isDuplicate = dedupIndex.checkAndRecord(RawSourceType.ZOOM, "demo-tenant", "sha256:aaa");

        assertThat(isDuplicate).isFalse();
        assertThat(fakeStorage.get("_dedup/ZOOM/demo-tenant.json")).containsExactly("sha256:aaa");
    }

    @Test
    void secondSightingOfTheSameHashIsADuplicateAndIsNotRecordedTwice() {
        dedupIndex.checkAndRecord(RawSourceType.ZOOM, "demo-tenant", "sha256:aaa");

        boolean isDuplicate = dedupIndex.checkAndRecord(RawSourceType.ZOOM, "demo-tenant", "sha256:aaa");

        assertThat(isDuplicate).isTrue();
        assertThat(fakeStorage.get("_dedup/ZOOM/demo-tenant.json")).containsExactly("sha256:aaa");
    }

    @Test
    void differentSourceTypesAndTenantsAreTrackedSeparately() {
        dedupIndex.checkAndRecord(RawSourceType.ZOOM, "demo-tenant", "sha256:aaa");

        boolean isDuplicateForOtherSourceType =
                dedupIndex.checkAndRecord(RawSourceType.SCANNED_DOC, "demo-tenant", "sha256:aaa");
        boolean isDuplicateForOtherTenant =
                dedupIndex.checkAndRecord(RawSourceType.ZOOM, "other-tenant", "sha256:aaa");

        assertThat(isDuplicateForOtherSourceType).isFalse();
        assertThat(isDuplicateForOtherTenant).isFalse();
    }

    @Test
    void forgetRollsBackAClaimSoARetryIsNotTreatedAsADuplicateForever() {
        dedupIndex.checkAndRecord(RawSourceType.ZOOM, "demo-tenant", "sha256:aaa");

        dedupIndex.forget(RawSourceType.ZOOM, "demo-tenant", "sha256:aaa");

        boolean isDuplicateAfterForget =
                dedupIndex.checkAndRecord(RawSourceType.ZOOM, "demo-tenant", "sha256:aaa");
        assertThat(isDuplicateAfterForget).isFalse();
    }

    @Test
    void forgettingAHashThatWasNeverRecordedLeavesTheIndexEmpty() {
        dedupIndex.forget(RawSourceType.ZOOM, "demo-tenant", "sha256:never-seen");

        assertThat(fakeStorage.get("_dedup/ZOOM/demo-tenant.json")).isEmpty();
    }
}
