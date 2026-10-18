package com.rnd.bridge.raw.model;

/**
 * How usable the landed file itself is - not how relevant its content is. An important meeting
 * with bad audio is {@link ScopeDecision#INCLUDED} + {@link #POOR}, never EXCLUDED for that
 * reason alone.
 */
public enum Quality {
    GOOD,
    POOR,
    UNREADABLE
}
