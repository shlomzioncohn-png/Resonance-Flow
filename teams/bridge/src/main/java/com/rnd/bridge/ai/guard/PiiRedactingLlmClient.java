package com.rnd.bridge.ai.guard;

import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;

/**
 * Decorator that runs {@link PiiRedactor} over the user text before it leaves Bridge. Only what is
 * sent to the model changes - the caller's own data (and anything it publishes) is untouched.
 * The system prompt is Bridge's own text and is left alone; images can't be redacted this way.
 */
public class PiiRedactingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final PiiRedactor redactor;

    public PiiRedactingLlmClient(LlmClient delegate, PiiRedactor redactor) {
        this.delegate = delegate;
        this.redactor = redactor;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return delegate.complete(request.withUserText(redactor.redact(request.userText())));
    }
}
