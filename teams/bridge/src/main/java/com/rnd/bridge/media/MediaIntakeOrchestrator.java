package com.rnd.bridge.media;

import com.rnd.bridge.media.meeting.FixtureBbbMeetingMetadataReader;
import com.rnd.bridge.media.meeting.FixtureZoomMeetingMetadataReader;
import com.rnd.bridge.media.meeting.MeetingMetadata;
import com.rnd.bridge.media.meeting.MeetingMetadataReader;
import com.rnd.bridge.media.meeting.ParticipantActivity;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Manual test-intake trigger: scans the configured zoom/bbb-recordings and
 * scanned-docs folders and uploads whatever it finds there. Each successfully
 * uploaded file is moved into an "uploaded/" subfolder of wherever it came
 * from, so a re-run only picks up genuinely new files instead of re-landing
 * (and re-publishing an event for) the same file every time it's triggered.
 * A stand-in for a real intake trigger - it only owns land+tag once a file
 * already exists locally, not how it got there.
 * <p>
 * Adding a new intake folder (e.g. a third source) is additive: add one
 * {@code @Value}-bound directory field and one new {@link IntakeSource} entry
 * in the constructor - {@link #runIntake()} and {@link #processDir} don't change.
 * <p>
 * Per file, in order: (1) junk filter - empty or below {@code minFileSizeBytes} is moved
 * to "rejected/" and never lands; (2) dedup check against {@link MediaDedupIndex} (MinIO,
 * keyed per source_type/tenant) - a content hash already seen is moved to "uploaded/" but
 * not re-landed/re-published; (3) for a MEETING recording, a same-basename transcript
 * sidecar ({@code .vtt}/{@code .srt}/{@code .txt}) is landed alongside it and linked via
 * transcriptObjectKey, and a same-basename meeting-metadata sidecar (Zoom:
 * {@code .meeting.json}, BBB: {@code .metadata.xml}) is landed and linked via
 * meetingMetadataObjectKey, with its parsed participant names on the event's participants
 * field; (4) land + publish. Both sidecar kinds are moved to "uploaded/" alongside their
 * recording even when the recording itself turns out to be a dedup skip, or they'd sit
 * unmatched forever (same reasoning already applied to transcripts).
 */
@Service
public class MediaIntakeOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MediaIntakeOrchestrator.class);
    private static final String UPLOADED_SUBDIR = "uploaded";
    private static final String REJECTED_SUBDIR = "rejected";
    private static final List<String> TRANSCRIPT_EXTENSIONS = List.of(".vtt", ".srt", ".txt");
    // Distinct per-source extensions, not a shared list like TRANSCRIPT_EXTENSIONS: each
    // recordings dir only ever pairs with its own platform's sidecar shape, so there's no
    // ambiguity to resolve the way multiple possible transcript formats need.
    private static final String MEETING_METADATA_EXTENSION_ZOOM = ".meeting.json";
    private static final String MEETING_METADATA_EXTENSION_BBB = ".metadata.xml";

    private final MediaIntakeService intakeService;
    private final MediaEventPublisher eventPublisher;
    private final MediaDedupIndex dedupIndex;
    private final String tenantId;
    private final long minFileSizeBytes;
    private final List<IntakeSource> intakeSources;

    public MediaIntakeOrchestrator(MediaIntakeService intakeService,
                                    MediaEventPublisher eventPublisher,
                                    MediaDedupIndex dedupIndex,
                                    FixtureZoomMeetingMetadataReader zoomMeetingMetadataReader,
                                    FixtureBbbMeetingMetadataReader bbbMeetingMetadataReader,
                                    @Value("${bridge.media.zoom-recordings-dir}") String zoomRecordingsDir,
                                    @Value("${bridge.media.bbb-recordings-dir}") String bbbRecordingsDir,
                                    @Value("${bridge.media.scanned-docs-dir}") String scannedDocsDir,
                                    @Value("${bridge.media.tenant-id}") String tenantId,
                                    @Value("${bridge.media.min-file-size-bytes:1024}") long minFileSizeBytes) {
        this.intakeService = intakeService;
        this.eventPublisher = eventPublisher;
        this.dedupIndex = dedupIndex;
        this.tenantId = tenantId;
        this.minFileSizeBytes = minFileSizeBytes;
        this.intakeSources = List.of(
                new IntakeSource("bridge.media.zoom-recordings-dir", zoomRecordingsDir,
                        RawSourceType.MEETING, Modality.AUDIO,
                        MEETING_METADATA_EXTENSION_ZOOM, zoomMeetingMetadataReader),
                new IntakeSource("bridge.media.bbb-recordings-dir", bbbRecordingsDir,
                        RawSourceType.MEETING, Modality.AUDIO,
                        MEETING_METADATA_EXTENSION_BBB, bbbMeetingMetadataReader),
                new IntakeSource("bridge.media.scanned-docs-dir", scannedDocsDir,
                        RawSourceType.SCANNED_DOC, Modality.SCANNED_DOC, null, null)
        );
    }

    public IntakeSummary runIntake() {
        List<String> uploaded = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (IntakeSource source : intakeSources) {
            processDir(source, uploaded, duplicates, rejected, failed);
        }
        return new IntakeSummary(uploaded, duplicates, rejected, failed);
    }

    private void processDir(IntakeSource source, List<String> uploaded, List<String> duplicates,
                             List<String> rejected, List<String> failed) {
        if (source.dir() == null || source.dir().isBlank()) {
            log.info("{} not set - skipping", source.propertyName());
            return;
        }

        Path dir = Path.of(source.dir());
        if (!Files.isDirectory(dir)) {
            log.warn("Configured {} does not exist: {}", source.propertyName(), dir);
            return;
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile).toList();
        } catch (Exception e) {
            log.error("Failed to list {} {}", source.propertyName(), dir, e);
            return;
        }

        // Transcript and meeting-metadata sidecars are consumed by their recording, not
        // processed as their own top-level upload.
        Set<Path> sidecars = new HashSet<>();
        if (source.sourceType() == RawSourceType.MEETING) {
            for (Path file : files) {
                findTranscript(file, dir).ifPresent(sidecars::add);
                findMeetingMetadata(file, dir, source).ifPresent(sidecars::add);
            }
        }

        Path uploadedDir = dir.resolve(UPLOADED_SUBDIR);
        Path rejectedDir = dir.resolve(REJECTED_SUBDIR);

        for (Path file : files) {
            if (sidecars.contains(file)) {
                continue;
            }
            upload(file, dir, uploadedDir, rejectedDir, source, uploaded, duplicates, rejected, failed);
        }
    }

    private void upload(Path file, Path dir, Path uploadedDir, Path rejectedDir, IntakeSource source,
                         List<String> uploaded, List<String> duplicates,
                         List<String> rejected, List<String> failed) {
        try {
            byte[] content = Files.readAllBytes(file);

            if (content.length == 0) {
                reject(file, rejectedDir, "empty", rejected);
                return;
            }
            if (content.length < minFileSizeBytes) {
                reject(file, rejectedDir, "below-min-size", rejected);
                return;
            }

            Path transcript = source.sourceType() == RawSourceType.MEETING
                    ? findTranscript(file, dir).orElse(null)
                    : null;
            Path meetingMetadataFile = source.sourceType() == RawSourceType.MEETING
                    ? findMeetingMetadata(file, dir, source).orElse(null)
                    : null;

            String contentHash = sha256Hex(content);
            boolean isDuplicate = dedupIndex.checkAndRecord(source.sourceType(), tenantId, contentHash);
            if (isDuplicate) {
                moveTo(file, uploadedDir);
                // Sidecars are tied to this specific filename, not to the content hash -
                // move them out of the way too, or they sit here forever getting re-checked
                // (and never matched, since the recording is never itself in seenHashes)
                // on every run.
                if (transcript != null) {
                    moveTo(transcript, uploadedDir);
                }
                if (meetingMetadataFile != null) {
                    moveTo(meetingMetadataFile, uploadedDir);
                }
                log.info("Skipped {} - duplicate of already-landed content", file.getFileName());
                duplicates.add(file.getFileName().toString());
                return;
            }

            try {
                Instant ingestedAt = Instant.now();
                // Shared by this recording's object_key and its sidecars' object_keys, so
                // the (up to) 3 files land next to each other when browsing MinIO by name -
                // object_key's schema-enforced pattern only allows sourceType/tenant/date/file
                // (no extra subfolder), so a shared filename-stem is the only way to group them.
                String recordingId = UUID.randomUUID().toString();

                String transcriptObjectKey = null;
                if (transcript != null) {
                    byte[] transcriptContent = Files.readAllBytes(transcript);
                    transcriptObjectKey = intakeService.landTranscript(
                            transcriptContent, transcript.getFileName().toString(), tenantId,
                            source.sourceType(), ingestedAt, recordingId);
                }

                String meetingMetadataObjectKey = null;
                List<String> participants = null;
                if (meetingMetadataFile != null) {
                    byte[] meetingMetadataContent = Files.readAllBytes(meetingMetadataFile);
                    meetingMetadataObjectKey = intakeService.landMeetingMetadata(
                            meetingMetadataContent, meetingMetadataFile.getFileName().toString(),
                            tenantId, source.sourceType(), ingestedAt, recordingId);
                    participants = extractParticipantNames(source, meetingMetadataFile);
                }

                RawObjectEvent event = intakeService.land(
                        content, contentHash, file.getFileName().toString(), tenantId,
                        source.sourceType(), source.modality(), ingestedAt, transcriptObjectKey,
                        participants, meetingMetadataObjectKey, recordingId);
                eventPublisher.publish(event);

                moveTo(file, uploadedDir);
                if (transcript != null) {
                    moveTo(transcript, uploadedDir);
                }
                if (meetingMetadataFile != null) {
                    moveTo(meetingMetadataFile, uploadedDir);
                }

                log.info("Uploaded {} as {}", file.getFileName(), event.objectKey());
                uploaded.add(event.objectKey());
            } catch (Exception e) {
                // checkAndRecord() already claimed contentHash above so a concurrent run
                // couldn't land the same content twice - but this attempt failed before
                // actually landing anything, so undo the claim or this content would be
                // treated as "already seen" forever and never get a real retry.
                dedupIndex.forget(source.sourceType(), tenantId, contentHash);
                throw e;
            }
        } catch (Exception e) {
            log.error("Failed to upload {}", file, e);
            failed.add(file.getFileName().toString());
        }
    }

    private void reject(Path file, Path rejectedDir, String reason, List<String> rejected) throws IOException {
        Files.createDirectories(rejectedDir);
        Files.move(file, rejectedDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        log.info("Rejected {} ({})", file.getFileName(), reason);
        rejected.add("%s (%s)".formatted(file.getFileName(), reason));
    }

    private void moveTo(Path file, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Files.move(file, targetDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }

    private Optional<Path> findTranscript(Path recording, Path dir) {
        String basename = stripExtension(recording.getFileName().toString());
        for (String extension : TRANSCRIPT_EXTENSIONS) {
            Path candidate = dir.resolve(basename + extension);
            if (!candidate.equals(recording) && Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<Path> findMeetingMetadata(Path recording, Path dir, IntakeSource source) {
        if (source.meetingMetadataExtension() == null) {
            return Optional.empty();
        }
        String basename = stripExtension(recording.getFileName().toString());
        Path candidate = dir.resolve(basename + source.meetingMetadataExtension());
        return !candidate.equals(recording) && Files.isRegularFile(candidate)
                ? Optional.of(candidate) : Optional.empty();
    }

    private List<String> extractParticipantNames(IntakeSource source, Path meetingMetadataFile) {
        try {
            MeetingMetadata metadata = source.meetingMetadataReader().read(meetingMetadataFile);
            return metadata.participants().stream().map(ParticipantActivity::name).toList();
        } catch (Exception e) {
            log.warn("Failed to parse meeting-metadata sidecar {} - landing it anyway, "
                    + "participants left unset", meetingMetadataFile, e);
            return null;
        }
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(sha256.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // meetingMetadataExtension/Reader are null for a source with no meeting-metadata sidecar
    // convention (scanned-docs).
    private record IntakeSource(String propertyName, String dir, RawSourceType sourceType, Modality modality,
                                 String meetingMetadataExtension, MeetingMetadataReader meetingMetadataReader) {
    }

    public record IntakeSummary(List<String> uploadedObjectKeys, List<String> duplicateFiles,
                                 List<String> rejectedFiles, List<String> failedFiles) {
    }
}
