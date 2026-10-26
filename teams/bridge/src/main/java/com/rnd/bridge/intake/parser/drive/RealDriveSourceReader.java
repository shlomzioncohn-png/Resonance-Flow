package com.rnd.bridge.intake.parser.drive;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.config.BridgeTenantId;
import com.rnd.bridge.intake.config.DriveImportProperties;
import com.rnd.bridge.intake.google.GoogleCredentialsProvider;
import com.rnd.bridge.intake.model.DriveFileState;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.DriveFileStateReconciler;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.RawSourceType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads real Google Drive files. Two modes, chosen by whether a startPageToken is already
 * known (cursor.token()):
 * - First run (cursor.token() == null): a full files.list() baseline scan, same as before this
 *   class supported incremental sync at all - every file goes through fileStateReconciler as a
 *   first-seen file (revision 0). A changes.list startPageToken freshly minted via
 *   changes().getStartPageToken() marks "from now on" only - confirmed against Google's own
 *   docs that it never retroactively returns pre-existing files - so it is minted alongside
 *   this baseline scan, not instead of it, and returned as this read's nextToken.
 * - Every run after (cursor.token() != null): changes.list(startPageToken) only - a true
 *   delta-only read, no files.list() call at all.
 * A native Google file (Doc/Sheet/Slide - mimeType starting "application/vnd.google-apps.")
 * has no bytes of its own and must be exported to a concrete format via files.export; every
 * other file is downloaded as-is via files.get(...).executeMediaAsInputStream() - except a
 * trashed file (see toTrashedSourceRecord), which is never fetched at all.
 * correlationId/revision come from DriveFileStateReconciler.resolve, keyed on fileId/
 * headRevisionId - the direct Drive analog of RealCalendarSourceReader's eventId/etag
 * reconciliation (see that class's javadoc for the shared reasoning).
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.drive", name = "mode", havingValue = "real")
public class RealDriveSourceReader implements DriveSourceReader {

    private static final Logger log = LoggerFactory.getLogger(RealDriveSourceReader.class);

    /** Default export target for native Google files - cheapest, most directly usable by
     * downstream text processing (Mind); trades away preserved formatting. */
    private static final String EXPORT_MIME_TYPE = "text/plain";
    private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
    private static final String SHORTCUT_MIME_TYPE = "application/vnd.google-apps.shortcut";
    private static final String LIST_FIELDS = "nextPageToken, files(id, name, description, mimeType, "
            + "modifiedTime, owners, size, headRevisionId, version)";
    private static final String CHANGES_FIELDS = "nextPageToken, newStartPageToken, "
            + "changes(fileId, removed, "
            + "file(id, name, description, mimeType, modifiedTime, owners, size, headRevisionId, version, "
            + "trashed))";

    private final GoogleCredentialsProvider credentialsProvider;
    private final DriveImportProperties properties;
    private final BridgeTenantId tenantId;
    private final DriveFileStateReconciler fileStateReconciler;
    private Drive client;

    @Autowired
    public RealDriveSourceReader(
            GoogleCredentialsProvider credentialsProvider, DriveImportProperties properties,
            BridgeTenantId tenantId, DriveFileStateReconciler fileStateReconciler) {
        this.credentialsProvider = credentialsProvider;
        this.properties = properties;
        this.tenantId = tenantId;
        this.fileStateReconciler = fileStateReconciler;
    }

    /** Test-only seam: lets tests inject a mocked Drive client instead of the real one client()
     * would otherwise lazily build from live Google credentials. */
    RealDriveSourceReader(
            GoogleCredentialsProvider credentialsProvider, DriveImportProperties properties,
            BridgeTenantId tenantId, DriveFileStateReconciler fileStateReconciler, Drive client) {
        this(credentialsProvider, properties, tenantId, fileStateReconciler);
        this.client = client;
    }

    @Override
    public DriveReadResult read(ReadCursor cursor) {
        String token = cursor == null ? null : cursor.token();
        log.info("Drive read starting: storedToken={}", token != null ? "present" : "absent");
        try {
            if (token == null) {
                DriveReadResult result = readBaseline();
                logResult("baseline", result);
                return result;
            }
            DriveReadResult result = readChanges(token);
            logResult("changes", result);
            return result;
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 410 && token != null) {
                // Expired startPageToken - the only way back is a full baseline resync.
                log.info("Drive changes read got 410 on stored startPageToken - falling back to baseline resync");
                log.warn("Drive start page token expired (410) - falling back to baseline resync");
                try {
                    DriveReadResult result = readBaseline();
                    logResult("baseline (post-410 fallback)", result);
                    return result;
                } catch (IOException retryFailure) {
                    throw new UncheckedIOException("Failed to list Drive files", retryFailure);
                }
            }
            throw new UncheckedIOException("Failed to list Drive files", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list Drive files", e);
        }
    }

    private void logResult(String path, DriveReadResult result) {
        log.info("Drive read via {} path complete: records={}, nextToken={}",
                path, result.records().size(), result.nextToken() != null ? "present" : "null");
    }

    private DriveReadResult readBaseline() throws IOException {
        Drive drive = client();
        List<SourceRecord> records = new ArrayList<>();
        String pageToken = null;
        do {
            FileList page = listPage(drive, pageToken);
            if (page.getFiles() != null) {
                for (File file : page.getFiles()) {
                    SourceRecord record = toSourceRecord(drive, file);
                    if (record != null) {
                        records.add(record);
                    }
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        String nextToken = drive.changes().getStartPageToken().execute().getStartPageToken();
        return new DriveReadResult(records, nextToken);
    }

    private FileList listPage(Drive drive, String pageToken) throws IOException {
        Drive.Files.List request = drive.files().list()
                .setFields(LIST_FIELDS)
                .setPageToken(pageToken);
        String rootFolderId = properties.rootFolderId();
        if (rootFolderId != null && !rootFolderId.isBlank()) {
            request.setQ("'" + rootFolderId + "' in parents");
        }
        return request.execute();
    }

    private DriveReadResult readChanges(String startPageToken) throws IOException {
        Drive drive = client();
        List<SourceRecord> records = new ArrayList<>();
        String pageToken = startPageToken;
        // newStartPageToken only ever appears on the last page of a sync (same contract as
        // Calendar's nextSyncToken) - so it's only ever read once, right before the loop ends.
        String nextToken = null;
        do {
            ChangeList page = drive.changes().list(pageToken).setFields(CHANGES_FIELDS).execute();
            if (page.getChanges() != null) {
                for (Change change : page.getChanges()) {
                    if (Boolean.TRUE.equals(change.getRemoved()) || change.getFile() == null) {
                        // removed=true (deletion vs. lost access, ambiguous) is explicitly out
                        // of scope for this pass - see ROADMAP.md §3.3.
                        continue;
                    }
                    SourceRecord record = toSourceRecord(drive, change.getFile());
                    if (record != null) {
                        records.add(record);
                    }
                }
            }
            pageToken = page.getNextPageToken();
            if (pageToken == null) {
                nextToken = page.getNewStartPageToken();
            }
        } while (pageToken != null);
        return new DriveReadResult(records, nextToken);
    }

    private SourceRecord toSourceRecord(Drive drive, File file) {
        if (Boolean.TRUE.equals(file.getTrashed())) {
            return toTrashedSourceRecord(file);
        }
        if (isNonExportableNativeType(file.getMimeType())) {
            log.warn("Skipping Drive file - mimeType is not exportable: fileId={}, mimeType={}",
                    file.getId(), file.getMimeType());
            return null;
        }
        byte[] content;
        try {
            content = fetchContent(drive, file);
        } catch (DriveFileNotExportableException e) {
            log.warn("Skipping Drive file - export rejected as not exportable: fileId={}, mimeType={}",
                    file.getId(), file.getMimeType());
            return null;
        }
        // SourceRecord.mimeType must describe content, not the original file - a native Google
        // file's content bytes are the EXPORT_MIME_TYPE export, never file.getMimeType() itself.
        String contentMimeType = DriveFileStateReconciler.isNativeGoogleFile(file.getMimeType())
                ? EXPORT_MIME_TYPE
                : file.getMimeType();
        String owner = file.getOwners() == null || file.getOwners().isEmpty()
                ? null
                : file.getOwners().get(0).getEmailAddress();
        Instant occurredAt = Instant.ofEpochMilli(file.getModifiedTime().getValue());
        DriveFileState state = fileStateReconciler.resolve(
                tenantId.value(), file.getId(), file.getMimeType(), file.getHeadRevisionId(), file.getVersion());
        return new SourceRecord(
                RawSourceType.DRIVE, tenantId.value(), file.getId(), state.correlationId(),
                occurredAt, owner, owner == null ? List.of() : List.of(owner), file.getName(),
                file.getDescription(), content, contentMimeType, null, 0, 0, List.of(), state.revision())
                .withSourceRef(file.getId());
    }

    /**
     * A trashed file's SourceRecord uses only fields Google guarantees on a change record with
     * removed=false (file.trashed=true) - name/description/owners/size are not assumed present,
     * and content is never fetched (may 403/404, and there is no reason to re-download content
     * being deleted) - mirrors RealCalendarSourceReader.toCancelledSourceRecord. correlationId/
     * revision still come from DriveFileStateReconciler.resolve, exactly like a normal edit -
     * only changeType marks this as a deletion.
     */
    private SourceRecord toTrashedSourceRecord(File file) {
        DriveFileState state = fileStateReconciler.resolve(
                tenantId.value(), file.getId(), file.getMimeType(), file.getHeadRevisionId(), file.getVersion());
        Instant occurredAt = file.getModifiedTime() != null
                ? Instant.ofEpochMilli(file.getModifiedTime().getValue())
                : Instant.now();
        return new SourceRecord(
                RawSourceType.DRIVE, tenantId.value(), file.getId(), state.correlationId(),
                occurredAt, null, List.of(), null, null,
                file.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), state.revision(),
                ChangeType.DELETE_REVIEW).withSourceRef(file.getId());
    }

    private byte[] fetchContent(Drive drive, File file) {
        try {
            if (DriveFileStateReconciler.isNativeGoogleFile(file.getMimeType())) {
                return drainToBytes(drive.files().export(file.getId(), EXPORT_MIME_TYPE).executeMediaAsInputStream());
            }
            return drainToBytes(drive.files().get(file.getId()).executeMediaAsInputStream());
        } catch (GoogleJsonResponseException e) {
            if (isFileNotExportable(e)) {
                throw new DriveFileNotExportableException(e);
            }
            throw new UncheckedIOException("Failed to fetch Drive file content for " + file.getId(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fetch Drive file content for " + file.getId(), e);
        }
    }

    /** Folders and shortcuts share the application/vnd.google-apps.* prefix with real native
     * Google docs (isNativeGoogleFile treats the whole prefix as exportable) but files.export
     * rejects them with a 403 fileNotExportable - "Export only supports Docs Editors files" -
     * known up front from the mimeType alone, so these are skipped before ever calling export. */
    private static boolean isNonExportableNativeType(String mimeType) {
        return FOLDER_MIME_TYPE.equals(mimeType) || SHORTCUT_MIME_TYPE.equals(mimeType);
    }

    /** Safety net for any other native Google type files.export rejects the same way (403,
     * reason fileNotExportable) that isNonExportableNativeType doesn't already know about by
     * mimeType alone. */
    private static boolean isFileNotExportable(GoogleJsonResponseException e) {
        return e.getStatusCode() == 403
                && e.getDetails() != null
                && e.getDetails().getErrors() != null
                && e.getDetails().getErrors().stream()
                        .anyMatch(error -> "fileNotExportable".equals(error.getReason()));
    }

    private static final class DriveFileNotExportableException extends RuntimeException {
        DriveFileNotExportableException(GoogleJsonResponseException cause) {
            super(cause);
        }
    }

    private static byte[] drainToBytes(java.io.InputStream in) throws IOException {
        try (java.io.InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            input.transferTo(out);
            return out.toByteArray();
        }
    }

    private synchronized Drive client() {
        if (client == null) {
            // Plain NetHttpTransport, not GoogleNetHttpTransport.newTrustedTransport() - see
            // GoogleCredentialsProvider for why.
            HttpTransport transport = new NetHttpTransport();
            var credential = credentialsProvider.credentials(List.of(DriveScopes.DRIVE_READONLY));
            client = new Drive.Builder(transport, GsonFactory.getDefaultInstance(), credential)
                    .setApplicationName("bridge-intake")
                    .build();
        }
        return client;
    }
}
