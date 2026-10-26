package com.rnd.bridge.intake.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for RealSlackSourceReader. botToken is the Bot User OAuth Token (xoxb-...) obtained by
 * installing a Slack app with channels:history/files:read scopes — see plan.md. channels is the
 * explicit list of channel IDs to read (the bot must be invited to each one first); Slack has no
 * "read every channel" mode without a much broader scope, so this mirrors the fixture reader's
 * one-channel-at-a-time shape instead of trying to auto-discover channels.
 *
 * signingSecret is the Slack app's Signing Secret (from the app's Basic Information page),
 * used by SlackSignatureVerifier to authenticate inbound Events API requests — unrelated to
 * botToken, which authenticates outbound calls to Slack's Web API. Only read once the Events
 * API endpoint is in use; the polling reader above never touches it.
 */
@ConfigurationProperties(prefix = "bridge.intake.slack")
public record SlackApiProperties(
        String botToken, List<String> channels,
        String signingSecret) {
}
