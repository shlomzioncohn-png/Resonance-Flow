package com.rnd.bridge.media.classification;

import com.rnd.bridge.media.classification.document.DocumentScan;
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
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic checks that run before the model: free, instant and reproducible, so anything
 * they can settle never costs a model call. Each returns empty when it can't decide - "not
 * obviously junk" is not a verdict, it just means the model gets to judge.
 * <p>
 * Deliberately no "empty transcript" rule: an empty transcript means transcription failed, not
 * that the meeting had no content - a real meeting must never be EXCLUDED for that. The
 * near-empty rule below needs at least one transcribed word, so it never fires on a failure.
 */
@Component
public class ClassificationRules {

    static final String RULES_VERSION = "v4";

    /** A meeting this short whose transcript is also this sparse is a mic check, not a meeting. */
    static final Duration NEAR_EMPTY_MAX_DURATION = Duration.ofMinutes(3);
    static final int NEAR_EMPTY_MAX_WORDS = 15;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
        if (input.metadata() != null
                && input.metadata().distinctParticipantCount().orElse(0) == 1) {
            return Optional.of(ruleVerdict(MeetingCategory.TEST, "meeting had a single participant"));
        }
        int words = wordCount(Transcripts.spokenText(input.transcript()));
        if (duration.isPresent() && duration.get().compareTo(NEAR_EMPTY_MAX_DURATION) < 0
                && words > 0 && words < NEAR_EMPTY_MAX_WORDS) {
            return Optional.of(ruleVerdict(MeetingCategory.TEST, "short meeting with only " + words
                    + " transcribed words"));
        }
        return Optional.empty();
    }

    private static int wordCount(String spoken) {
        String trimmed = spoken.strip();
        return trimmed.isEmpty() ? 0 : WHITESPACE.split(trimmed).length;
    }

    /**
     * BLANK only when every page was checked and every page is blank - an empty cover or
     * separator page in front of real content never makes a document BLANK, and neither does a
     * scan that stopped at its page limit (see DocumentScanner).
     */
    public Optional<Classification> classifyDocument(DocumentScan scan) {
        if (scan.outcome() == DocumentScan.Outcome.ALL_BLANK) {
            return Optional.of(ruleVerdict(DocumentCategory.BLANK, scan.pageCount() == 1
                    ? "page has no visible content"
                    : "all " + scan.pageCount() + " pages have no visible content"));
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
