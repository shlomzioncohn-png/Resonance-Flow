package com.rnd.bridge.crm.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and validates the structure of a CRM-deal-enrichment model reply (see
 * {@code docs/crm-llm-enrichment-design.md} section 3/13). Pure code - no Spring, no
 * LLM client - so {@code CrmDealLlmEnricher} (and its cache) can stay thin around
 * this. Never throws for a malformed/invalid reply; every failure is a
 * {@link Result.Rejected} with a fixed {@link RejectionReason}, never the reply text
 * itself (same rule {@code com.rnd.bridge.ai} follows for its own error messages).
 * <p>
 * v1 validates structure only, not factual correctness - there is no date/number
 * grounding against the input. That was removed by a team-lead decision: regex-based
 * grounding does not prove correctness and can reject a good reply; see the design
 * doc section 3 for the rationale. The model is still instructed not to invent facts
 * (see {@link EnrichmentPromptBuilder}), and protected task fields (status, urgency,
 * deadline, sourceRef) stay under ETL control regardless of what the model returns.
 */
public final class EnrichmentReplyParser {

    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MIN_EFFORT_MINUTES = 5;
    private static final int MAX_EFFORT_MINUTES = 480;

    private static final ObjectMapper JSON = new ObjectMapper();

    // Whole-reply code fence only (leading/trailing whitespace allowed) - a fence around
    // part of the reply, or any other prose, is left alone and fails JSON parsing as-is;
    // LlmRequest has no JSON mode, so a fenced reply is a likely, not hypothetical, shape.
    private static final Pattern CODE_FENCE = Pattern.compile(
            "\\A```(?:json)?\\s*\\R?(.*?)\\R?```\\z", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    public enum RejectionReason {
        INVALID_JSON,
        MISSING_TITLE,
        TITLE_TOO_LONG,
        DESCRIPTION_TOO_LONG,
        EFFORT_OUT_OF_RANGE
    }

    public sealed interface Result {
        record Accepted(String title, String description, Integer effortEstimateMinutes) implements Result {
        }

        record Rejected(RejectionReason reason) implements Result {
        }
    }

    public Result parse(String rawReply) {
        JsonNode root = readJsonObject(rawReply);
        if (root == null) {
            return new Result.Rejected(RejectionReason.INVALID_JSON);
        }

        JsonNode titleNode = root.get("title");
        if (titleNode == null || !titleNode.isTextual()) {
            return new Result.Rejected(RejectionReason.MISSING_TITLE);
        }
        String title = titleNode.asText().trim();
        if (title.isEmpty()) {
            return new Result.Rejected(RejectionReason.MISSING_TITLE);
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return new Result.Rejected(RejectionReason.TITLE_TOO_LONG);
        }

        JsonNode descriptionNode = root.get("description");
        String description = null;
        if (descriptionNode != null && !descriptionNode.isNull()) {
            if (!descriptionNode.isTextual()) {
                return new Result.Rejected(RejectionReason.INVALID_JSON);
            }
            String trimmed = descriptionNode.asText().trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
                    return new Result.Rejected(RejectionReason.DESCRIPTION_TOO_LONG);
                }
                description = trimmed;
            }
        }

        JsonNode effortNode = root.get("effortEstimateMinutes");
        Integer effort = null;
        if (effortNode != null && !effortNode.isNull()) {
            if (!effortNode.isIntegralNumber()) {
                return new Result.Rejected(RejectionReason.EFFORT_OUT_OF_RANGE);
            }
            long value = effortNode.asLong();
            if (value < MIN_EFFORT_MINUTES || value > MAX_EFFORT_MINUTES) {
                return new Result.Rejected(RejectionReason.EFFORT_OUT_OF_RANGE);
            }
            effort = (int) value;
        }

        return new Result.Accepted(title, description, effort);
    }

    private static JsonNode readJsonObject(String rawReply) {
        if (rawReply == null) {
            return null;
        }
        String jsonText = stripCodeFence(rawReply);
        try {
            JsonNode root = JSON.readTree(jsonText);
            return root != null && root.isObject() ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripCodeFence(String reply) {
        String trimmed = reply.strip();
        Matcher m = CODE_FENCE.matcher(trimmed);
        return m.matches() ? m.group(1).strip() : trimmed;
    }
}
