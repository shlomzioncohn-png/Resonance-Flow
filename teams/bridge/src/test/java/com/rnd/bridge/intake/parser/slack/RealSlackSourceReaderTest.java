package com.rnd.bridge.intake.parser.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rnd.bridge.intake.config.SlackApiProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.slack.api.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RealSlackSourceReader needs a bot token and at least one channel id before it can call the
 * Slack Web API at all - both are checked eagerly in the constructor (unlike
 * RealWhatsAppSourceReader, which defers its checks to read()) because building the
 * MethodsClient itself requires the token up front.
 * toChatMessage()/hasReplies() are exercised directly against plain Message POJOs (no mocking
 * library in this project) rather than through read(), which would need a real/mocked Slack
 * network client - user is left unset on every test Message so resolveUserName() takes its
 * null-user "unknown" fallback instead of calling users.info over the network.
 */
class RealSlackSourceReaderTest {

    private final RealSlackSourceReader reader =
            new RealSlackSourceReader(new SlackApiProperties("xoxb-fake", "kamatech", List.of("C123")));

    @Test
    void failsFastWhenBotTokenIsNotConfigured() {
        assertThatThrownBy(() -> new RealSlackSourceReader(
                        new SlackApiProperties(null, "kamatech", List.of("C123"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bot-token");
    }

    @Test
    void failsFastWhenNoChannelsAreConfigured() {
        assertThatThrownBy(() -> new RealSlackSourceReader(
                        new SlackApiProperties("xoxb-fake", "kamatech", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channels");
    }

    private Message message(String ts, String threadTs, Integer replyCount, String text) {
        Message message = new Message();
        message.setTs(ts);
        message.setThreadTs(threadTs);
        message.setReplyCount(replyCount);
        message.setText(text);
        return message;
    }

    @Test
    void hasRepliesIsTrueOnlyWhenReplyCountIsPositive() {
        assertThat(reader.hasReplies(message("1.1", null, 3, "root"))).isTrue();
        assertThat(reader.hasReplies(message("1.1", null, 0, "root"))).isFalse();
        assertThat(reader.hasReplies(message("1.1", null, null, "root"))).isFalse();
    }

    @Test
    void toChatMessagePopulatesIdFromTsAndCarriesThePassedThreadId() {
        ChatMessage chatMessage = reader.toChatMessage("C123", message("1700000000.000100", null, null, "hi"), null);

        assertThat(chatMessage.id()).isEqualTo("1700000000.000100");
        assertThat(chatMessage.threadId()).isNull();
        assertThat(chatMessage.text()).isEqualTo("hi");
        assertThat(chatMessage.sender()).isEqualTo("unknown");
    }

    @Test
    void toChatMessageCarriesAnExplicitThreadIdForRepliesAndRoots() {
        ChatMessage root = reader.toChatMessage(
                "C123", message("1700000000.000100", "1700000000.000100", 2, "root question"),
                "1700000000.000100");
        ChatMessage reply = reader.toChatMessage(
                "C123", message("1700000000.000200", "1700000000.000100", null, "a reply"),
                "1700000000.000100");

        assertThat(root.threadId()).isEqualTo(reply.threadId()).isEqualTo("1700000000.000100");
        assertThat(root.id()).isNotEqualTo(reply.id());
    }
}
