package com.rnd.bridge.core;

public interface PayloadPublisher<O extends BridgePayload> {

    void publish(O payload);
}
