package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.lane.DriveFileStateStore;
import com.rnd.bridge.intake.model.DriveFileState;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves the correlationId/revision Bridge should use for one Google Drive file, given its
 * stable fileId and Google's headRevisionId for the version currently being read - the Drive
 * analog of {@link CalendarEventStateReconciler} (see its javadoc for the full reasoning this
 * mirrors, substituting fileId for eventId and headRevisionId for etag).
 * resolve()'s three outcomes, matching DriveFileState's javadoc:
 * - first-seen fileId: mint a new correlationId, start at revision 0, unconfirmed.
 * - same headRevisionId as last time: a retry - reuse the same correlationId/revision/
 *   headRevisionId/published state untouched.
 * - different headRevisionId: a genuinely newer Drive version (or a trashed file - see
 *   RealDriveSourceReader - the reconciler has no notion of "trashed", only headRevisionId
 *   changes; deciding changeType is entirely RealDriveSourceReader's job) - same correlationId,
 *   revision + 1, published reset to false.
 */
@Service
public class DriveFileStateReconciler {

    private final DriveFileStateStore store;

    public DriveFileStateReconciler(DriveFileStateStore store) {
        this.store = store;
    }

    public DriveFileState resolve(String tenantId, String fileId, String headRevisionId) {
        return store.update(tenantId, fileId, current -> {
            if (current.fileId() == null) {
                return new DriveFileState(fileId, tenantId, UUID.randomUUID().toString(), headRevisionId, 0, false);
            }
            if (headRevisionIdUnchanged(current, headRevisionId)) {
                return current;
            }
            return new DriveFileState(
                    fileId, tenantId, current.correlationId(), headRevisionId, current.revision() + 1, false);
        });
    }

    private static boolean headRevisionIdUnchanged(DriveFileState current, String headRevisionId) {
        return current.headRevisionId() == null
                ? headRevisionId == null
                : current.headRevisionId().equals(headRevisionId);
    }
}
