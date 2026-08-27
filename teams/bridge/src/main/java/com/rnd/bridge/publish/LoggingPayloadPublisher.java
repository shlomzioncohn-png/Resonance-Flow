package com.rnd.bridge.publish;

import com.rnd.bridge.core.BridgePayload;
import com.rnd.bridge.core.PayloadPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs each payload instead of publishing it anywhere. The stand-in that
 * lets a lane run end to end before Kafka exists.
 */
public class LoggingPayloadPublisher<O extends BridgePayload> implements PayloadPublisher<O> {

    private static final Logger log = LoggerFactory.getLogger(LoggingPayloadPublisher.class);

    @Override
    public void publish(O payload) {
        log.info("tenantId={} dedupKey={} payload={}", payload.tenantId(), payload.dedupKey(), payload);
    }
}
