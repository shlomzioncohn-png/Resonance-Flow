package com.rnd.bridge.ai;

import java.util.List;
import java.util.Objects;

/**
 * One single-turn call to a chat model: a system prompt, the user's text, and optionally images
 * attached to the user message in order (PNG bytes). {@code maxOutputTokens} and
 * {@code reasoningEffort} are set by the chain from the model's bridge.ai settings
 * ({@link #withModelSettings}) - callers leave them null.
 */
public record LlmRequest(String systemPrompt, String userText, List<byte[]> pngImages,
                         Integer maxOutputTokens, String reasoningEffort) {

    public LlmRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userText, "userText");
        pngImages = pngImages == null ? List.of() : List.copyOf(pngImages);
    }

    public LlmRequest(String systemPrompt, String userText, List<byte[]> pngImages) {
        this(systemPrompt, userText, pngImages, null, null);
    }

    public static LlmRequest text(String systemPrompt, String userText) {
        return new LlmRequest(systemPrompt, userText, List.of());
    }

    public boolean hasImages() {
        return !pngImages.isEmpty();
    }

    public LlmRequest withUserText(String newUserText) {
        return new LlmRequest(systemPrompt, newUserText, pngImages, maxOutputTokens, reasoningEffort);
    }

    public LlmRequest withModelSettings(Integer newMaxOutputTokens, String newReasoningEffort) {
        return new LlmRequest(systemPrompt, userText, pngImages, newMaxOutputTokens, newReasoningEffort);
    }
}
