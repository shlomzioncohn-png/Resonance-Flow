package com.rnd.bridge.ai.provider;

import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;

/**
 * One provider endpoint (bridge.ai.providers.&lt;name&gt;) - the Strategy behind every model call.
 * One implementation per wire format; which one a provider uses is its {@code type} setting.
 */
public interface LlmProvider {

    /** Whether an API key is set - without one, nothing is ever sent. */
    boolean configured();

    /**
     * @param modelName the provider's own model id
     * @throws com.rnd.bridge.ai.LlmException on any failure, after this provider's own retries
     */
    LlmResponse complete(String modelName, LlmRequest request);
}
