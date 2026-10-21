package com.rnd.bridge.media.classification;

import com.rnd.bridge.ai.LlmQuotaExhaustedException;
import com.rnd.bridge.media.classification.document.DocumentScan;
import com.rnd.bridge.media.classification.document.DocumentScanner;
import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.ItemCategory;
import com.rnd.bridge.raw.model.Label;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decides each media item's scope_decision and the classification behind it: deterministic
 * {@link ClassificationRules} first, then the {@link ModelMediaClassifier} only for what the
 * rules couldn't settle (and only when a model API key is configured). A scanned document is
 * first examined once by {@link DocumentScanner}, and that one scan is what both the rules and
 * the model work from.
 * <p>
 * Fails open, always: disabled, nothing to judge, a model error or a malformed reply all give
 * {@link Triage#UNCLASSIFIED} (INCLUDED, no classification) - losing a real meeting is worse
 * than forwarding a junk one, so this never throws and never blocks intake. A result is only
 * EXCLUDED when its category is configured as excluded <em>and</em> its confidence reaches
 * min-confidence-to-exclude; a less confident result is still attached, but stays INCLUDED.
 * <p>
 * No model quota left (the free tier's daily cap, or bridge.ai's own daily-call-limit) is the
 * same fail-open, chosen on purpose: the item is published now, INCLUDED and unclassified, rather
 * than held back until the quota renews. It gets one WARN line with the marker
 * {@code CLASSIFICATION_SKIPPED_QUOTA} and the renewal time, so a day's worth of unclassified
 * items is easy to find; the rules (which cost no quota) still run first.
 */
@Service
public class MediaClassificationService {

    private static final Logger log = LoggerFactory.getLogger(MediaClassificationService.class);

    private final ClassificationRules rules;
    private final ModelMediaClassifier model;
    private final DocumentScanner documentScanner;
    private final MediaClassificationProperties properties;

    public MediaClassificationService(ClassificationRules rules, ModelMediaClassifier model,
                                      DocumentScanner documentScanner, MediaClassificationProperties properties) {
        this.rules = rules;
        this.model = model;
        this.documentScanner = documentScanner;
        this.properties = properties;
        log.info("Media classification {}", !properties.enabled() ? "disabled"
                : model.meetingModelConfigured() || model.documentModelConfigured()
                ? "enabled (rules + model, see bridge.ai usages " + ModelMediaClassifier.MEETING_USAGE
                        + "/" + ModelMediaClassifier.DOCUMENT_USAGE + ")"
                : "enabled (rules only - no model api key set)");
    }

    public Triage triageMeeting(MeetingClassificationInput input, String itemName) {
        return triage(itemName, properties.excludedMeetingCategories(), model::meetingModelConfigured,
                () -> rules.classifyMeeting(input), () -> model.classifyMeeting(input));
    }

    public Triage triageDocument(DocumentClassificationInput input) {
        if (!properties.enabled()) {
            return Triage.UNCLASSIFIED;
        }
        DocumentScan scan = documentScanner.scan(input.content(), input.filename());
        return triage(input.filename(), properties.excludedDocumentCategories(), model::documentModelConfigured,
                () -> rules.classifyDocument(scan), () -> model.classifyDocument(scan));
    }

    private Triage triage(String itemName, Set<? extends ItemCategory> excludedCategories,
                          BooleanSupplier modelConfigured,
                          Supplier<Optional<Classification>> ruleStep,
                          Supplier<Optional<Classification>> modelStep) {
        if (!properties.enabled()) {
            return Triage.UNCLASSIFIED;
        }
        try {
            Optional<Classification> classification = ruleStep.get();
            if (classification.isEmpty() && modelConfigured.getAsBoolean()) {
                classification = modelStep.get();
            }
            Triage triage = classification
                    .map(c -> new Triage(scopeFor(c, excludedCategories), c))
                    .orElse(Triage.UNCLASSIFIED);
            logOutcome(itemName, triage);
            return triage;
        } catch (LlmQuotaExhaustedException e) {
            log.warn("CLASSIFICATION_SKIPPED_QUOTA item={} retry_at={} - published INCLUDED and unclassified ({})",
                    itemName, e.retryAt(), e.getMessage());
            return Triage.UNCLASSIFIED;
        } catch (RuntimeException e) {
            log.warn("Classification failed for {} - landing it INCLUDED and unclassified", itemName, e);
            return Triage.UNCLASSIFIED;
        }
    }

    private ScopeDecision scopeFor(Classification classification, Set<? extends ItemCategory> excludedCategories) {
        Label<ItemCategory> category = classification.category();
        boolean excluded = excludedCategories.contains(category.value())
                && category.confidence() >= properties.minConfidenceToExclude();
        return excluded ? ScopeDecision.EXCLUDED : ScopeDecision.INCLUDED;
    }

    private static void logOutcome(String itemName, Triage triage) {
        Classification c = triage.classification();
        if (c == null) {
            log.info("{}: not classified - {}", itemName, triage.scopeDecision());
            return;
        }
        log.info("{}: {} by {} - category={} ({}), quality={}, sensitivity={}, reason=\"{}\"",
                itemName, triage.scopeDecision(), c.decidedBy(), c.category().value(),
                c.category().confidence(), c.quality().value(), c.sensitivity().value(), c.reason());
    }
}
