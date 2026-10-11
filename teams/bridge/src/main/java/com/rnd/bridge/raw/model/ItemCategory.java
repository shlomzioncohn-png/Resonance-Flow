package com.rnd.bridge.raw.model;

/**
 * What kind of item a {@link RawObjectEvent} is, from its {@link Classification}. One
 * implementation per source type, since the meaningful kinds differ per source (a meeting is a
 * standup or a customer call; a scanned doc is an invoice or a letter) - a single wire field,
 * {@code category}, with the event's own source_type saying which value set applies. Serialized
 * as the enum constant's name.
 */
public sealed interface ItemCategory permits MeetingCategory, DocumentCategory {

    /** The one source type this category's values are valid for - checked by RawObjectValidator. */
    RawSourceType sourceType();
}
