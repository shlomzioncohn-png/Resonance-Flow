package com.rnd.bridge.raw.model;

/**
 * What kind of change a RawObjectEvent represents, alongside its revision (see RawObjectEvent's
 * javadoc). UPSERT covers both a genuinely new logical object and an edit of an existing one
 * (same correlationId, higher revision) - Mind doesn't need to tell them apart, since either
 * way the latest revision simply replaces whatever it had for that correlationId.
 * DELETE_REVIEW is reserved for a future soft-delete/tombstone signal (see Bridge issue #264)
 * and is not emitted by any lane yet - defined now so RawObjectEvent's shape doesn't need to
 * change again once delete handling lands.
 * Wire format is the enum name verbatim (uppercase snake_case), per Mind's convention for enums
 * on this contract (see ScopeDecision/Modality/RawSourceType).
 */
public enum ChangeType {
    UPSERT,
    DELETE_REVIEW
}
