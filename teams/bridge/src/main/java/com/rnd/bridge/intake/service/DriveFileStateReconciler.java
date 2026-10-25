package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.lane.DriveFileStateStore;
import com.rnd.bridge.intake.model.DriveFileState;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves the correlationId/revision Bridge should use for one Google Drive file, given its
 * stable fileId and Google's own change signal for the version currently being read - the Drive
 * analog of {@link CalendarEventStateReconciler} (see its javadoc for the full reasoning this
 * mirrors, substituting fileId for eventId and headRevisionId/version for etag).
 * Which signal is authoritative depends on the file type: Google never populates headRevisionId
 * for a native Google Doc/Sheet/Slide (mimeType starting {@link #GOOGLE_APPS_MIME_PREFIX} -
 * verified against Google's own Drive API docs), so headRevisionId alone can't detect an edit to
 * one - both the stored and incoming value are always null, which this class's own
 * headRevisionIdUnchanged treats as "unchanged". version (a separate, Google-maintained,
 * monotonically increasing counter bumped on any content or metadata change - present for every
 * file type, unlike headRevisionId) is used instead for native files; ordinary binary-content
 * files keep using headRevisionId, unchanged from before.
 * resolve()'s three outcomes, matching DriveFileState's javadoc:
 * - first-seen fileId: mint a new correlationId, start at revision 0, unconfirmed.
 * - signal unchanged from last time (headRevisionId for binary, version for native - see
 *   isNativeGoogleFile): a retry - reuse the same correlationId/revision/published state, but
 *   still refresh the stored headRevisionId/version to the incoming values (both are cheap to
 *   keep current and this mirrors what the "changed" branch already does).
 * - signal changed: a genuinely newer Drive version (or a trashed file - see
 *   RealDriveSourceReader - the reconciler has no notion of "trashed", only the signal changing;
 *   deciding changeType is entirely RealDriveSourceReader's job) - same correlationId,
 *   revision + 1, published reset to false.
 */
@Service
public class DriveFileStateReconciler {

    private static final String GOOGLE_APPS_MIME_PREFIX = "application/vnd.google-apps.";

    private final DriveFileStateStore store;

    public DriveFileStateReconciler(DriveFileStateStore store) {
        this.store = store;
    }

    public static boolean isNativeGoogleFile(String mimeType) {
        return mimeType != null && mimeType.startsWith(GOOGLE_APPS_MIME_PREFIX);
    }

    public DriveFileState resolve(String tenantId, String fileId, String mimeType, String headRevisionId,
            Long version) {
        boolean isNative = isNativeGoogleFile(mimeType);
        return store.update(tenantId, fileId, current -> {
            if (current.fileId() == null) {
                return new DriveFileState(
                        fileId, tenantId, UUID.randomUUID().toString(), headRevisionId, version, 0, false);
            }
            boolean unchanged = isNative
                    ? versionUnchanged(current, version)
                    : headRevisionIdUnchanged(current, headRevisionId);
            if (unchanged) {
                return current;
            }
            return new DriveFileState(
                    fileId, tenantId, current.correlationId(), headRevisionId, version, current.revision() + 1,
                    false);
        });
    }

    private static boolean headRevisionIdUnchanged(DriveFileState current, String headRevisionId) {
        return current.headRevisionId() == null
                ? headRevisionId == null
                : current.headRevisionId().equals(headRevisionId);
    }

    /**
     * A stored version of null (every record persisted before this field existed, or any record
     * for a file that was native at the time but never saw a non-null version) transitioning to
     * a non-null incoming version is treated as changed, never as "unchanged" - otherwise a
     * native file that upgraded from the old headRevisionId-only behavior would stay stuck at its
     * pre-upgrade revision forever, since both sides of that comparison were always null before.
     */
    private static boolean versionUnchanged(DriveFileState current, Long version) {
        return current.version() == null ? version == null : current.version().equals(version);
    }
}
