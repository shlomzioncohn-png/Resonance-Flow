package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.LandedAttachment;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.ChangeType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Builds the SourceRecords for one chat session/group: one for the transcript, plus one more
 * per attachment across every message in that session (a single message can carry several,
 * e.g. a real Slack "files" array) — all sharing the transcript's correlationId (see
 * SourceRecord's javadoc for why attachments get their own record instead of being folded
 * into the transcript's).
 * Two entry points, matching the two shapes ChatSessionGrouper/ChatGroupStateReconciler hand
 * it - a plain, never-persisted session ({@code toSourceRecords(List)}, used by the
 * stateless WhatsApp/fixture path - see ChatSessionGrouper.groupIntoSessions) and a persisted,
 * possibly-merged group ({@code toSourceRecords(GroupState, List)}, used by
 * ChatGroupStateReconciler for Slack). correlationId is never the group's own internal
 * identity (a Slack threadId, or Bridge's minted session UUID) - it's a UUID minted fresh here
 * for a stateless session, or GroupState.correlationId (itself minted once and carried forward
 * across edits/retries/thread-continuation - see GroupState's javadoc) for a persisted one.
 * Pure/stateless: never persists anything itself, just reads what it's given.
 */
@Service
public class ChatSourceRecordMapper {

    /** Filler for a carried-forward attachment record's rawContent (see SourceRecord.
     * precomputedRef's javadoc) - the object already exists in MinIO under precomputedRef's own
     * objectKey, so this value is never read by anything that lands or hashes content. */
    private static final byte[] EMPTY_CONTENT = new byte[0];

    public List<SourceRecord> toSourceRecords(List<ChatMessage> sessionMessages) {
        return toSourceRecords(sessionMessages, UUID.randomUUID().toString());
    }

    private List<SourceRecord> toSourceRecords(List<ChatMessage> sessionMessages, String correlationId) {
        ChatMessage first = sessionMessages.get(0);
        List<String> participants = sessionMessages.stream()
                .map(ChatMessage::sender)
                .distinct()
                .toList();
        String transcript = sessionMessages.stream()
                .map(m -> m.sender() + ": " + m.text())
                .collect(Collectors.joining("\n"));
        String naturalId = first.chatId() + "|" + first.timestamp();
        List<String> sessionMessageIds = sessionMessages.stream()
                .map(ChatMessage::id)
                .filter(id -> id != null)
                .toList();
        // Every message in this session is available right here in one pass, so the total is
        // always fully known before any record is built - unlike the persisted-group path below.
        int attachmentCount = sessionMessages.stream()
                .mapToInt(m -> m.attachments().size())
                .sum();

        List<SourceRecord> records = new ArrayList<>();
        records.add(new SourceRecord(
                first.sourceType(),
                first.tenantId(),
                naturalId,
                correlationId,
                first.timestamp(),
                String.join(", ", participants),
                participants,
                null,
                transcript,
                transcript.getBytes(StandardCharsets.UTF_8),
                null,
                null,
                0,
                attachmentCount,
                sessionMessageIds,
                0));

        int attachmentIndex = 0;
        for (ChatMessage message : sessionMessages) {
            for (ChatMessage.Attachment attachment : message.attachments()) {
                attachmentIndex++;
                List<String> ownerMessageId = message.id() == null ? List.of() : List.of(message.id());
                records.add(new SourceRecord(
                        message.sourceType(),
                        message.tenantId(),
                        naturalId + "|attachment-" + attachmentIndex,
                        correlationId,
                        message.timestamp(),
                        message.sender(),
                        List.of(message.sender()),
                        null,
                        null,
                        attachment.content(),
                        attachment.mimeType(),
                        attachment.filename(),
                        attachment.fileId(),
                        attachmentIndex,
                        attachmentCount,
                        ownerMessageId,
                        0,
                        ChangeType.UPSERT,
                        null,
                        null));
            }
        }
        return records;
    }

    /**
     * Builds SourceRecords for a group whose identity/content spans possibly more than this
     * one batch (see ChatGroupStateReconciler) — the transcript/participants/naturalId cover
     * every message ever accumulated into {@code mergedGroup} (old + new), but attachments are
     * only emitted for {@code newMessagesThisBatch}: a message read in an earlier run already
     * had its attachments landed then, so re-emitting them here would just duplicate them.
     * Uses mergedGroup.correlationId() as the wire-facing correlationId — the stable UUID Mind
     * receives — never mergedGroup.groupId() (Bridge's own internal identity, a threadId or
     * minted session UUID that's never sent downstream); see GroupState's javadoc. groupId IS
     * still carried onto each built SourceRecord's own groupId field, though - Bridge-internal
     * bookkeeping only (see SourceRecord's javadoc), letting UnstructuredIntakeLane.afterPublish
     * confirm the exact group these records came from without a reverse messageId-index lookup.
     * A message snapshot NOT in newMessagesThisBatch can still carry its own landedAttachments
     * (today: only a migrated thread root - see ChatGroupStateReconciler.newThreadGroupState) -
     * those are emitted here too, as precomputedRef attachment records (see SourceRecord's
     * javadoc), so a root's already-landed attachment is never silently dropped from the thread's
     * own logical object just because the root itself was never "incoming" for this group.
     */
    public List<SourceRecord> toSourceRecords(GroupState mergedGroup, List<ChatMessage> newMessagesThisBatch) {
        List<MessageSnapshot> allMessages = mergedGroup.messages();
        MessageSnapshot first = allMessages.get(0);
        List<String> participants = allMessages.stream()
                .map(MessageSnapshot::sender)
                .distinct()
                .toList();
        String transcript = allMessages.stream()
                .map(m -> m.sender() + ": " + m.text())
                .collect(Collectors.joining("\n"));
        String naturalId = mergedGroup.chatId() + "|" + first.timestamp();
        List<String> sessionMessageIds = allMessages.stream().map(MessageSnapshot::messageId).toList();
        // Summed from the persisted snapshots, not from newMessagesThisBatch: a message merged
        // in an earlier run (and never re-emitted as a SourceRecord since) still contributes its
        // attachmentCount here, because MessageSnapshot carries it forward across runs - see
        // MessageSnapshot's javadoc for why that's necessary.
        int attachmentCount = allMessages.stream()
                .mapToInt(MessageSnapshot::attachmentCount)
                .sum();

        List<SourceRecord> records = new ArrayList<>();
        records.add(new SourceRecord(
                mergedGroup.sourceType(),
                mergedGroup.tenantId(),
                naturalId,
                mergedGroup.correlationId(),
                first.timestamp(),
                String.join(", ", participants),
                participants,
                null,
                transcript,
                transcript.getBytes(StandardCharsets.UTF_8),
                null,
                null,
                0,
                attachmentCount,
                sessionMessageIds,
                mergedGroup.revision(),
                mergedGroup.groupId()));

        int attachmentIndex = 0;
        List<String> incomingMessageIds = newMessagesThisBatch.stream()
                .map(ChatMessage::id)
                .filter(id -> id != null)
                .toList();
        for (MessageSnapshot snapshot : allMessages) {
            if (incomingMessageIds.contains(snapshot.messageId())) {
                continue;
            }
            for (LandedAttachment landed : snapshot.landedAttachments()) {
                attachmentIndex++;
                records.add(new SourceRecord(
                        mergedGroup.sourceType(),
                        mergedGroup.tenantId(),
                        naturalId + "|attachment-" + attachmentIndex,
                        mergedGroup.correlationId(),
                        snapshot.timestamp(),
                        snapshot.sender(),
                        List.of(snapshot.sender()),
                        null,
                        null,
                        EMPTY_CONTENT,
                        landed.fileId(),
                        attachmentIndex,
                        attachmentCount,
                        List.of(snapshot.messageId()),
                        mergedGroup.revision(),
                        mergedGroup.groupId(),
                        landed.ref()));
            }
        }
        for (ChatMessage message : newMessagesThisBatch) {
            for (ChatMessage.Attachment attachment : message.attachments()) {
                attachmentIndex++;
                List<String> ownerMessageId = message.id() == null ? List.of() : List.of(message.id());
                records.add(new SourceRecord(
                        message.sourceType(),
                        message.tenantId(),
                        naturalId + "|attachment-" + attachmentIndex,
                        mergedGroup.correlationId(),
                        message.timestamp(),
                        message.sender(),
                        List.of(message.sender()),
                        null,
                        null,
                        attachment.content(),
                        attachment.mimeType(),
                        attachment.filename(),
                        attachment.fileId(),
                        attachmentIndex,
                        attachmentCount,
                        ownerMessageId,
                        mergedGroup.revision(),
                        mergedGroup.groupId()));
            }
        }
        return records;
    }
}
