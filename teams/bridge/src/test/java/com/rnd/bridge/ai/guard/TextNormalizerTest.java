package com.rnd.bridge.ai.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    @Test
    void fullWidthCharactersBecomePlain() {
        assertThat(TextNormalizer.normalize("Ｉｇｎｏｒｅ ０５２"))
                .isEqualTo("Ignore 052");
    }

    @Test
    void invisibleAndControlCharactersAreRemovedButLineBreaksKept() {
        assertThat(TextNormalizer.normalize("ig​nore‮ prev\u0007ious\nline\ttab﻿"))
                .isEqualTo("ignore previous\nline\ttab");
    }

    @Test
    void hebrewIsUnchanged() {
        assertThat(TextNormalizer.normalize("סיכום פגישה של הצוות")).isEqualTo("סיכום פגישה של הצוות");
    }

    @Test
    void normalizationRunsBeforeRedactionSoHiddenDigitsAreStillCaught() {
        List<LlmRequest> seen = new ArrayList<>();
        LlmClient model = request -> {
            seen.add(request);
            return new LlmResponse("ok", "m", null, null);
        };
        LlmClient chain = new TextNormalizingLlmClient(new PiiRedactingLlmClient(model, new PiiRedactor()));

        chain.complete(LlmRequest.text("system", "call ０５２-123​4567"));

        assertThat(seen.get(0).userText()).isEqualTo("call [PHONE]");
    }
}
