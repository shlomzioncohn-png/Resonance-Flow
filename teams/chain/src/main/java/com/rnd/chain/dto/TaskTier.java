package com.rnd.chain.dto;

/** Inbox's task tier; absence is modeled as a null {@code Task.tier}, not an enum value. */
public enum TaskTier {
    OPERATIONAL,
    TACTICAL,
    STRATEGIC
}
