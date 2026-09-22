package com.rnd.bridge.intake.parser.slack;

import com.rnd.bridge.intake.config.SlackApiProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.raw.model.RawSourceType;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest;
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.File;
import com.slack.api.model.Message;
import com.slack.api.model.User;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads real Slack messages via the Web API's conversations.history, one call per configured
 * channel ID with cursor-based pagination (see plan.md) — the bot must already be a member of
 * every channel listed in bridge.intake.slack.channels. File attachments aren't included in the
 * history payload itself; conversations.history only returns metadata (name/mimetype/a private
 * download URL), so bytes are fetched with a second authenticated GET per file, mirroring the
 * same "metadata now, content later" split RealEmailSourceReader has for Gmail attachments.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.slack", name = "mode", havingValue = "real")
public class RealSlackSourceReader implements SlackSourceReader {

    private final SlackApiProperties properties;
    private final MethodsClient methods;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    // conversations.history only ever returns the sender as a user id (Uxxxxxxxx) - ids are
    // permanent, display names aren't, so Slack makes you look names up separately via
    // users.info. Cached per read() call since the same handful of people post repeatedly in
    // a channel; there's no cross-run cache since a user could rename between polls.
    private final Map<String, String> userNameCache = new ConcurrentHashMap<>();

    public RealSlackSourceReader(SlackApiProperties properties) {
        if (properties.botToken() == null || properties.botToken().isBlank()) {
            throw new IllegalStateException(
                    "bridge.intake.slack.bot-token must be set when bridge.intake.slack.mode=real");
        }
        if (properties.channels() == null || properties.channels().isEmpty()) {
            throw new IllegalStateException(
                    "bridge.intake.slack.channels must list at least one channel id when bridge.intake.slack.mode=real");
        }
        this.properties = properties;
        this.methods = Slack.getInstance().methods(properties.botToken());
    }

    @Override
    public List<ChatMessage> read(Instant since) {
        List<ChatMessage> messages = new ArrayList<>();
        for (String channelId : properties.channels()) {
            messages.addAll(readChannel(channelId, since));
        }
        // A broadcast reply ("also send to channel") appears in both conversations.history
        // and conversations.replies - keep the first occurrence per id, drop the duplicate.
        Map<String, ChatMessage> byId = new LinkedHashMap<>();
        for (ChatMessage message : messages) {
            byId.putIfAbsent(message.id(), message);
        }
        return new ArrayList<>(byId.values());
    }

    private List<ChatMessage> readChannel(String channelId, Instant since) {
        List<ChatMessage> messages = new ArrayList<>();
        String cursor = null;
        do {
            ConversationsHistoryResponse response = call(channelId, cursor, since);
            for (Message message : response.getMessages()) {
                // conversations.history never returns thread replies (broadcast replies are
                // the one exception) - a message with reply_count > 0 is a thread root, and
                // its replies have to be fetched separately via conversations.replies.
                String threadId = hasReplies(message) ? message.getTs() : message.getThreadTs();
                messages.add(toChatMessage(channelId, message, threadId));
                if (hasReplies(message)) {
                    messages.addAll(readReplies(channelId, message.getTs()));
                }
            }
            cursor = response.getResponseMetadata() == null ? null : response.getResponseMetadata().getNextCursor();
        } while (cursor != null && !cursor.isBlank());
        return messages;
    }

    // Package-visible (not private) so RealSlackSourceReaderTest can exercise the id/threadId
    // mapping directly with plain Message POJOs, without mocking the Slack network client.
    boolean hasReplies(Message message) {
        return message.getReplyCount() != null && message.getReplyCount() > 0;
    }

