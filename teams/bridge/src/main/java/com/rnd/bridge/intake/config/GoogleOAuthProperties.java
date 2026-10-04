package com.rnd.bridge.intake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for GoogleCredentialsProvider, shared by every real Google reader (Calendar/Gmail/
 * Drive). Personal Gmail accounts can't use service-account domain-wide delegation, so this
 * backs the installed-app OAuth2 user-consent flow instead: clientSecretsPath points at the
 * OAuth Client ID JSON downloaded from Google Cloud Console (Desktop app type), tokensDirectory
 * is where the resulting refresh token gets cached after the one-time browser consent, so later
 * runs don't need a browser at all. Both live outside the repo (see .gitignore) - same handling
 * as the Slack bot token.
 */
@ConfigurationProperties(prefix = "bridge.intake.google")
public record GoogleOAuthProperties(String clientSecretsPath, String tokensDirectory) {
}
