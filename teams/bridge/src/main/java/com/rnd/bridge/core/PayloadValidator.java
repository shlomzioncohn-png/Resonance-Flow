package com.rnd.bridge.core;

public interface PayloadValidator<O extends BridgePayload> {

    void validate(O payload);
}
