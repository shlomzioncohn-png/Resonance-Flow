package com.rnd.bridge.core;

import java.time.Instant;

public interface PayloadMapper<S, O extends BridgePayload> {

    O map(S source, Instant asOf);
}
