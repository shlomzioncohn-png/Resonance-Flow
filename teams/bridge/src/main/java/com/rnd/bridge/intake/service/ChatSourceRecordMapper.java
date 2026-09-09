package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
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
                        attachmentIndex,
                        ownerMessageId,
                        0));
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
     * Uses mergedGroup.correlationId() — the stable UUID Mind receives — not mergedGroup.groupId()
     * (Bridge's own internal identity, a threadId or minted session UUID that's never sent
     * downstream); see GroupState's javadoc.
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
                sessionMessageIds,
                mergedGroup.revision()));

        int attachmentIndex = 0;
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
                        attachmentIndex,
                        ownerMessageId,
                        mergedGroup.revision()));
            }
        }
        return records;
    }
}
