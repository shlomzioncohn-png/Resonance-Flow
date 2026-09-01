package com.rnd.bridge.intake.parser.slack;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rnd.bridge.intake.config.SlackApiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RealSlackSourceReader needs a bot token and at least one channel id before it can call the
 * Slack Web API at all - both are checked eagerly in the constructor (unlike
 * RealWhatsAppSourceReader, which defers its checks to read()) because building the
 * MethodsClient itself requires the token up front.
 */
class RealSlackSourceReaderTest {

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
}
