package com.rnd.bridge.intake.parser.drive;

import static com.rnd.bridge.intake.parser.FixtureIoSupport.FIXTURE_ROOT;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.readJson;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.textOrNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.model.DriveFileState;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.DriveFileStateReconciler;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.RawSourceType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Parses drive-sample.json. Moved as-is from the old FixtureSourceLoader.loadDrive() — see
 * plan.md for why this reads real Google Drive API v3 File resource field names.
 * Fixture files have no real Google headRevisionId, so node.toString() (the file's own JSON
 * text) is used as the headRevisionId surrogate passed to DriveFileStateReconciler - same
 * fixture/real parity convention as FixtureCalendarSourceReader's etag surrogate (see its
 * javadoc): it changes if and only if the fixture content for that file actually changes.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.drive", name = "mode", havingValue = "fixture", matchIfMissing = true)
public class FixtureDriveSourceReader implements DriveSourceReader {

    private final ObjectMapper objectMapper;
    private final DriveFileStateReconciler fileStateReconciler;

    public FixtureDriveSourceReader(ObjectMapper objectMapper, DriveFileStateReconciler fileStateReconciler) {
        this.objectMapper = objectMapper;
        this.fileStateReconciler = fileStateReconciler;
    }

    @Override
    public DriveReadResult read(ReadCursor cursor) {
        List<SourceRecord> records = new ArrayList<>();
        for (JsonNode node : readJson(objectMapper, FIXTURE_ROOT + "drive-sample.json")) {
            String tenantId = node.get("tenantId").asText();
            String fileId = node.get("id").asText();
            if (isTrashed(node)) {
                records.add(toTrashedSourceRecord(tenantId, fileId, node));
                continue;
            }
            String owner = node.get("owners").get(0).get("emailAddress").asText();
            DriveFileState state = fileStateReconciler.resolve(tenantId, fileId, node.toString());
            records.add(new SourceRecord(
                    RawSourceType.DRIVE,
                    tenantId,
                    fileId,
                    state.correlationId(),
                    Instant.parse(node.get("modifiedTime").asText()),
                    owner,
                    List.of(owner),
                    textOrNull(node.get("name")),
                    textOrNull(node.get("description")),
                    node.toString().getBytes(StandardCharsets.UTF_8),
                    textOrNull(node.get("mimeType")),
                    null,
                    0,
                    0,
                    List.of(),
                    state.revision()));
        }
        // Fixtures never support incremental sync - no token to advance to.
        return new DriveReadResult(records, null);
    }

    private static boolean isTrashed(JsonNode node) {
        JsonNode trashed = node.get("trashed");
        return trashed != null && trashed.asBoolean(false);
    }

    /** Fixture analog of RealDriveSourceReader.toTrashedSourceRecord - see its javadoc for why
     * only id/headRevisionId-surrogate-derived fields are used, nothing name/description/owners. */
    private SourceRecord toTrashedSourceRecord(String tenantId, String fileId, JsonNode node) {
        DriveFileState state = fileStateReconciler.resolve(tenantId, fileId, node.toString());
        return new SourceRecord(
                RawSourceType.DRIVE, tenantId, fileId, state.correlationId(), Instant.now(), null, List.of(),
                null, null, node.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(),
                state.revision(), ChangeType.DELETE_REVIEW);
    }
}
