package com.rnd.bridge.intake.model;

import com.rnd.bridge.raw.model.RawSourceType;

/**
 * A Slack "this message no longer exists" signal, parsed from either a top-level
 * {@code message_deleted} event (identity from {@code deleted_ts}) or a {@code message_changed}
 * event whose nested {@code message.subtype} is {@code "tombstone"} (identity from the nested
 * message's own {@code ts}, the root's original id, unchanged by the tombstone) - see
 * SlackEventMessageParser's javadoc for why these are the only two deletion shapes Slack sends
 * and why neither carries a {@code thread_ts}, so routing can't be decided from event shape the
 * way an ordinary edit's can (see ChatGroupStateReconciler.resolveCurrentGroupId).
 */
public record SlackMessageDeletion(RawSourceType sourceType, String tenantId, String chatId, String messageId) {
}
