package com.rnd.bridge.media;

import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.storage.ObjectStorageClient;
import com.rnd.bridge.storage.ObjectStorageClient.PutResult;
import com.rnd.bridge.storage.ObjectTags;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    // Mind's confirmed RawObjectEvent contract requires attachment_index; 0 marks a
    // standalone item (this lane never splits one source item into a body + attachments).
    private static final int ATTACHMENT_INDEX_STANDALONE = 0;

    private final ObjectStorageClient storageClient;

    public MediaIntakeService(ObjectStorageClient storageClient) {
        this.storageClient = storageClient;
    }

    public RawObjectEvent land(byte[] content, String contentHash, String filename, String tenantId,
                                RawSourceType sourceType, Modality modality, Instant ingestedAt,
                                String transcriptObjectKey) {
        return land(content, contentHash, filename, tenantId, sourceType, modality, ingestedAt,
                transcriptObjectKey, null, null, UUID.randomUUID().toString());
    }

    /**
     * Same as the 8-arg {@link #land} but also carries the participant names and
     * meeting-metadata sidecar pointer derived from a landed Zoom/BBB meeting-metadata
     * sidecar (see media.meeting package) - both null when no such sidecar was found - and
     * recordingId, the shared id this recording's object_key and its sidecars' object_keys
     * all use (see landTranscript/landMeetingMetadata) so the three land next to each other
     * when browsing MinIO by name, not scattered among unrelated recordings from the same day.
     */
    public RawObjectEvent land(byte[] content, String contentHash, String filename, String tenantId,
                                RawSourceType sourceType, Modality modality, Instant ingestedAt,
                                String transcriptObjectKey, List<String> participants,
                                String meetingMetadataObjectKey, String recordingId) {
        String contentType = guessContentType(extractExtension(filename));
        String objectKey = buildObjectKey(sourceType, tenantId, ingestedAt, recordingId, extractExtension(filename));

        PutResult putResult = storageClient.putObject(
                objectKey, content, contentType,
                ObjectTags.of(sourceType, tenantId, ingestedAt, ScopeDecision.INCLUDED));

        return RawObjectEvent.create(
                objectKey, sourceType, tenantId, ingestedAt, ScopeDecision.INCLUDED, contentHash,
                modality, participants, putResult.contentType(), putResult.sizeBytes(),
                transcriptObjectKey, null, null, ATTACHMENT_INDEX_STANDALONE, meetingMetadataObjectKey);
    }

    /**
     * Lands a platform-generated transcript sidecar file (see
     * MediaIntakeOrchestrator.findTranscript) under the same source_type/tenant/date
     * partition as its recording, tagged the same way, and sharing its recordingId (see
     * {@link #land}) so the two land next to each other in MinIO. No RawObjectEvent/publish
     * of its own - it's referenced from the recording's event via transcriptObjectKey, per
     * Mind's confirmed contract ("MinIO pointer to a platform-generated transcript").
     */
    public String landTranscript(byte[] content, String filename, String tenantId,
                                  RawSourceType sourceType, Instant ingestedAt, String recordingId) {
        String extension = extractExtension(filename);
        String contentType = switch (extension.toLowerCase()) {
            case ".vtt" -> "text/vtt";
            case ".srt" -> "application/x-subrip";
            default -> "text/plain";
        };
        String objectKey = buildObjectKey(sourceType, tenantId, ingestedAt, recordingId, extension);

        storageClient.putObject(
                objectKey, content, contentType,
                ObjectTags.of(sourceType, tenantId, ingestedAt, ScopeDecision.INCLUDED));

        return objectKey;
    }

    /**
     * Lands a Zoom/BBB meeting-metadata sidecar file (see MediaIntakeOrchestrator and
     * media.meeting) in its source-native format - Zoom's JSON as application/json, BBB's
     * metadata.xml as application/xml - unconverted, under the same source_type/tenant/date
     * partition as its recording, sharing its recordingId (see {@link #land}) so the two
     * land next to each other in MinIO. No RawObjectEvent/publish of its own - referenced
     * from the recording's event via meetingMetadataObjectKey, same pattern as landTranscript.
     */
    public String landMeetingMetadata(byte[] content, String filename, String tenantId,
                                       RawSourceType sourceType, Instant ingestedAt, String recordingId) {
        String extension = extractExtension(filename);
        String contentType = switch (extension.toLowerCase()) {
            case ".json" -> "application/json";
            case ".xml" -> "application/xml";
            default -> "application/octet-stream";
        };
        String objectKey = buildObjectKey(sourceType, tenantId, ingestedAt, recordingId, extension);

        storageClient.putObject(
                objectKey, content, contentType,
                ObjectTags.of(sourceType, tenantId, ingestedAt, ScopeDecision.INCLUDED));

        return objectKey;
    }

    private String buildObjectKey(RawSourceType sourceType, String tenantId, Instant ingestedAt,
                                   String recordingId, String extension) {
        return "%s/%s/%s/%s%s".formatted(
                sourceType.name(), tenantId, DATE_FORMAT.format(ingestedAt), recordingId, extension);
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