    // The root message comes back as the first entry of conversations.replies too - skipped
    // here since readChannel() already added it from conversations.history.
    private List<ChatMessage> readReplies(String channelId, String rootTs) {
        List<ChatMessage> replies = new ArrayList<>();
        String cursor = null;
        boolean first = true;
        do {
            ConversationsRepliesResponse response = callReplies(channelId, rootTs, cursor);
            for (Message message : response.getMessages()) {
                if (first) {
                    first = false;
                    continue;
                }
                replies.add(toChatMessage(channelId, message, rootTs));
            }
            cursor = response.getResponseMetadata() == null ? null : response.getResponseMetadata().getNextCursor();
        } while (cursor != null && !cursor.isBlank());
        return replies;
    }

    private ConversationsRepliesResponse callReplies(String channelId, String rootTs, String cursor) {
        try {
            ConversationsRepliesResponse response = methods.conversationsReplies(ConversationsRepliesRequest
                    .builder()
                    .channel(channelId)
                    .ts(rootTs)
                    .cursor(cursor)
                    .limit(200)
                    .build());
            if (!response.isOk()) {
                throw new IllegalStateException(
                        "Slack conversations.replies failed for channel " + channelId + " ts " + rootTs
                                + ": " + response.getError());
            }
            return response;
        } catch (IOException | SlackApiException e) {
            throw new IllegalStateException(
                    "Slack conversations.replies call failed for channel " + channelId + " ts " + rootTs, e);
        }
    }

    private ConversationsHistoryResponse call(String channelId, String cursor, Instant since) {
        try {
            ConversationsHistoryRequest.ConversationsHistoryRequestBuilder request = ConversationsHistoryRequest
                    .builder()
                    .channel(channelId)
                    .cursor(cursor)
                    .limit(200);
            // Slack's "oldest" takes a Unix timestamp string and returns only messages posted
            // after it - null (ReadCursor.full()) omits it entirely, which reads all history.
            if (since != null) {
                request.oldest(String.valueOf(since.getEpochSecond()));
            }
            ConversationsHistoryResponse response = methods.conversationsHistory(request.build());
            if (!response.isOk()) {
                throw new IllegalStateException(
                        "Slack conversations.history failed for channel " + channelId + ": " + response.getError());
            }
            return response;
        } catch (IOException | SlackApiException e) {
            throw new IllegalStateException("Slack conversations.history call failed for channel " + channelId, e);
        }
    }

    ChatMessage toChatMessage(String channelId, Message message, String threadId) {
        long epochMillis = Math.round(Double.parseDouble(message.getTs()) * 1000);
        return new ChatMessage(
                RawSourceType.SLACK,
                properties.tenantId(),
                channelId,
                resolveUserName(message.getUser()),
                Instant.ofEpochMilli(epochMillis),
                message.getText(),
                resolveAttachments(message.getFiles()),
                message.getTs(),
                threadId);
    }

    // Falls back to the raw id (userId itself, or "unknown" if even that is missing - e.g. a
    // bot-posted message) rather than failing the whole read over one lookup, matching how the
    // rest of this reader treats a single bad item.
    private String resolveUserName(String userId) {
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
                return userId;
            }
            return displayName(response.getUser()).orElse(userId);
        } catch (IOException | SlackApiException e) {
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

    private List<ChatMessage.Attachment> resolveAttachments(List<File> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<ChatMessage.Attachment> attachments = new ArrayList<>();
        for (File file : files) {
            attachments.add(downloadFile(file));
        }
        return attachments;
    }

    // conversations.history returns file metadata only; the actual bytes live behind
    // url_private_download and require the bot token as a Bearer credential to fetch.
    private ChatMessage.Attachment downloadFile(File file) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(file.getUrlPrivateDownload()))
                    .header("Authorization", "Bearer " + properties.botToken())
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Failed to download Slack file " + file.getName() + ": HTTP " + response.statusCode());
            }
            try (InputStream body = response.body()) {
                return new ChatMessage.Attachment(file.getId(), file.getName(), file.getMimetype(), body.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to download Slack file: " + file.getName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while downloading Slack file: " + file.getName(), e);
        }
    }
}
