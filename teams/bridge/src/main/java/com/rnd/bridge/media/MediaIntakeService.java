package com.rnd.bridge.media;

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
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Lands raw meeting/audio/scanned-doc content through the shared ObjectStorageClient
 * (storage/) - reused from the Unstructured-intake lane so both raw-landing lanes go
 * through one MinIO client/bucket instead of each wiring its own - and builds the matching
 * RawObjectEvent. Takes already-read bytes and an already-computed contentHash rather than
 * a Path: the caller (MediaIntakeOrchestrator) needs both before this is called anyway -
 * for the junk-file filter and the dedup check - so the file is only read off disk once
 * per upload, not once here and once there.
 */
@Service
public class MediaIntakeService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    // This lane never republishes an existing correlationId (no edit/resync path), so
    // revision is always 0 and changeType is always UPSERT - see RawObjectEvent's javadoc.
    private static final int REVISION_INITIAL = 0;

    private final ObjectStorageClient storageClient;

    public MediaIntakeService(ObjectStorageClient storageClient) {
        this.storageClient = storageClient;
    }

    /**
     * Lands the primary recording/scanned-doc file and builds the one RawObjectEvent for this
     * logical item - its object list is [primary ref] or [primary ref, transcriptRef] when a
     * sidecar transcript was landed first (see {@link #landTranscript}). Never publishes a
     * partial object list: this method only runs after any transcript sidecar has already
     * landed successfully (see MediaIntakeOrchestrator.upload's call order), so transcriptRef
     * is either a fully-landed ref or null, never a half-landed one.
     */
    public RawObjectEvent land(byte[] content, String contentHash, String filename, String tenantId,
                                RawSourceType sourceType, Modality modality, Instant ingestedAt,
                                RawObjectRef transcriptRef) {
        String contentType = guessContentType(extractExtension(filename));
        String objectKey = buildObjectKey(sourceType, tenantId, ingestedAt, extractExtension(filename));

        PutResult putResult = storageClient.putObject(
                objectKey, content, contentType,
                ObjectTags.of(sourceType, tenantId, ingestedAt, ScopeDecision.INCLUDED));

        RawObjectRef primaryRef = new RawObjectRef(
                sourceType == RawSourceType.MEETING ? Role.RECORDING : Role.BODY,
                objectKey, contentHash, putResult.contentType(), putResult.sizeBytes(), modality);
        List<RawObjectRef> objects = transcriptRef == null
                ? List.of(primaryRef)
                : List.of(primaryRef, transcriptRef);

        return RawObjectEvent.create(
                sourceType, tenantId, ingestedAt, ScopeDecision.INCLUDED, null, null,
                UUID.randomUUID().toString(), REVISION_INITIAL, ChangeType.UPSERT, objects);
    }

    /**
     * Lands a platform-generated transcript sidecar file (see
     * MediaIntakeOrchestrator.findTranscript) under the same source_type/tenant/date
     * partition as its recording, tagged the same way, and returns the RawObjectRef
     * (role=TRANSCRIPT) for it - the caller passes this into {@link #land} so the recording's
     * event object list includes it, instead of a separate transcript_object_key field.
     */
    public RawObjectRef landTranscript(byte[] content, String filename, String tenantId,
                                        RawSourceType sourceType, Instant ingestedAt) {
        String extension = extractExtension(filename);
        String contentType = switch (extension.toLowerCase()) {
            case ".vtt" -> "text/vtt";
            case ".srt" -> "application/x-subrip";
            default -> "text/plain";
        };
        String objectKey = buildObjectKey(sourceType, tenantId, ingestedAt, extension);

        PutResult putResult = storageClient.putObject(
                objectKey, content, contentType,
                ObjectTags.of(sourceType, tenantId, ingestedAt, ScopeDecision.INCLUDED));

        return new RawObjectRef(
                Role.TRANSCRIPT, objectKey, sha256Hex(content), putResult.contentType(),
                putResult.sizeBytes(), Modality.TEXT);
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(sha256.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String buildObjectKey(RawSourceType sourceType, String tenantId, Instant ingestedAt, String extension) {
        return "%s/%s/%s/%s%s".formatted(
                sourceType.name(), tenantId, DATE_FORMAT.format(ingestedAt), UUID.randomUUID(), extension);
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
}
