package com.rnd.bridge.intake.parser.email;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.rnd.bridge.intake.config.GmailImportProperties;
import com.rnd.bridge.intake.google.GoogleCredentialsProvider;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads real Gmail messages via users.messages.list/get. Two gotchas vs. FixtureEmailSourceReader
 * (see plan.md "Gmail attachment shape mismatch"):
 * 1. Attachment bytes are never inline in the message payload once a message is non-trivial in
 *    size - they need a separate users.messages.attachments.get call keyed by attachmentId.
 * 2. Gmail base64-encodes both inline part bodies and fetched attachment bodies with the
 *    URL-safe alphabet (Base64.getUrlDecoder()), not standard base64 - unlike the fixtures,
 *    which use standard Base64 like the calendar/drive fixtures do.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.email", name = "mode", havingValue = "real")
public class RealEmailSourceReader implements EmailSourceReader {

    private static final Pattern ANGLE_BRACKET_EMAIL = Pattern.compile("<([^>]+)>");

    private final GoogleCredentialsProvider credentialsProvider;
    private final GmailImportProperties properties;
    private Gmail client;

    public RealEmailSourceReader(GoogleCredentialsProvider credentialsProvider, GmailImportProperties properties) {
        this.credentialsProvider = credentialsProvider;
        this.properties = properties;
    }

    @Override
    public List<SourceRecord> read() {
        Gmail gmail = client();
        List<SourceRecord> records = new ArrayList<>();
        String pageToken = null;
        do {
            ListMessagesResponse page = listPage(gmail, pageToken);
            if (page.getMessages() != null) {
                for (Message summary : page.getMessages()) {
                    records.addAll(toSourceRecords(gmail, getFull(gmail, summary.getId())));
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        return records;
    }

    private ListMessagesResponse listPage(Gmail gmail, String pageToken) {
        try {
            return gmail.users().messages().list("me").setPageToken(pageToken).execute();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list Gmail messages", e);
        }
    }

    private Message getFull(Gmail gmail, String messageId) {
        try {
            return gmail.users().messages().get("me", messageId).setFormat("full").execute();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fetch Gmail message " + messageId, e);
        }
    }

    private List<SourceRecord> toSourceRecords(Gmail gmail, Message message) {
        List<SourceRecord> records = new ArrayList<>();
        MessagePart payload = message.getPayload();
        String from = extractEmailAddress(header(payload, "From"));
        String subject = header(payload, "Subject");
        Instant occurredAt = Instant.ofEpochMilli(message.getInternalDate());
        String naturalId = "EMAIL|" + message.getId();
        String correlationId = UUID.randomUUID().toString();
        List<String> participants = participants(payload, from);

        StringBuilder plainTextBody = new StringBuilder();
        List<MessagePart> attachmentParts = new ArrayList<>();
        collectBodyAndAttachments(payload, plainTextBody, attachmentParts);

        records.add(new SourceRecord(
                RawSourceType.EMAIL, properties.tenantId(), naturalId, correlationId, occurredAt, from,
                participants, subject, plainTextBody.isEmpty() ? null : plainTextBody.toString(),
                message.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, List.of()));

        int index = 0;
        for (MessagePart attachment : attachmentParts) {
            index++;
            records.add(new SourceRecord(
                    RawSourceType.EMAIL, properties.tenantId(), naturalId + "|attachment-" + index, correlationId,
                    occurredAt, from, participants, null, null, attachmentBytes(gmail, message.getId(), attachment),
                    attachment.getMimeType(), attachment.getFilename(), index, List.of()));
        }
        return records;
    }

    /** Sender plus every To/Cc recipient - unlike Calendar/Drive, an email naturally has more
     * than one relevant party, and scoping (allowed-domains) should see all of them, not just
     * the sender. */
    private static List<String> participants(MessagePart payload, String from) {
        List<String> participants = new ArrayList<>();
        participants.add(from);
        participants.addAll(recipientAddresses(payload, "To"));
        participants.addAll(recipientAddresses(payload, "Cc"));
        return participants;
    }

    private static List<String> recipientAddresses(MessagePart payload, String headerName) {
        String header = header(payload, headerName);
        if (header == null || header.isBlank()) {
            return List.of();
        }
        List<String> addresses = new ArrayList<>();
        for (String address : header.split(",")) {
            addresses.add(extractEmailAddress(address.trim()));
        }
        return addresses;
    }

    /**
     * Gmail bodies are a MIME part tree, not a flat field (unlike the fixture's assumption) -
     * walks it depth-first, keeping the first text/plain part found as the body and every part
     * with a filename as an attachment reference.
     */
    private void collectBodyAndAttachments(MessagePart part, StringBuilder body, List<MessagePart> attachments) {
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                collectBodyAndAttachments(child, body, attachments);
            }
            return;
        }
        if (part.getFilename() != null && !part.getFilename().isBlank()) {
            attachments.add(part);
        } else if ("text/plain".equals(part.getMimeType()) && body.isEmpty() && part.getBody().getData() != null) {
            body.append(new String(Base64.getUrlDecoder().decode(part.getBody().getData()), StandardCharsets.UTF_8));
        }
    }

    private byte[] attachmentBytes(Gmail gmail, String messageId, MessagePart part) {
        MessagePartBody body = part.getBody();
        if (body.getData() != null) {
            return Base64.getUrlDecoder().decode(body.getData());
        }
        try {
            MessagePartBody fetched = gmail.users()
                    .messages()
                    .attachments()
                    .get("me", messageId, body.getAttachmentId())
                    .execute();
            return Base64.getUrlDecoder().decode(fetched.getData());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to fetch Gmail attachment " + body.getAttachmentId() + " on message " + messageId, e);
        }
    }

    private static String header(MessagePart payload, String name) {
        for (MessagePartHeader header : payload.getHeaders()) {
            if (header.getName().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    private static String extractEmailAddress(String fromHeader) {
        if (fromHeader == null) {
            return null;
        }
        Matcher matcher = ANGLE_BRACKET_EMAIL.matcher(fromHeader);
        return matcher.find() ? matcher.group(1) : fromHeader;
    }

    private synchronized Gmail client() {
        if (client == null) {
            // Plain NetHttpTransport, not GoogleNetHttpTransport.newTrustedTransport() - see
            // GoogleCredentialsProvider for why (system truststore vs. Google's bundled one).
            HttpTransport transport = new NetHttpTransport();
            var credential = credentialsProvider.credentials(List.of(GmailScopes.GMAIL_READONLY));
            client = new Gmail.Builder(transport, GsonFactory.getDefaultInstance(), credential)
                    .setApplicationName("bridge-intake")
                    .build();
        }
        return client;
    }
}
