package com.rnd.bridge.intake.model;

import com.rnd.bridge.raw.model.RawObjectRef;

/**
 * One Slack attachment that has already been landed to MinIO during a non-thread session's
 * real-time arrival (see ChatGroupStateReconciler.reconcileNonThreadedMessage /
 * GroupStateStore.recordLandedAttachment) but not yet published to Kafka - publication only
 * happens once, for the whole session, when SlackSessionPublisher assembles every
 * LandedAttachment across the group's messages plus the freshly-landed transcript into one
 * RawObjectEvent. fileId is Slack's own stable per-file id (see ChatMessage.Attachment.fileId) -
 * kept alongside ref so a later edit/retry of the same message can recognize this exact file was
 * already landed and skip re-landing it (see SourceRecord.attachmentFileId's javadoc for why
 * fileId, not position, is the matching key).
 */
public record LandedAttachment(String fileId, RawObjectRef ref) {
}
