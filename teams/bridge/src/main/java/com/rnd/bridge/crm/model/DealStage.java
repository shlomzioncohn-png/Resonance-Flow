package com.rnd.bridge.crm.model;

public enum DealStage {

    PROSPECT,
    QUALIFIED,
    PROPOSAL,
    NEGOTIATION,
    CLOSED_WON,
    CLOSED_LOST,
    ON_HOLD;

    public boolean isClosed() {
        return this == CLOSED_WON || this == CLOSED_LOST;
    }
}