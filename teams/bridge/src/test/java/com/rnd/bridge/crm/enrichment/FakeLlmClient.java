package com.rnd.bridge.crm.enrichment;

import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;

/** Test-only fake: returns a fixed reply, or throws a fixed exception, and counts calls. */
final class FakeLlmClient implements LlmClient {

    private final String replyText;
    private final RuntimeException toThrow;
    private int callCount = 0;

    private FakeLlmClient(String replyText, RuntimeException toThrow) {
        this.replyText = replyText;
        this.toThrow = toThrow;
    }

    static FakeLlmClient returning(String replyText) {
        return new FakeLlmClient(replyText, null);
    }

    static FakeLlmClient throwing(RuntimeException e) {
        return new FakeLlmClient(null, e);
    }

    @Override
    public synchronized LlmResponse complete(LlmRequest request) {
        callCount++;
        if (toThrow != null) {
            throw toThrow;
        }
        return new LlmResponse(replyText, "fake-model", 10, 5);
    }

    synchronized int callCount() {
        return callCount;
    }
}
