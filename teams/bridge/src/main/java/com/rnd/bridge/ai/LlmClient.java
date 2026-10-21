package com.rnd.bridge.ai;

/**
 * A chat model ready to call, with every bridge-wide rule (free-only, daily limit, PII redaction,
 * fallback, monitoring) already applied around it. Get one from {@link LlmClients#forUsage}.
 */
public interface LlmClient {

    /**
     * @throws LlmException when the call fails or is blocked - callers decide whether to fail
     *                      open (as media classification does) or propagate
     */
    LlmResponse complete(LlmRequest request);
}
