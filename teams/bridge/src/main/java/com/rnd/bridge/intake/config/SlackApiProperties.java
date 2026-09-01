package com.rnd.bridge.intake.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Config for RealSlackSourceReader. botToken is the Bot User OAuth Token (xoxb-...) obtained by
 * installing a Slack app with channels:history/files:read scopes — see plan.md. channels is the
 * explicit list of channel IDs to read (the bot must be invited to each one first); Slack has no
 * "read every channel" mode without a much broader scope, so this mirrors the fixture reader's
 * one-channel-at-a-time shape instead of trying to auto-discover channels.
 */
@ConfigurationProperties(prefix = "bridge.intake.slack")
public record SlackApiProperties(
        String botToken, @DefaultValue("kamatech") String tenantId, List<String> channels) {
}
