package com.rnd.bridge.ai.provider;

import com.rnd.bridge.ai.AiProperties;
import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmException;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;

/**
 * One model from bridge.ai.models bound to its provider - the innermost link of every chain.
 * Refuses a request with images when the model doesn't accept them, before anything is sent, and
 * applies the model's max-output-tokens and reasoning-effort.
 */
public class ModelLlmClient implements LlmClient {

    private final String modelKey;
    private final AiProperties.Model model;
    private final LlmProvider provider;

    public ModelLlmClient(String modelKey, AiProperties.Model model, LlmProvider provider) {
        this.modelKey = modelKey;
        this.model = model;
        this.provider = provider;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (request.hasImages() && !model.images()) {
            throw new LlmException("Model '" + modelKey + "' does not accept images", false);
        }
        return provider.complete(model.name(), request.withModelSettings(model.maxOutputTokens(), model.reasoningEffort()));
    }
}
