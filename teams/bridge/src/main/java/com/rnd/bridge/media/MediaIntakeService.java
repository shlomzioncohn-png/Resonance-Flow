package com.rnd.bridge.media;

import com.rnd.bridge.media.classification.Triage;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.storage.ObjectStorageClient;
import com.rnd.bridge.storage.ObjectStorageClient.PutResult;
import com.rnd.bridge.storage.ObjectTags;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Lands raw meeting/audio/scanned-doc content through the shared ObjectStorageClient
 * (storage/) - reused from the Unstructured-intake lane so both raw-landing lanes go
 * through one MinIO client/bucket instead of each wiring its own - and builds the matching
 * RawObjectEvent, under the shared "one event, objects[] list" shape (see
 * {@link RawObjectEvent}'s javadoc) rather than one event per physical file.
 * <p>
 * The scope_decision on both the event and every landed object's MinIO tags, and the event's
 * optional classification, come from the caller's {@link Triage} (see
 * media.classification.MediaClassificationService) - this class only applies them.
 * <p>
 * Both this lane's items are brand-new on first landing and never revised afterward (a Zoom
 * recording doesn't get edited; a scanned doc doesn't either) - so every event this class
 * builds is {@code revision 0}, {@link ChangeType#UPSERT}, {@code changeSignificanceScore
 * 100}, always. If that ever stops being true for some new source this lane adds, those
 * three stop being hardcoded here, not before.
 */
@Service
public class MediaIntakeService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final int NEW_ITEM_REVISION = 0;

    private final ObjectStorageClient storageClient;

    public MediaIntakeService(@Qualifier("minioObjectStorageClient") ObjectStorageClient storageClient) {
        this.storageClient = storageClient;
    }

    /**
     * A standalone item with exactly one object - today, scanned docs. Landed as
     * {@link Role#BODY}: it's the primary (only) content of the logical item, the same
     * structural slot an email body fills, not a Zoom-specific role.
     */
    public RawObjectEvent landStandalone(byte[] content, String contentHash, String filename, String tenantId,
                                          RawSourceType sourceType, Modality modality, Instant ingestedAt,
                                          String correlationId, Triage triage) {
        RawObjectRef object = landObject(content, contentHash, filename, tenantId, sourceType, ingestedAt,
                correlationId, Role.BODY, modality, triage.scopeDecision());
        return buildEvent(sourceType, tenantId, correlationId, ingestedAt, null, null, null,
                List.of(object), triage);
    }

    /**
     * A meeting recording plus whichever of its two optional sidecars were found
     * (transcript, platform meeting-metadata) - see MediaIntakeOrchestrator. All objects
     * belonging to this one logical recording land here together and go out as a single
     * event, sharing correlationId. transcript/metadata content+filename are null when that
     * sidecar wasn't found; sourceRef is the platform's own stable reference for the meeting
     * (e.g. Zoom's meeting uuid) - null if the metadata sidecar wasn't found or didn't parse.
     */
    public RawObjectEvent landMeeting(byte[] recordingContent, String recordingContentHash,
                                       String recordingFilename, byte[] transcriptContent,
                                       String transcriptFilename, byte[] metadataContent,
                                       String metadataFilename, String tenantId, RawSourceType sourceType,
                                       Instant ingestedAt, List<String> participants, Instant sourceTimestamp,
                                       String sourceRef, String correlationId, Triage triage) {
        ScopeDecision scopeDecision = triage.scopeDecision();
        List<RawObjectRef> objects = new ArrayList<>();
        objects.add(landObject(recordingContent, recordingContentHash, recordingFilename, tenantId, sourceType,
                ingestedAt, correlationId, Role.RECORDING, Modality.AUDIO, scopeDecision));

        if (transcriptContent != null) {
            objects.add(landObject(transcriptContent, sha256Hex(transcriptContent), transcriptFilename, tenantId,
                    sourceType, ingestedAt, correlationId, Role.TRANSCRIPT, Modality.TEXT, scopeDecision));
        }
        if (metadataContent != null) {
            objects.add(landObject(metadataContent, sha256Hex(metadataContent), metadataFilename, tenantId,
                    sourceType, ingestedAt, correlationId, Role.SOURCE_METADATA, Modality.TEXT, scopeDecision));
        }

        return buildEvent(sourceType, tenantId, correlationId, ingestedAt, participants, sourceTimestamp,
                sourceRef, objects, triage);
    }

    private RawObjectEvent buildEvent(RawSourceType sourceType, String tenantId, String correlationId,
                                       Instant ingestedAt, List<String> participants, Instant sourceTimestamp,
                                       String sourceRef, List<RawObjectRef> objects, Triage triage) {
        return RawObjectEvent.create(
                        sourceType, tenantId, ingestedAt, triage.scopeDecision(), participants, sourceTimestamp,
                        correlationId, NEW_ITEM_REVISION, ChangeType.UPSERT, objects)
                .withSourceRef(sourceRef)
                .withClassification(triage.classification());
    }

    private RawObjectRef landObject(byte[] content, String contentHash, String filename, String tenantId,
                                     RawSourceType sourceType, Instant ingestedAt, String correlationId,
                                     Role role, Modality modality, ScopeDecision scopeDecision) {
        String extension = extractExtension(filename);
        String contentType = contentTypeFor(role, extension);
        String objectKey = buildObjectKey(sourceType, tenantId, ingestedAt, correlationId, extension);

        PutResult putResult = storageClient.putObject(
                objectKey, content, contentType,
                ObjectTags.of(sourceType, tenantId, ingestedAt, scopeDecision));

        return new RawObjectRef(role, objectKey, contentHash, putResult.contentType(), putResult.sizeBytes(),
                modality);
    }

    private String contentTypeFor(Role role, String extension) {
        return switch (role) {
            case TRANSCRIPT -> switch (extension.toLowerCase()) {
                case ".vtt" -> "text/vtt";
                case ".srt" -> "application/x-subrip";
                default -> "text/plain";
            };
            case SOURCE_METADATA -> switch (extension.toLowerCase()) {
                case ".json" -> "application/json";
                case ".xml" -> "application/xml";
                default -> "application/octet-stream";
            };
            case RECORDING, BODY, ATTACHMENT -> guessContentType(extension);
        };
    }

    private String buildObjectKey(RawSourceType sourceType, String tenantId, Instant ingestedAt,
                                   String correlationId, String extension) {
        return "%s/%s/%s/%s%s".formatted(
                sourceType.name(), tenantId, DATE_FORMAT.format(ingestedAt), correlationId, extension);
    }

    private String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private String guessContentType(String extension) {
        return switch (extension.toLowerCase()) {
            case ".mp3" -> "audio/mpeg";
            case ".wav" -> "audio/wav";
            case ".mp4", ".m4a" -> "audio/mp4";
            case ".pdf" -> "application/pdf";
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".tif", ".tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(sha256.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
