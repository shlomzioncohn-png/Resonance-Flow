package com.rnd.bridge.intake.parser.slack;

import com.rnd.bridge.intake.config.SlackApiProperties;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.User;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves a Slack user id (Uxxxxxxxx) to a display name via the Web API's users.info, shared by
 * both {@code RealSlackSourceReader} (batch/poll) and {@code SlackEventMessageParser} (live
 * Events API) so the two paths reuse the same lookups and the same cache instead of each paying
 * for a users.info call on the same person. A single Spring-managed instance backs both callers.
 * ids are permanent, display names aren't, so Slack requires this separate lookup rather than
 * returning a name inline with a message. Cached for the lifetime of this component since the
 * same handful of people post repeatedly; there's no invalidation since a user could rename
 * between lookups, matching the previous per-reader cache's behavior.
 */
@Component
public class SlackUserNameResolver {

    private static final Logger log = LoggerFactory.getLogger(SlackUserNameResolver.class);

    private final MethodsClient methods;
    private final Map<String, String> userNameCache = new ConcurrentHashMap<>();

    @Autowired
    public SlackUserNameResolver(SlackApiProperties properties) {
        this(Slack.getInstance().methods(properties.botToken()));
    }

    /** Test-only seam: lets tests inject a mocked MethodsClient instead of the real one built
     * from a live bot token. */
    SlackUserNameResolver(MethodsClient methods) {
        this.methods = methods;
    }

    // Falls back to the raw id (userId itself, or "unknown" if even that is missing - e.g. a
    // bot-posted message) rather than failing the whole read over one lookup, matching how the
    // rest of the callers treat a single bad item.
    public String resolveUserName(String userId) {
        if (userId == null) {
            return "unknown";
        }
        return userNameCache.computeIfAbsent(userId, this::lookupUserName);
    }

    private String lookupUserName(String userId) {
        try {
            UsersInfoResponse response =
                    methods.usersInfo(UsersInfoRequest.builder().user(userId).build());
            if (!response.isOk() || response.getUser() == null) {
                log.warn("Slack users.info failed for user {}: {}", userId, response.getError());
                return userId;
            }
            String resolved = displayName(response.getUser()).orElse(userId);
            log.debug("Resolved Slack user {} to {}", userId, resolved);
            return resolved;
        } catch (IOException | SlackApiException e) {
            log.warn("Slack users.info threw for user {}", userId, e);
            return userId;
        }
    }

    private Optional<String> displayName(User user) {
        if (user.getProfile() != null && notBlank(user.getProfile().getRealName())) {
            return Optional.of(user.getProfile().getRealName());
        }
        if (notBlank(user.getRealName())) {
            return Optional.of(user.getRealName());
        }
        return notBlank(user.getName()) ? Optional.of(user.getName()) : Optional.empty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

