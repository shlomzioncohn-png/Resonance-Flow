package com.rnd.bridge.media.classification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks a chat model to triage one item as a whole - category, file quality, sensitivity - and
 * turns its reply into a {@link Classification}. Meetings are judged from their transcript and
 * topic (never the audio, never participant names); scanned docs from the page image.
 * <p>
 * The prompt holds the model to Bridge's boundary: it must not extract tasks, people, dates,
 * owners or priority - that is Mind's (and Chain's) job. The reply is a flat JSON object, simpler
 * for a model to produce reliably than the nested wire shape, and is validated strictly: an
 * unknown category becomes OTHER (the same rule the wire contract gives consumers), anything else
 * malformed throws, which {@link MediaClassificationService} turns into "unclassified".
 * <p>
 * Changing either prompt changes the classifier's behavior, so it must come with a bump of
 * bridge.media.classification.model.prompt-version - that's what keeps results comparable.
 */
@Component
public class ModelMediaClassifier {

    private static final Logger log = LoggerFactory.getLogger(ModelMediaClassifier.class);
    private static final int MAX_REASON_CHARS = 200;
    // Larger pages cost more tokens without helping a document-type decision.
    private static final int MAX_IMAGE_SIDE_PX = 1600;

    private static final String SHARED_RULES = """
            You triage items arriving at a work-intake pipeline. Judge the item AS A WHOLE:
            what kind of item it is, how usable the file is, and how sensitive its content is.
            You NEVER extract tasks, action items, people, owners, dates, deadlines or priorities.

            quality - the file itself, not how relevant it is:
              GOOD: fully usable. POOR: usable with difficulty (noise, blur, broken text).
              UNREADABLE: nothing meaningful can be recovered.
            sensitivity - how much care the content calls for:
              NONE: nothing sensitive.
              LOW: ordinary personal or business details (names, emails, a vendor invoice, a budget).
              HIGH: harmful if exposed (medical, an individual's termination or salary,
                    litigation, bank details, national ID numbers).

            Reply with ONLY this JSON object, no other text:
            {"category": "<one of the categories>", "category_confidence": <0..1>,
             "quality": "GOOD|POOR|UNREADABLE", "quality_confidence": <0..1>,
             "sensitivity": "NONE|LOW|HIGH", "sensitivity_confidence": <0..1>,
             "reason": "<at most 15 words, generic - no names, no dates>"}
            """;

    private static final String MEETING_PROMPT = SHARED_RULES + """

            The item is a recorded meeting, given as its topic and a transcript excerpt.
            categories:
              PLANNING: planning or prioritizing work. STANDUP: short status round.
              CUSTOMER_CALL: with a customer, prospect or partner. INTERVIEW: a hiring interview.
              ONE_ON_ONE: a manager/report or peer 1:1. SOCIAL: non-work chat.
              TEST: a mic/connection check or recording with no real content.
              OTHER: anything else.
            """;

    private static final String DOCUMENT_PROMPT = SHARED_RULES + """

            The item is a scanned document page, given as an image.
            categories: %s.
              BLANK: an empty page. OTHER: none of the above fits.
            """.formatted(Arrays.stream(DocumentCategory.values()).map(Enum::name)
                    .collect(Collectors.joining(", ")));

    private final ChatCompletionClient chatClient;
    private final ObjectMapper objectMapper;
    private final MediaClassificationProperties properties;

    public ModelMediaClassifier(ChatCompletionClient chatClient, ObjectMapper objectMapper,
                                MediaClassificationProperties properties) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Empty when there's nothing to judge from (no transcript and no topic). */
    public Optional<Classification> classifyMeeting(MeetingClassificationInput input) {
        String spoken = Transcripts.spokenText(input.transcript());
        String topic = input.metadata() == null ? null : input.metadata().topic();
        if (spoken.isEmpty() && (topic == null || topic.isBlank())) {
            log.info("Meeting has neither transcript nor topic - nothing for the model to classify");
            return Optional.empty();
        }
        String reply = chatClient.complete(MEETING_PROMPT, describeMeeting(input.metadata(), topic, spoken), null);
        return Optional.of(parse(reply, MeetingCategory::valueOf, MeetingCategory.OTHER));
    }

