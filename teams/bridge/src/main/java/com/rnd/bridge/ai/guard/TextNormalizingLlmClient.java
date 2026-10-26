package com.rnd.bridge.ai.guard;

import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;

/**
 * Decorator that runs {@link TextNormalizer} over the user text before anything else in the chain
 * sees it - so PII redaction's patterns can't be dodged with full-width digits or zero-width
 * characters, and the model gets the same text a human reviewer would. Always on, for every usage.
 */
public class TextNormalizingLlmClient implements LlmClient {

    private final LlmClient delegate;

    public TextNormalizingLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return delegate.complete(request.withUserText(TextNormalizer.normalize(request.userText())));
    }
}
