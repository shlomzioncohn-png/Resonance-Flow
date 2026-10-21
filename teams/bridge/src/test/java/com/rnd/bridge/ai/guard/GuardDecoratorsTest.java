package com.rnd.bridge.ai.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rnd.bridge.ai.LlmQuotaExhaustedException;
import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmException;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuardDecoratorsTest {

    private static final LlmRequest REQUEST = LlmRequest.text("system", "call 052-1234567 about the sprint");

    @Test
    void redactionChangesOnlyTheTextSentToTheModel() {
        FakeModel model = FakeModel.answering("m");

        new PiiRedactingLlmClient(model, new PiiRedactor()).complete(REQUEST);

        assertThat(model.seen.get(0).userText()).isEqualTo("call [PHONE] about the sprint");
        assertThat(model.seen.get(0).systemPrompt()).isEqualTo("system");
        assertThat(REQUEST.userText()).contains("052-1234567");
    }

    @Test
    void fallbackMovesOnOnlyAfterATransientFailure() {
        FakeModel primary = FakeModel.failing(new LlmException("503", true));
        FakeModel backup = FakeModel.answering("backup");

        LlmResponse reply = new FallbackLlmClient("u", List.of(primary, backup)).complete(REQUEST);

        assertThat(reply.model()).isEqualTo("backup");
    }

    @Test
    void fallbackStopsAtANonTransientFailure() {
        FakeModel primary = FakeModel.failing(new LlmException("401 bad key", false));
        FakeModel backup = FakeModel.answering("backup");

        assertThatThrownBy(() -> new FallbackLlmClient("u", List.of(primary, backup)).complete(REQUEST))
                .hasMessageContaining("401");
        assertThat(backup.seen).isEmpty();
    }

    @Test
    void fallbackRethrowsTheLastFailureWhenAllModelsFail() {
        FakeModel primary = FakeModel.failing(new LlmException("503", true));
        FakeModel backup = FakeModel.failing(new LlmException("429", true));

        assertThatThrownBy(() -> new FallbackLlmClient("u", List.of(primary, backup)).complete(REQUEST))
                .hasMessage("429");
    }

    @Test
    void dailyLimitBlocksBeforeSendingAndResetsAtUtcMidnight() {
        FakeModel model = FakeModel.answering("m");
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T23:00:00Z"));
        DailyCallLimitLlmClient limited = new DailyCallLimitLlmClient(model, "u", 2, clock);

        limited.complete(REQUEST);
        limited.complete(REQUEST);
        assertThatThrownBy(() -> limited.complete(REQUEST))
                .isInstanceOfSatisfying(LlmQuotaExhaustedException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.retryAt()).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));
                });
        assertThat(model.seen).hasSize(2);

        clock.now = Instant.parse("2026-10-21T00:00:01Z");
        limited.complete(REQUEST);
        assertThat(model.seen).hasSize(3);
    }

    private static final class FakeModel implements LlmClient {
        final List<LlmRequest> seen = new ArrayList<>();
        final String name;
        final LlmException failure;

        private FakeModel(String name, LlmException failure) {
            this.name = name;
            this.failure = failure;
        }

        static FakeModel answering(String name) {
            return new FakeModel(name, null);
        }

        static FakeModel failing(LlmException failure) {
            return new FakeModel("failing", failure);
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            seen.add(request);
            if (failure != null) {
                throw failure;
            }
            return new LlmResponse("ok", name, 1, 1);
        }
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