    /** Empty when the file isn't a decodable image (e.g. a PDF) - those aren't sent to the model. */
    public Optional<Classification> classifyDocument(DocumentClassificationInput input) {
        BufferedImage image = ClassificationRules.decode(input.content());
        if (image == null) {
            log.info("{} is not a decodable image - skipping model classification", input.filename());
            return Optional.empty();
        }
        String reply = chatClient.complete(DOCUMENT_PROMPT, "Classify this scanned document page.", toPng(image));
        return Optional.of(parse(reply, DocumentCategory::valueOf, DocumentCategory.OTHER));
    }

    /** Topic, length, headcount and transcript - deliberately no participant names or emails. */
    private String describeMeeting(MeetingMetadata metadata, String topic, String spoken) {
        StringBuilder text = new StringBuilder();
        if (topic != null && !topic.isBlank()) {
            text.append("Topic: ").append(topic).append('\n');
        }
        if (metadata != null && metadata.startedAt() != null && metadata.endedAt() != null) {
            text.append("Duration: ")
                    .append(Duration.between(metadata.startedAt(), metadata.endedAt()).toMinutes())
                    .append(" minutes\n");
        }
        if (metadata != null && metadata.participants() != null && !metadata.participants().isEmpty()) {
            text.append("Participants: ").append(metadata.participants().size()).append('\n');
        }
        text.append("Transcript excerpt:\n")
                .append(spoken.isEmpty() ? "(no transcript)" : truncate(spoken, properties.transcriptExcerptChars()));
        return text.toString();
    }

    private Classification parse(String reply, Function<String, ItemCategory> categoryParser, ItemCategory other) {
        ModelVerdict verdict;
        try {
            verdict = objectMapper.readValue(extractJsonObject(reply), ModelVerdict.class);
        } catch (IOException e) {
            throw new IllegalStateException("Model reply is not the expected JSON: " + truncate(reply, 300), e);
        }
        return new Classification(
                DecidedBy.MODEL,
                new Label<>(parseCategory(verdict.category(), categoryParser, other), verdict.categoryConfidence()),
                new Label<>(Quality.valueOf(verdict.quality()), verdict.qualityConfidence()),
                new Label<>(Sensitivity.valueOf(verdict.sensitivity()), verdict.sensitivityConfidence()),
                truncate(verdict.reason() == null ? "" : verdict.reason().strip(), MAX_REASON_CHARS),
                new ClassifierInfo(properties.model().name(), properties.model().promptVersion()));
    }

    private static ItemCategory parseCategory(String value, Function<String, ItemCategory> parser, ItemCategory other) {
        try {
            return parser.apply(value.strip().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.info("Model returned unknown category '{}' - recording it as {}", value, other);
            return other;
        }
    }

    /** Tolerates a reply wrapped in ```json fences or a sentence of prose around the object. */
    private static String extractJsonObject(String reply) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalStateException("Model reply contains no JSON object: " + truncate(reply, 300));
        }
        return reply.substring(start, end + 1);
    }

    private static byte[] toPng(BufferedImage image) {
        BufferedImage scaled = image;
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest > MAX_IMAGE_SIDE_PX) {
            double factor = (double) MAX_IMAGE_SIDE_PX / longest;
            int width = (int) Math.round(image.getWidth() * factor);
            int height = (int) Math.round(image.getHeight() * factor);
            scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            scaled.getGraphics().drawImage(image.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(scaled, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to re-encode page image as PNG", e);
        }
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelVerdict(
            @JsonProperty("category") String category,
            @JsonProperty("category_confidence") Double categoryConfidence,
            @JsonProperty("quality") String quality,
            @JsonProperty("quality_confidence") Double qualityConfidence,
            @JsonProperty("sensitivity") String sensitivity,
            @JsonProperty("sensitivity_confidence") Double sensitivityConfidence,
            @JsonProperty("reason") String reason) {
    }
}
