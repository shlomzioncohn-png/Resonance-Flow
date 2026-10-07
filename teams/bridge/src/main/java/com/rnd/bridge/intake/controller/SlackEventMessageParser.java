package com.rnd.bridge.intake.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rnd.bridge.intake.config.SlackApiProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.parser.slack.SlackUserNameResolver;
import com.rnd.bridge.raw.model.RawSourceType;
import com.slack.api.model.File;
import com.slack.api.model.Message;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Converts a verified Slack Events API "event_callback" payload's inner {@code event} object
 * into a {@link ChatMessage}, for the two shapes this real-time path supports: a plain,
 * newly-posted "message" event with no subtype, and a "message_changed" edit. Deliberately
 * parses into the Slack SDK's own {@link Message} model (the same type
 * {@code RealSlackSourceReader} gets back from conversations.history) rather than hand-rolled
 * JSON field access — the Events API's message event (and the nested "message" object inside a
 * "message_changed" edit) shares the same field names as that polling response, so this reuses
 * the existing DTO instead of inventing a parallel one.
 * For "message_changed", Slack nests the edited content under {@code event.message} (with the
 * pre-edit content under {@code event.previous_message}, unused here) — {@code event.message.ts}
 * is the ORIGINAL message's stable id, which is what becomes {@link ChatMessage#id()} so the
 * reconciler's messageId -> groupId lookup finds the message being edited. The top-level
 * {@code event.ts} is the edit notification's own timestamp, never used as a message identity.
 * The nested message object has no "channel" field of its own, so the top-level event's channel
 * is copied in before mapping to {@link Message}.
 * Any other shape (a different event type, or a "message" with any other subtype - deletes, bot
 * messages, channel joins, etc.) returns {@link Optional#empty()} rather than throwing: Slack
 * must still receive its ack for those, they are just not fed into reconciliation. Expanding
 * support to those subtypes is out of scope for this step (see SlackEventsController).
 * userName resolution uses the same shared {@link SlackUserNameResolver} as
 * {@code RealSlackSourceReader}'s polling path, so both paths agree on a resolved display name
 * for the same user and share one lookup cache instead of each paying for its own users.info call.
 */
@Component
class SlackEventMessageParser {

    // The Slack SDK's Message/File models are annotated for Gson (SerializedName), not Jackson -
    // there is no @JsonProperty mapping "thread_ts" -> threadTs etc. for our app's shared
    // ObjectMapper to pick up. A small dedicated snake_case mapper reuses the SDK's DTO shape
    // (per this class's javadoc) without either hand-rolling field access or changing the
    // shared ObjectMapper's naming strategy app-wide.
    private static final ObjectMapper SLACK_EVENT_MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final SlackApiProperties properties;
    private final SlackUserNameResolver userNameResolver;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    SlackEventMessageParser(SlackApiProperties properties, SlackUserNameResolver userNameResolver) {
        this.properties = properties;
        this.userNameResolver = userNameResolver;
    }

    Optional<ChatMessage> parse(JsonNode event) {
        if (event == null || !event.hasNonNull("type") || !"message".equals(event.get("type").asText())) {
            return Optional.empty();
        }
        if (!event.hasNonNull("subtype")) {
            return parseMessageNode(event, event);
        }
        if ("message_changed".equals(event.get("subtype").asText()) && event.hasNonNull("message")) {
            return parseMessageNode(event, event.get("message"));
        }
        // message_deleted, bot messages, channel-join notices, malformed message_changed
        // payloads (no nested "message"), etc. - not this step's scope.
        return Optional.empty();
    }

    /**
     * channelSource supplies "channel" (the top-level event field is authoritative and, for a
     * message_changed edit, absent from the nested message node); messageNode supplies
     * everything else - itself for a plain message, or event.message for an edit, so that an
     * edit's resulting ChatMessage.id is the ORIGINAL message's stable ts, never the top-level
     * event.ts (see class javadoc). If messageNode is missing its own "ts", this returns empty
     * rather than ever falling back to channelSource's ts - a malformed edit must not be
     * misread as a new message with the wrong identity.
     */
    private Optional<ChatMessage> parseMessageNode(JsonNode channelSource, JsonNode messageNode) {
        if (!channelSource.hasNonNull("channel") || !messageNode.hasNonNull("ts") || !messageNode.hasNonNull("text")) {
            return Optional.empty();
        }

        Message message;
        try {
            ObjectNode toMap = messageNode.deepCopy();
            toMap.put("channel", channelSource.get("channel").asText());
            // Real Slack messages carry a "blocks" (Block Kit) array whose elements are a
            // polymorphic LayoutBlock hierarchy - deserializable via the SDK's own Gson setup,
            // but not via this plain Jackson mapper (no @JsonTypeInfo/@JsonSubTypes here), so
            // treeToValue would throw on any real payload. Not read by this class (only
            // channel/user/ts/text/thread_ts/files are), so it's dropped before mapping rather
            // than fought with a custom polymorphic deserializer.
            toMap.remove("blocks");
            message = SLACK_EVENT_MAPPER.treeToValue(toMap, Message.class);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }

        String threadId = message.getThreadTs();
        long epochMillis = Math.round(Double.parseDouble(message.getTs()) * 1000);
        return Optional.of(new ChatMessage(
                RawSourceType.SLACK,
                properties.tenantId(),
                message.getChannel(),
                userNameResolver.resolveUserName(message.getUser()),
                Instant.ofEpochMilli(epochMillis),
                message.getText(),
                resolveAttachments(message.getFiles()),
                message.getTs(),
                threadId));
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

    // Same "metadata now, bytes via a second authenticated GET" split RealSlackSourceReader uses
    // for its own file downloads - the Events API payload never carries file content either.
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
