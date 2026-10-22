package com.rnd.bridge.ai.guard;

import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmException;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator over a usage's models in order: the primary first, then each fallback - moving on
 * only when the failure was transient (timeout, rate limit, 5xx, quota). A bad key, a bad request
 * or a model that can't take this request's images ends the call at once: the next model would
 * fail the same way, or should not be silently swapped in.
 * <p>
 * Never reaches a paid model while bridge.ai.free-only is on: LlmClientRegistry refuses to start
 * if any listed model isn't free.
 */
public class FallbackLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackLlmClient.class);

    private final String usage;
    private final List<LlmClient> inOrder;

    public FallbackLlmClient(String usage, List<LlmClient> inOrder) {
        if (inOrder.isEmpty()) {
            throw new IllegalArgumentException("A usage needs at least one model");
        }
        this.usage = usage;
        this.inOrder = List.copyOf(inOrder);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        for (int i = 0; ; i++) {
            try {
                return inOrder.get(i).complete(request);
            } catch (LlmException e) {
                boolean last = i == inOrder.size() - 1;
                if (last || !e.retryable()) {
                    throw e;
                }
                log.warn("{}: model {} of {} failed ({}) - falling back to the next",
                        usage, i + 1, inOrder.size(), e.getMessage());
            }
        }
    }
}
