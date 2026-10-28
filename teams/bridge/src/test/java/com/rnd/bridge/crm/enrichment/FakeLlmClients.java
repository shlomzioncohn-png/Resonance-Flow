package com.rnd.bridge.crm.enrichment;

import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmClients;

/** Test-only fake: always hands back the one configured {@link FakeLlmClient}, for any usage. */
final class FakeLlmClients implements LlmClients {

    private final FakeLlmClient client;

    FakeLlmClients(FakeLlmClient client) {
        this.client = client;
    }

    @Override
    public LlmClient forUsage(String usage) {
        return client;
    }

    @Override
    public boolean isConfigured(String usage) {
        return true;
    }
}
