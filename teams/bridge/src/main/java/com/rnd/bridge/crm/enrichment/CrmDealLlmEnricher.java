package com.rnd.bridge.crm.enrichment;

import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmClients;
import com.rnd.bridge.ai.LlmException;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.task.enrichment.EnrichmentInput;
import com.rnd.bridge.task.enrichment.EnrichmentOutput;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import com.rnd.bridge.task.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * {@link TaskEnricher} for {@code CRM_DEAL} tasks: builds the prompt, calls Gemini
 * through the shared AI infrastructure, validates the reply, and merges it into an
 * {@link EnrichmentOutput} - urgency/deadline/confidence always stay null (design doc
 * section 3: the LLM never overrides those). Never throws - any LLM failure, quota
 * exhaustion, or invalid/rejected reply falls back to {@link EnrichmentOutput#empty()}
 * so CRM synchronization is never interrupted (design doc "Core Principle").
 * <p>
 * Caches via {@link EnrichmentReplyCache} - see that class for cache key, isolation,
 * eviction and single-flight behavior (design doc section 4).
 * <p>
 * Logs only {@code sourceRef} and a fixed category (rejection reason / exception
 * class name) on failure - never the model's reply or the deal's own text, same rule
 * {@code com.rnd.bridge.ai} follows for its own error messages.
 */
public final class CrmDealLlmEnricher implements TaskEnricher {

    public static final String USAGE = "crm-deal-enrichment";

    private static final String ORIGINAL_NOTES_HEADER = "\n\nOriginal CRM notes:\n";
    private static final String ORIGINAL_DEAL_LABEL = "Original CRM deal: ";

    private static final Logger log = LoggerFactory.getLogger(CrmDealLlmEnricher.class);

    private final LlmClient llmClient;
    private final EnrichmentPromptBuilder promptBuilder;
    private final EnrichmentReplyParser replyParser;
    private final EnrichmentReplyCache cache;
    private final CallRateLimiter rateLimiter;

    public CrmDealLlmEnricher(LlmClients llmClients, EnrichmentPromptBuilder promptBuilder,
                              EnrichmentReplyParser replyParser, Clock clock, Duration minCallInterval) {
        this(llmClients, promptBuilder, replyParser,
                new EnrichmentReplyCache(clock, () -> EnrichmentPromptBuilder.PROMPT_VERSION),
                new CallRateLimiter(minCallInterval, clock, CallRateLimiter.realSleeper()));
    }

    /** Test-only: lets a test control the cache and the call-rate limiter directly. */
    CrmDealLlmEnricher(LlmClients llmClients, EnrichmentPromptBuilder promptBuilder,
                       EnrichmentReplyParser replyParser, EnrichmentReplyCache cache, CallRateLimiter rateLimiter) {
        this.llmClient = llmClients.forUsage(USAGE);
        this.promptBuilder = promptBuilder;
        this.replyParser = replyParser;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public EnrichmentOutput enrich(EnrichmentInput input) {
        try {
            return doEnrich(input);
        } catch (Exception e) {
            log.warn("CRM deal enrichment failed unexpectedly sourceRef={} exception={}",
                    input.sourceRef(), e.getClass().getSimpleName());
            return EnrichmentOutput.empty();
        }
    }

    private EnrichmentOutput doEnrich(EnrichmentInput input) {
        if (isTerminal(input.status()) || isBlank(input.description())) {
            return EnrichmentOutput.empty();
        }

        return cache.getOrCompute(input.sourceRef(), input.title(), input.description(),
                input.status(), input.deadline(), () -> callModelAndParse(input));
    }

    private EnrichmentReplyCache.Outcome callModelAndParse(EnrichmentInput input) {
        rateLimiter.awaitTurn();
        LlmRequest request = promptBuilder.build(input);
        String replyText;
        try {
            replyText = llmClient.complete(request).text();
        } catch (LlmException e) {
            log.info("CRM deal enrichment call failed sourceRef={} exception={} retryable={}",
                    input.sourceRef(), e.getClass().getSimpleName(), e.retryable());
            return new EnrichmentReplyCache.Outcome.Transient(EnrichmentOutput.empty());
        }

        EnrichmentReplyParser.Result result = replyParser.parse(replyText);
        return switch (result) {
            case EnrichmentReplyParser.Result.Accepted accepted ->
                    new EnrichmentReplyCache.Outcome.Success(toOutput(accepted, input.title(), input.description()));
            case EnrichmentReplyParser.Result.Rejected rejected -> {
                log.info("CRM deal enrichment reply rejected sourceRef={} reason={}",
                        input.sourceRef(), rejected.reason());
                yield new EnrichmentReplyCache.Outcome.Rejected();
            }
        };
    }

    private static EnrichmentOutput toOutput(EnrichmentReplyParser.Result.Accepted accepted,
                                             String originalTitle, String originalDescription) {
        boolean modelDescriptionUsable = accepted.description() != null
                && !isDuplicateOfOriginal(accepted.description(), originalDescription);
        boolean titleChanged = !normalizeWhitespace(accepted.title()).equalsIgnoreCase(normalizeWhitespace(originalTitle));

        String description;
        if (!titleChanged) {
            // Unchanged behavior: the rewritten title still carries the deal name, so there's
            // nothing extra to preserve. null means "keep original unchanged" (AbstractTaskMapper).
            description = modelDescriptionUsable
                    ? accepted.description() + ORIGINAL_NOTES_HEADER + originalDescription
                    : null;
        } else {
            // The rewritten title dropped the deal name (company/deal identity) - a salesperson
            // must still be able to tell which deal this is, so the original title is preserved
            // in the description regardless of whether the model also gave a usable description.
            String originalNotes = originalDescription != null ? ORIGINAL_NOTES_HEADER + originalDescription : "";
            description = modelDescriptionUsable
                    ? accepted.description() + "\n\n" + ORIGINAL_DEAL_LABEL + originalTitle + originalNotes
                    : ORIGINAL_DEAL_LABEL + originalTitle + originalNotes;
        }
        return new EnrichmentOutput(null, null, accepted.title(), description, null, accepted.effortEstimateMinutes());
    }

    /**
     * True when the model's description adds nothing the salesperson doesn't already have:
     * it matches the original (ignoring whitespace differences), or the original already
     * contains it verbatim. Either way, prepending it would just duplicate the CRM notes.
     */
    private static boolean isDuplicateOfOriginal(String modelDescription, String originalDescription) {
        if (originalDescription == null) {
            return false;
        }
        String normalizedModel = normalizeWhitespace(modelDescription);
        String normalizedOriginal = normalizeWhitespace(originalDescription);
        return normalizedModel.equals(normalizedOriginal) || normalizedOriginal.contains(normalizedModel);
    }

    private static String normalizeWhitespace(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.DONE || status == TaskStatus.CANCELLED;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
