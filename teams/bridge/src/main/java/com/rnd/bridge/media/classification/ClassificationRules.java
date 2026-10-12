package com.rnd.bridge.media.classification;

import com.rnd.bridge.media.meeting.MeetingMetadata;
import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.ClassifierInfo;
import com.rnd.bridge.raw.model.DecidedBy;
import com.rnd.bridge.raw.model.DocumentCategory;
import com.rnd.bridge.raw.model.ItemCategory;
import com.rnd.bridge.raw.model.Label;
import com.rnd.bridge.raw.model.MeetingCategory;
import com.rnd.bridge.raw.model.Quality;
import com.rnd.bridge.raw.model.Sensitivity;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/**
 * Deterministic checks that run before the model: free, instant and reproducible, so anything
 * they can settle never costs a model call. Each returns empty when it can't decide - "not
 * obviously junk" is not a verdict, it just means the model gets to judge.
 * <p>
 * Deliberately no "empty transcript" rule: an empty transcript means transcription failed, not
 * that the meeting had no content - a real meeting must never be EXCLUDED for that.
 */
@Component
public class ClassificationRules {

    static final String RULES_VERSION = "v1";
    // A page counts as blank when under 0.2% of sampled pixels are darker than near-white.
    private static final int DARK_LUMINANCE_THRESHOLD = 200;
    private static final double MAX_DARK_PIXEL_RATIO_FOR_BLANK = 0.002;
    private static final int MAX_PIXEL_SAMPLES = 250_000;

    private final Duration minMeetingDuration;

    public ClassificationRules(MediaClassificationProperties properties) {
        this.minMeetingDuration = properties.minMeetingDuration();
    }

    public Optional<Classification> classifyMeeting(MeetingClassificationInput input) {
        Optional<Duration> duration = durationOf(input.metadata());
        if (duration.isPresent() && duration.get().compareTo(minMeetingDuration) < 0) {
            return Optional.of(ruleVerdict(MeetingCategory.TEST,
                    "meeting shorter than " + minMeetingDuration.toSeconds() + "s"));
        }
        return Optional.empty();
    }

    /**
     * Only a single-page document can be BLANK: a multi-page scan with an empty cover or
     * separator page is still a real document.
     */
    public Optional<Classification> classifyDocument(DocumentClassificationInput input) {
        DecodedDocument document = decode(input.content());
        if (document != null && document.pageCount() == 1 && isBlank(document.firstPage())) {
            return Optional.of(ruleVerdict(DocumentCategory.BLANK, "page has no visible content"));
        }
        return Optional.empty();
    }

    /**
     * Zoom reports duration in whole minutes, so start/end timestamps are preferred when both
     * are present - a 40-second recording is "0 minutes" either way, but 70 seconds isn't.
     */
    private static Optional<Duration> durationOf(MeetingMetadata metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        if (metadata.startedAt() != null && metadata.endedAt() != null) {
            return Optional.of(Duration.between(metadata.startedAt(), metadata.endedAt()));
        }
        return Optional.ofNullable(metadata.durationMinutes()).map(Duration::ofMinutes);
    }

    /**
     * The first page and the page count (a multi-page TIFF has several), or null when ImageIO
     * can't decode it (e.g. a PDF) - the image-based checks then don't apply.
     */
    static DecodedDocument decode(byte[] content) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = stream == null ? null : ImageIO.getImageReaders(stream);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                int pageCount = Math.max(1, reader.getNumImages(true));
                return new DecodedDocument(reader.read(0), pageCount);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    record DecodedDocument(BufferedImage firstPage, int pageCount) {
    }

    private static boolean isBlank(BufferedImage image) {
        long pixels = (long) image.getWidth() * image.getHeight();
        int step = (int) Math.max(1, Math.ceil(Math.sqrt((double) pixels / MAX_PIXEL_SAMPLES)));
        long sampled = 0;
        long dark = 0;
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (0.299 * r + 0.587 * g + 0.114 * b < DARK_LUMINANCE_THRESHOLD) {
                    dark++;
                }
                sampled++;
            }
        }
        return sampled > 0 && (double) dark / sampled < MAX_DARK_PIXEL_RATIO_FOR_BLANK;
    }

    private static Classification ruleVerdict(ItemCategory category, String reason) {
        return new Classification(
                DecidedBy.RULE,
                Label.fromRule(category),
                Label.fromRule(Quality.GOOD),
                Label.fromRule(Sensitivity.NONE),
                reason,
                new ClassifierInfo(ClassifierInfo.RULES_MODEL, RULES_VERSION));
    }
}
