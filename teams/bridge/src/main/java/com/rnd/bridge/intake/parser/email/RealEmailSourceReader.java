package com.rnd.bridge.intake.parser.email;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.rnd.bridge.core.ReadCursor;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads real Gmail messages via users.messages.list/get, or - once a prior historyId exists -
 * via the cheaper users.history.list. Two gotchas vs. FixtureEmailSourceReader (see plan.md
 * "Gmail attachment shape mismatch"):
 * 1. Attachment bytes are never inline in the message payload once a message is non-trivial in
 *    size - they need a separate users.messages.attachments.get call keyed by attachmentId.
 * 2. Gmail base64-encodes both inline part bodies and fetched attachment bodies with the
 *    URL-safe alphabet (Base64.getUrlDecoder()), not standard base64 - unlike the fixtures,
 *    which use standard Base64 like the calendar/drive fixtures do.
 * cursor.historyId() switches to incremental mode: users.history.list(startHistoryId) returns
 * only the messages added since that id, instead of a full mailbox scan. Mirrors
 * RealCalendarSourceReader's read()/readPages() split and its reasoning for returning the next
 * cursor as part of the per-call result rather than an instance field - this class is a
 * singleton Spring bean, so a shared mutable "last historyId" field would let one concurrent
 * read's cursor bleed into another's caller.
 * A 404 response to users.history.list means the startHistoryId is too old for Gmail to still
 * have a record of (Gmail does not retain history indefinitely) - the only recovery is a full
 * resync, exactly like Calendar's 410-on-expired-syncToken handling; readFull()'s own fresh
 * historyId (not a continuation of the expired one) is returned as part of that same
 * EmailReadResult.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.email", name = "mode", havingValue = "real")
public class RealEmailSourceReader implements EmailSourceReader {

    private static final Pattern ANGLE_BRACKET_EMAIL = Pattern.compile("<([^>]+)>");

    private final GoogleCredentialsProvider credentialsProvider;
    private final GmailImportProperties properties;
    private Gmail client;

    @Autowired
    public RealEmailSourceReader(GoogleCredentialsProvider credentialsProvider, GmailImportProperties properties) {
        this.credentialsProvider = credentialsProvider;
        this.properties = properties;
    }

    /** Test-only seam: lets tests inject a mocked Gmail client instead of the real one client()
     * would otherwise lazily build from live Google credentials. */
    RealEmailSourceReader(GoogleCredentialsProvider credentialsProvider, GmailImportProperties properties, Gmail client) {
        this(credentialsProvider, properties);
        this.client = client;
    }

    @Override
    public EmailReadResult read(ReadCursor cursor) {
        String historyId = cursor == null ? null : cursor.historyId();
        if (historyId == null) {
            return readFull();
        }
        try {
            return readIncremental(historyId);
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                // Expired/unknown historyId - the only way back is a full sync from scratch,
                // logged so an operator can see this happened even though it self-heals (Mind
                // dedupes on content, so a resync is harmless - see ROADMAP.md).
                System.getLogger(RealEmailSourceReader.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Gmail historyId " + historyId + " expired or unknown (404) - falling back to full resync");
                return readFull();
            }
            throw new UncheckedIOException("Failed to list Gmail history from " + historyId, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list Gmail history from " + historyId, e);
        }
    }

    private EmailReadResult readFull() {
        Gmail gmail = client();
        List<SourceRecord> records = new ArrayList<>();
        String pageToken = null;
        String latestHistoryId = null;
        do {
            ListMessagesResponse page = listPage(gmail, pageToken);
            if (page.getMessages() != null) {
                for (Message summary : page.getMessages()) {
                    Message full = getFull(gmail, summary.getId());
                    records.addAll(toSourceRecords(gmail, full));
                    if (full.getHistoryId() != null) {
                        latestHistoryId = full.getHistoryId().toString();
                    }
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        return new EmailReadResult(records, latestHistoryId);
    }

    private EmailReadResult readIncremental(String startHistoryId) throws IOException {
        Gmail gmail = client();
        Set<String> changedMessageIds = new LinkedHashSet<>();
        String pageToken = null;
        String nextHistoryId = startHistoryId;
        do {
            ListHistoryResponse page = listHistoryPage(gmail, startHistoryId, pageToken);
            if (page.getHistory() != null) {
                for (History history : page.getHistory()) {
                    if (history.getMessagesAdded() != null) {
                        history.getMessagesAdded().forEach(added -> changedMessageIds.add(added.getMessage().getId()));
                    }
                }
            }
            if (page.getHistoryId() != null) {
                nextHistoryId = page.getHistoryId().toString();
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);

        List<SourceRecord> records = new ArrayList<>();
        for (String messageId : changedMessageIds) {
            records.addAll(toSourceRecords(gmail, getFull(gmail, messageId)));
        }
        return new EmailReadResult(records, nextHistoryId);
    }

    private ListMessagesResponse listPage(Gmail gmail, String pageToken) {
        try {
            return gmail.users().messages().list("me").setPageToken(pageToken).execute();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list Gmail messages", e);
        }
    }

    private ListHistoryResponse listHistoryPage(Gmail gmail, String startHistoryId, String pageToken)
            throws IOException {
        return gmail.users().history().list("me")
                .setStartHistoryId(new java.math.BigInteger(startHistoryId))
                .setPageToken(pageToken)
                .execute();
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
        int attachmentCount = attachmentParts.size();

        records.add(new SourceRecord(
                RawSourceType.EMAIL, properties.tenantId(), naturalId, correlationId, occurredAt, from,
                participants, subject, plainTextBody.isEmpty() ? null : plainTextBody.toString(),
                message.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, attachmentCount, List.of(), 0));

        int index = 0;
        for (MessagePart attachment : attachmentParts) {
            index++;
            records.add(new SourceRecord(
                    RawSourceType.EMAIL, properties.tenantId(), naturalId + "|attachment-" + index, correlationId,
                    occurredAt, from, participants, null, null, attachmentBytes(gmail, message.getId(), attachment),
                    attachment.getMimeType(), attachment.getFilename(), index, attachmentCount, List.of(), 0));
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
