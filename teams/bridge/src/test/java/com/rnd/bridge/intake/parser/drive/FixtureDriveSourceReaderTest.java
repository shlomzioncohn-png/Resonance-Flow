package com.rnd.bridge.intake.parser.drive;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.lane.DriveFileStateStore;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.DriveFileStateReconciler;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Covers the fixture/real parity fix added alongside RealDriveSourceReader's edit/delete
 * support: FixtureDriveSourceReader now goes through DriveFileStateReconciler (it used to mint
 * a fresh UUID/revision 0 every read, like the real reader did before this work), and maps a
 * `trashed: true` fixture node to DELETE_REVIEW - the fixture-side mirror of
 * RealDriveSourceReader.toTrashedSourceRecord. Uses a real in-memory-backed reconciler (same
 * pattern as DriveFileStateReconcilerTest) since the property under test - identity/revision
 * stability across repeated reads - is exactly what a mock would hide.
 */
class FixtureDriveSourceReaderTest {

    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final DriveFileStateReconciler reconciler = new DriveFileStateReconciler(
            new DriveFileStateStore(
                    new InMemoryObjectStorageClient(backingStore), ControlPlaneStateStores.inMemory(backingStore),
                    objectMapper));
    private final FixtureDriveSourceReader reader = new FixtureDriveSourceReader(objectMapper, reconciler);

    @Test
    void trashedFixtureFileBecomesDeleteReviewWithNoContentFields() {
        List<SourceRecord> records = reader.read(ReadCursor.full()).records();

        SourceRecord trashed = records.stream()
                .filter(r -> "del-7a8b9c".equals(r.naturalId()))
                .findFirst().orElseThrow();
        assertThat(trashed.changeType()).isEqualTo(ChangeType.DELETE_REVIEW);
        assertThat(trashed.sender()).isNull();
        assertThat(trashed.subject()).isNull();
        assertThat(trashed.participants()).isEmpty();
    }

    @Test
    void repeatedReadsOfTheSameUnchangedFileReuseCorrelationIdAndRevision() {
        SourceRecord first = reader.read(ReadCursor.full()).records().stream()
                .filter(r -> "1a2b3c4d5e".equals(r.naturalId()))
                .findFirst().orElseThrow();

        SourceRecord second = reader.read(ReadCursor.full()).records().stream()
                .filter(r -> "1a2b3c4d5e".equals(r.naturalId()))
                .findFirst().orElseThrow();

        // Same fixture content every read -> same headRevisionId surrogate -> reconciler treats
        // it as a retry, not a new revision (see DriveFileStateReconciler.resolve).
        assertThat(second.correlationId()).isEqualTo(first.correlationId());
        assertThat(second.revision()).isEqualTo(first.revision());
    }

    @Test
    void liveAndTrashedFilesGetIndependentCorrelationIds() {
        List<SourceRecord> records = reader.read(ReadCursor.full()).records();

        SourceRecord live = records.stream().filter(r -> "1a2b3c4d5e".equals(r.naturalId())).findFirst().orElseThrow();
        SourceRecord trashed = records.stream().filter(r -> "del-7a8b9c".equals(r.naturalId())).findFirst().orElseThrow();

        assertThat(live.correlationId()).isNotEqualTo(trashed.correlationId());
    }

    @Test
    void readNeverSupportsIncrementalSyncSoNextTokenIsAlwaysNull() {
        DriveReadResult result = reader.read(ReadCursor.full());

        assertThat(result.nextToken()).isNull();
    }
}
