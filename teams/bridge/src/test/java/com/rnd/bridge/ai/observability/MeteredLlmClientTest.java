package com.rnd.bridge.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rnd.bridge.ai.AiProperties;
import com.rnd.bridge.ai.LlmException;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MeteredLlmClientTest {

    private static final AiProperties.Model FREE = new AiProperties.Model("p", "free-model", false, true, null, null, null, null);
    private static final AiProperties.Model PAID = new AiProperties.Model("p", "paid-model", false, false, 0.75, 3.75, null, null);
    private static final AiProperties.Model PAID_UNPRICED = new AiProperties.Model("p", "x", false, false, null, null, null, null);
    private static final AiProperties.Monitoring LIMITS = new AiProperties.Monitoring(Duration.ofSeconds(30), 0.02);

    @Test
    void freeModelCostsZero() {
        assertThat(MeteredLlmClient.cost(FREE, 1000, 100)).isEqualTo(0.0);
        assertThat(MeteredLlmClient.cost(FREE, null, null)).isEqualTo(0.0);
    }

    @Test
    void paidModelIsPricedPerMillionTokens() {
        assertThat(MeteredLlmClient.cost(PAID, 1_000_000, 1_000_000)).isEqualTo(4.5);
    }

    @Test
    void unknownTokensOrPriceIsUnpricedNotZero() {
        assertThat(MeteredLlmClient.cost(PAID, null, 10)).isNull();
        assertThat(MeteredLlmClient.cost(PAID_UNPRICED, 10, 10)).isNull();
    }

    @Test
    void passesTheResponseThroughAndRethrowsFailuresUnchanged() {
        LlmResponse response = new LlmResponse("ok", "free-model", 1, 1);
        assertThat(new MeteredLlmClient(request -> response, "u", FREE, LIMITS)
                .complete(LlmRequest.text("s", "t"))).isSameAs(response);

        LlmException failure = new LlmException("boom", true);
        assertThatThrownBy(() -> new MeteredLlmClient(request -> { throw failure; }, "u", FREE, LIMITS)
                .complete(LlmRequest.text("s", "t"))).isSameAs(failure);
    }
}
