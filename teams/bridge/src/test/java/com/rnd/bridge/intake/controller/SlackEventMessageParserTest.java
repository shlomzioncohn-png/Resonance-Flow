package com.rnd.bridge.intake.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.config.SlackApiProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.parser.slack.SlackUserNameResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Attachment download (SlackEventMessageParser.downloadFile) isn't exercised here - it needs a
 * live HTTP endpoint, mirrors RealSlackSourceReader's own download logic, and every test below
 * uses an event with no "files" so that path is never reached.
 * userNameResolver is mocked and, unless a test overrides it, stubbed to pass the raw user id
 * straight through - this keeps every test below focused on parsing rather than resolution,
 * while resolvesTheSendersDisplayNameThroughTheSharedResolver/cachesAcrossMultipleEvents below
 * specifically exercise the resolver integration. SlackUserNameResolverTest covers the resolver's
 * own fallback-chain behavior in isolation.
 */
class SlackEventMessageParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SlackUserNameResolver userNameResolver = mock(SlackUserNameResolver.class);
    private final SlackEventMessageParser parser = new SlackEventMessageParser(
            new SlackApiProperties("xoxb-fake", "kamatech", List.of("C123"), "shh"), userNameResolver);

    SlackEventMessageParserTest() {
        when(userNameResolver.resolveUserName(any())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(0);
            return userId == null ? "unknown" : userId;
        });
    }

    private JsonNode event(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    void parsesAPlainNonThreadMessageIntoAChatMessage() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U1\",\"ts\":\"1000.100000\",\"text\":\"hi\"}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        ChatMessage message = result.orElseThrow();
        assertThat(message.chatId()).isEqualTo("C123");
        assertThat(message.sender()).isEqualTo("U1");
        assertThat(message.text()).isEqualTo("hi");
        assertThat(message.id()).isEqualTo("1000.100000");
        assertThat(message.threadId()).isNull();
        assertThat(message.timestamp()).isEqualTo(Instant.ofEpochMilli(1000100L));
        assertThat(message.attachments()).isEmpty();
    }

    @Test
    void parsesAThreadReplyWithItsThreadId() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U1\",\"ts\":\"1000.2\","
                        + "\"thread_ts\":\"1000.0\",\"text\":\"reply\"}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().threadId()).isEqualTo("1000.0");
    }

    @Test
    void missingUserFallsBackToUnknown() throws Exception {
        JsonNode event = event("{\"type\":\"message\",\"channel\":\"C123\",\"ts\":\"1000.1\",\"text\":\"hi\"}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().sender()).isEqualTo("unknown");
    }

    @Test
    void messageChangedWithNoNestedMessageIsUnsupported() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\","
                        + "\"ts\":\"1000.1\",\"text\":\"hi\"}");

        assertThat(parser.parse(event)).isEmpty();
    }

    @Test
    void messageDeletedIsUnsupported() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_deleted\",\"channel\":\"C123\","
                        + "\"ts\":\"1000.1\",\"deleted_ts\":\"1000.0\","
                        + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.0\",\"text\":\"bye\"}}");

        assertThat(parser.parse(event)).isEmpty();
    }

    @Test
    void messageChangedWithNestedTombstoneIsNotParsedAsAnEdit() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\",\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"subtype\":\"tombstone\",\"ts\":\"1000.0\","
                        + "\"text\":\"This message was deleted.\"}}");

        assertThat(parser.parse(event)).isEmpty();
    }

    @Test
    void messageChangedWithNestedBotMessageIsIgnoredEntirely() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\",\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"subtype\":\"bot_message\",\"ts\":\"1000.0\","
                        + "\"text\":\"unfurled link\"}}");

        assertThat(parser.parse(event)).isEmpty();
    }

    @Test
    void parseDeletionExtractsDeletedTsFromMessageDeleted() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_deleted\",\"channel\":\"C123\","
                        + "\"ts\":\"1000.9\",\"deleted_ts\":\"1000.0\","
                        + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.0\",\"text\":\"bye\"}}");

        Optional<com.rnd.bridge.intake.model.SlackMessageDeletion> result = parser.parseDeletion(event);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().chatId()).isEqualTo("C123");
        assertThat(result.orElseThrow().messageId()).isEqualTo("1000.0");
    }

    @Test
    void parseDeletionPrefersDeletedTsOverMismatchedPreviousMessageTs() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_deleted\",\"channel\":\"C123\","
                        + "\"ts\":\"1000.9\",\"deleted_ts\":\"1000.0\","
                        + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.1\",\"text\":\"bye\"}}");

        Optional<com.rnd.bridge.intake.model.SlackMessageDeletion> result = parser.parseDeletion(event);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().messageId()).isEqualTo("1000.0");
    }

    @Test
    void parseDeletionDetectsATombstoneNestedInMessageChanged() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\",\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"subtype\":\"tombstone\",\"ts\":\"1000.0\","
                        + "\"text\":\"This message was deleted.\"}}");

        Optional<com.rnd.bridge.intake.model.SlackMessageDeletion> result = parser.parseDeletion(event);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().chatId()).isEqualTo("C123");
        assertThat(result.orElseThrow().messageId()).isEqualTo("1000.0");
    }

    @Test
    void parseDeletionIgnoresABotMessageNestedInMessageChanged() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\",\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"subtype\":\"bot_message\",\"ts\":\"1000.0\","
                        + "\"text\":\"unfurled link\"}}");

        assertThat(parser.parseDeletion(event)).isEmpty();
    }

    @Test
    void parseDeletionIgnoresAnOrdinaryEdit() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\",\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.0\",\"text\":\"edited\"}}");

        assertThat(parser.parseDeletion(event)).isEmpty();
    }

    @Test
    void topLevelFileShareWithNoFilesIsRoutedAsAPlainMessage() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"file_share\",\"channel\":\"C123\",\"user\":\"U1\","
                        + "\"ts\":\"1000.100000\",\"text\":\"check this out\"}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        ChatMessage message = result.orElseThrow();
        assertThat(message.chatId()).isEqualTo("C123");
        assertThat(message.sender()).isEqualTo("U1");
        assertThat(message.text()).isEqualTo("check this out");
        assertThat(message.id()).isEqualTo("1000.100000");
        assertThat(message.threadId()).isNull();
        assertThat(message.attachments()).isEmpty();
    }

    @Test
    void topLevelFileShareThreadReplyIsRoutedWithItsThreadId() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"file_share\",\"channel\":\"C123\",\"user\":\"U1\","
                        + "\"ts\":\"1000.2\",\"thread_ts\":\"1000.0\",\"text\":\"here's the file\"}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().threadId()).isEqualTo("1000.0");
    }

    @Test
    void topLevelFileShareWithEmptyTextIsRouted() throws Exception {
        // Slack sends an empty "text" (not a missing field) when a file is shared with no
        // accompanying comment.
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"file_share\",\"channel\":\"C123\",\"user\":\"U1\","
                        + "\"ts\":\"1000.3\",\"text\":\"\"}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().text()).isEmpty();
    }

    @Test
    void topLevelFileShareWithFilesArrayIsRoutedThroughAttachmentResolution() throws Exception {
        // Verifies routing up to (but not through) the actual download: this parser has no
        // injectable download seam (see this class's header javadoc), so a real "files" entry
        // unavoidably reaches downloadFile's live HTTP call. Pointing url_private_download at
        // 127.0.0.1:1 (a port nothing listens on) fails fast with "connection refused" - no DNS
        // lookup, no real network dependency, no live Slack token - while still proving the
        // event was accepted, parsed into a Message, and its file list reached resolveAttachments.
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"file_share\",\"channel\":\"C123\",\"user\":\"U1\","
                        + "\"ts\":\"1000.4\",\"text\":\"check this out\",\"files\":[{\"id\":\"F1\","
                        + "\"name\":\"doc.pdf\",\"mimetype\":\"application/pdf\","
                        + "\"url_private_download\":\"http://127.0.0.1:1/doc.pdf\"}]}");

        assertThatThrownBy(() -> parser.parse(event))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void topLevelThreadBroadcastIsRoutedAsAnOrdinaryThreadReply() throws Exception {
        // "Also send to channel" on a thread reply - still just a thread reply (threadId set),
        // no channel-group handling here by design.
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"thread_broadcast\",\"channel\":\"C123\",\"user\":\"U1\","
                        + "\"ts\":\"1000.5\",\"thread_ts\":\"1000.0\",\"text\":\"heads up everyone\"}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        ChatMessage message = result.orElseThrow();
        assertThat(message.text()).isEqualTo("heads up everyone");
        assertThat(message.threadId()).isEqualTo("1000.0");
    }

    @Test
    void messageChangedWithNestedThreadBroadcastSubtypeIsParsedAsAnOrdinaryEdit() throws Exception {
        // A nested "thread_broadcast" subtype (editing a thread reply that broadcast to the
        // channel) isn't special-cased either - falls straight through to parseMessageNode, same
        // as an edit with no nested subtype. No code change was needed for this case; this test
        // documents that it already worked.
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\",\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"subtype\":\"thread_broadcast\",\"user\":\"U1\","
                        + "\"ts\":\"1000.0\",\"thread_ts\":\"999.0\",\"text\":\"edited broadcast reply\"}}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        ChatMessage message = result.orElseThrow();
        assertThat(message.id()).isEqualTo("1000.0");
        assertThat(message.threadId()).isEqualTo("999.0");
        assertThat(message.text()).isEqualTo("edited broadcast reply");
    }

    @Test
    void messageDeletedWithThreadBroadcastPreviousMessageIsStillAThreadReplyDeletion() throws Exception {
        // parseDeletion's message_deleted path never reads previous_message's own subtype or
        // thread_ts at all - identity comes solely from the top-level deleted_ts/channel fields
        // (see parseDeletion's javadoc) - so a thread_broadcast previous_message is handled
        // exactly like any other message_deleted event. This test documents current (already
        // correct) behavior; no code change was made to parseDeletion.
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_deleted\",\"channel\":\"C123\","
                        + "\"ts\":\"1000.9\",\"deleted_ts\":\"1000.0\","
                        + "\"previous_message\":{\"type\":\"message\",\"subtype\":\"thread_broadcast\",\"user\":\"U1\","
                        + "\"ts\":\"1000.0\",\"thread_ts\":\"999.0\",\"text\":\"heads up everyone\"}}");

        Optional<com.rnd.bridge.intake.model.SlackMessageDeletion> result = parser.parseDeletion(event);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().chatId()).isEqualTo("C123");
        assertThat(result.orElseThrow().messageId()).isEqualTo("1000.0");
    }

    @Test
    void messageChangedWithNestedFileShareSubtypeIsParsedAsAnOrdinaryEdit() throws Exception {
        // A nested "file_share" subtype (editing a message that has a file attached) isn't
        // special-cased - only "tombstone" and "bot_message" are filtered above - so this falls
        // straight through to parseMessageNode, same as an edit with no nested subtype at all.
        // No code change was needed for this case; this test documents that it already worked.
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\",\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"subtype\":\"file_share\",\"user\":\"U1\","
                        + "\"ts\":\"1000.0\",\"text\":\"edited caption\"}}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        ChatMessage message = result.orElseThrow();
        assertThat(message.id()).isEqualTo("1000.0");
        assertThat(message.text()).isEqualTo("edited caption");
    }

    @Test
    void otherSubtypesAreUnsupported() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"bot_message\",\"channel\":\"C123\","
                        + "\"ts\":\"1000.1\",\"text\":\"hi\"}");

        assertThat(parser.parse(event)).isEmpty();
    }

    @Test
    void messageChangedUsesTheOriginalMessagesStableTsAsTheChatMessageId() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\","
                        + "\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.0\","
                        + "\"text\":\"edited text\",\"edited\":{\"user\":\"U1\",\"ts\":\"1000.9\"}},"
                        + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.0\",\"text\":\"original text\"}}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        ChatMessage message = result.orElseThrow();
        assertThat(message.id()).isEqualTo("1000.0");
        assertThat(message.chatId()).isEqualTo("C123");
        assertThat(message.sender()).isEqualTo("U1");
        assertThat(message.text()).isEqualTo("edited text");
        assertThat(message.timestamp()).isEqualTo(Instant.ofEpochMilli(1000000L));
        assertThat(message.threadId()).isNull();
    }

    @Test
    void messageChangedOfAThreadReplyKeepsTheNestedThreadTs() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\","
                        + "\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.2\","
                        + "\"thread_ts\":\"1000.0\",\"text\":\"edited reply\"},"
                        + "\"previous_message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.2\","
                        + "\"thread_ts\":\"1000.0\",\"text\":\"reply\"}}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        ChatMessage message = result.orElseThrow();
        assertThat(message.id()).isEqualTo("1000.2");
        assertThat(message.threadId()).isEqualTo("1000.0");
        assertThat(message.text()).isEqualTo("edited reply");
    }

    @Test
    void messageChangedMissingTheNestedMessagesTsIsUnsupported() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\","
                        + "\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"user\":\"U1\",\"text\":\"edited text\"}}");

        assertThat(parser.parse(event)).isEmpty();
    }

    @Test
    void messageChangedMissingTheNestedMessagesTextIsUnsupported() throws Exception {
        JsonNode event = event(
                "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C123\","
                        + "\"ts\":\"1000.9\","
                        + "\"message\":{\"type\":\"message\",\"user\":\"U1\",\"ts\":\"1000.0\"}}");

        assertThat(parser.parse(event)).isEmpty();
    }

    @Test
    void nonMessageEventTypeIsUnsupported() throws Exception {
        JsonNode event = event("{\"type\":\"reaction_added\"}");

        assertThat(parser.parse(event)).isEmpty();
    }

    @Test
    void missingRequiredFieldsIsUnsupported() throws Exception {
        JsonNode event = event("{\"type\":\"message\",\"channel\":\"C123\"}"); // no ts/text

        assertThat(parser.parse(event)).isEmpty();
    }

    @Test
    void nullEventIsUnsupported() {
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void aRealSlackMessageWithRichTextBlocksStillParses() throws Exception {
        // Real Slack Events API "message" events always carry a "blocks" (Block Kit) array -
        // unlike every synthetic event above - whose elements are a polymorphic LayoutBlock
        // hierarchy this parser's plain Jackson mapper can't deserialize on its own; it must be
        // dropped rather than fought with, since this class never reads it anyway.
        JsonNode event = event(
                "{\"type\":\"message\",\"channel\":\"C0BLFAJK5GR\",\"channel_type\":\"group\","
                        + "\"user\":\"U0BC74M13RC\",\"ts\":\"1790090496.169599\",\"event_ts\":\"1790090496.169599\","
                        + "\"text\":\"hi whats up?\",\"team\":\"T123\","
                        + "\"blocks\":[{\"type\":\"rich_text\",\"block_id\":\"abc123\",\"elements\":["
                        + "{\"type\":\"rich_text_section\",\"elements\":["
                        + "{\"type\":\"text\",\"text\":\"hi whats up?\"}]}]}]}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        ChatMessage message = result.orElseThrow();
        assertThat(message.chatId()).isEqualTo("C0BLFAJK5GR");
        assertThat(message.sender()).isEqualTo("U0BC74M13RC");
        assertThat(message.text()).isEqualTo("hi whats up?");
        assertThat(message.id()).isEqualTo("1790090496.169599");
        assertThat(message.threadId()).isNull();
    }

    @Test
    void senderIsTheNameTheSharedResolverResolvesTheRawUserIdTo() throws Exception {
        when(userNameResolver.resolveUserName("U1")).thenReturn("Alice Example");
        JsonNode event = event(
                "{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U1\",\"ts\":\"1000.100000\",\"text\":\"hi\"}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().sender()).isEqualTo("Alice Example");
    }

    @Test
    void resolverIsCalledOncePerEventLettingItCacheAcrossEvents() throws Exception {
        when(userNameResolver.resolveUserName("U1")).thenReturn("Alice Example");
        JsonNode first = event(
                "{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U1\",\"ts\":\"1000.1\",\"text\":\"hi\"}");
        JsonNode second = event(
                "{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U1\",\"ts\":\"1000.2\",\"text\":\"hi again\"}");

        parser.parse(first);
        parser.parse(second);

        // The parser itself never caches - it delegates every call straight to the resolver,
        // which is the one shared cache reused by both this live path and the batch reader.
        verify(userNameResolver, times(2)).resolveUserName("U1");
    }

    @Test
    void senderFallsBackToTheRawUserIdWhenTheResolverCannotResolveIt() throws Exception {
        when(userNameResolver.resolveUserName("U9")).thenReturn("U9");
        JsonNode event = event(
                "{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U9\",\"ts\":\"1000.1\",\"text\":\"hi\"}");

        Optional<ChatMessage> result = parser.parse(event);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().sender()).isEqualTo("U9");
    }
}
