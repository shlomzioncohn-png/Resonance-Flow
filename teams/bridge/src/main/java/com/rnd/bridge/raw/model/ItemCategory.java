package com.rnd.bridge.raw.model;

import java.util.Set;

/**
 * What kind of item a {@link RawObjectEvent} is, from its {@link Classification}. One
 * implementation per kind of item, since the meaningful kinds differ (a meeting is a standup or a
 * customer call; a scanned doc is an invoice or a letter) - a single wire field, {@code category},
 * with the event's own source_type saying which value set applies. One value set can serve several
 * source types (every meeting platform shares MeetingCategory). Serialized as the enum constant's
 * name.
 */
public sealed interface ItemCategory permits MeetingCategory, DocumentCategory {

    /** The source types this category's values are valid for - checked by RawObjectValidator. */
    Set<RawSourceType> sourceTypes();

    /** The wire value - the enum constant's name (every implementation is an enum). */
    String name();
}
