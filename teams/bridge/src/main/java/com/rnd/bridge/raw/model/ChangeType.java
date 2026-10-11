package com.rnd.bridge.raw.model;

/**
 * Whether a {@link RawObjectEvent} is a new/updated logical item (always UPSERT today - both
 * lanes only ever create or overwrite, never delete) or a source-side deletion that needs a
 * human/Mind decision rather than an automatic delete. No lane publishes DELETE_REVIEW yet;
 * it exists so Mind's consumer has a stable place to add that handling later without another
 * contract change.
 */
public enum ChangeType {
    UPSERT,
    DELETE_REVIEW
}
