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
import com.rnd.bridge.intake.config.BridgeTenantId;
import com.rnd.bridge.intake.google.GoogleCredentialsProvider;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
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
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Drafts (every autosave is a new message id, so each would land as its own object), spam and
 * trash are skipped - on the full scan via the FULL_SYNC_QUERY search, and on both paths via the
 * message's labelIds, since users.history.list takes no search query. The user's own sent mail is
 * ingested (Daniel/#1052: sent mail matters for task detection too).
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.email", name = "mode", havingValue = "real")
public class RealEmailSourceReader implements EmailSourceReader {

    private static final Logger log = LoggerFactory.getLogger(RealEmailSourceReader.class);
    private static final Pattern ANGLE_BRACKET_EMAIL = Pattern.compile("<([^>]+)>");
    // messages.list already leaves out spam and trash unless includeSpamTrash is set.
    static final String FULL_SYNC_QUERY = "-in:drafts";
    static final Set<String> EXCLUDED_LABELS = Set.of("DRAFT", "SPAM", "TRASH");

    private final GoogleCredentialsProvider credentialsProvider;
    private final BridgeTenantId tenantId;
    private Gmail client;

    @Autowired
    public RealEmailSourceReader(GoogleCredentialsProvider credentialsProvider, BridgeTenantId tenantId) {
        this.credentialsProvider = credentialsProvider;
        this.tenantId = tenantId;
    }

    /** Test-only seam: lets tests inject a mocked Gmail client instead of the real one client()
     * would otherwise lazily build from live Google credentials. */
    RealEmailSourceReader(GoogleCredentialsProvider credentialsProvider, BridgeTenantId tenantId, Gmail client) {
        this(credentialsProvider, tenantId);
        this.client = client;
    }

    @Override
    public EmailReadResult read(ReadCursor cursor) {
        String historyId = cursor == null ? null : cursor.token();
        log.info("Email read starting: storedToken={}", historyId != null ? "present" : "absent");
        if (historyId == null) {
            EmailReadResult result = readFull();
            logResult("full", result);
            return result;
        }
        try {
            EmailReadResult result = readIncremental(historyId);
            logResult("incremental", result);
            return result;
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                // Expired/unknown historyId - the only way back is a full sync from scratch,
                // logged so an operator can see this happened even though it self-heals (Mind
                // dedupes on content, so a resync is harmless - see ROADMAP.md).
                System.getLogger(RealEmailSourceReader.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Gmail historyId " + historyId + " expired or unknown (404) - falling back to full resync");
                log.info("Email incremental read got 404 on stored historyId - falling back to full resync; "
                        + "googleErrorDetails={}", e.getDetails() != null ? e.getDetails() : e.getMessage());
                EmailReadResult result = readFull();
                logResult("full (post-404 fallback)", result);
                return result;
            }
            throw new UncheckedIOException("Failed to list Gmail history from " + historyId, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list Gmail history from " + historyId, e);
        }
    }

    private void logResult(String path, EmailReadResult result) {
        log.info("Email read via {} path complete: records={}, nextToken={}",
                path, result.records().size(), result.nextHistoryId() != null ? "present" : "null");
    }

    private EmailReadResult readFull() {
        Gmail gmail = client();
        // The historyId to resume from must be a high-water mark as of (at least) the start of
        // this scan, not whichever message happens to be processed last - messages.list has no
        // documented, stable ordering guarantee, and in practice returns newest-first, so "last
        // processed" is the OLDEST message's historyId: constant across runs (nothing older ever
        // arrives) and already expired/unknown by the time the next incremental read tries it,
        // forcing an endless full resync. users.getProfile("me").historyId is Gmail's own current
        // mailbox-level historyId, captured before any per-message fetch, so it's always >= every
        // message's own historyId.
        BigInteger profileHistoryId = fetchProfileHistoryId(gmail);
        List<SourceRecord> records = new ArrayList<>();
        String pageToken = null;
        BigInteger maxMessageHistoryId = null;
        do {
            ListMessagesResponse page = listPage(gmail, pageToken);
            if (page.getMessages() != null) {
                for (Message summary : page.getMessages()) {
                    Message full;
                    try {
                        full = getFull(gmail, summary.getId());
                    } catch (MessageNotFoundException e) {
                        log.warn("Gmail message {} no longer exists (404) - skipping", summary.getId());
                        continue;
                    }
                    if (!isExcluded(full)) {
                        records.addAll(toSourceRecords(gmail, full));
                    }
                    if (full.getHistoryId() != null
                            && (maxMessageHistoryId == null || full.getHistoryId().compareTo(maxMessageHistoryId) > 0)) {
                        maxMessageHistoryId = full.getHistoryId();
                    }
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        BigInteger nextHistoryId = profileHistoryId != null ? profileHistoryId : maxMessageHistoryId;
        return new EmailReadResult(records, nextHistoryId == null ? null : nextHistoryId.toString());
    }

    private BigInteger fetchProfileHistoryId(Gmail gmail) {
        try {
            return gmail.users().getProfile("me").execute().getHistoryId();
        } catch (IOException e) {
            log.warn("Failed to fetch Gmail profile historyId - falling back to the max historyId "
                    + "across this sync's fetched messages", e);
            return null;
        }
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
                        history.getMessagesAdded().stream()
                                .map(added -> added.getMessage())
                                .filter(added -> !isExcluded(added))
                                .forEach(added -> changedMessageIds.add(added.getId()));
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
            Message full;
            try {
                full = getFull(gmail, messageId);
            } catch (MessageNotFoundException e) {
                log.warn("Gmail message {} no longer exists (404) - skipping", messageId);
                continue;
            }
            if (!isExcluded(full)) {
                records.addAll(toSourceRecords(gmail, full));
            }
        }
        return new EmailReadResult(records, nextHistoryId);
    }

    private ListMessagesResponse listPage(Gmail gmail, String pageToken) {
        try {
            return gmail.users().messages().list("me").setQ(FULL_SYNC_QUERY).setPageToken(pageToken).execute();
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

    /** A message with no labelIds (history entries sometimes omit them) is kept; the full message
     * fetched afterwards is checked again. */
    private static boolean isExcluded(Message message) {
        List<String> labels = message.getLabelIds();
        return labels != null && labels.stream().anyMatch(EXCLUDED_LABELS::contains);
    }

    private Message getFull(Gmail gmail, String messageId) {
        try {
            return gmail.users().messages().get("me", messageId).setFormat("full").execute();
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                throw new MessageNotFoundException(messageId, e);
            }
            throw new UncheckedIOException("Failed to fetch Gmail message " + messageId, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fetch Gmail message " + messageId, e);
        }
    }

    /** Internal signal that a single message id is gone (deleted between history/list and the
     * get call) - callers skip just that message instead of failing the whole run. */
    private static final class MessageNotFoundException extends RuntimeException {
        MessageNotFoundException(String messageId, GoogleJsonResponseException cause) {
            super("Gmail message " + messageId + " not found (404)", cause);
        }
    }

    private List<SourceRecord> toSourceRecords(Gmail gmail, Message message) {
        List<SourceRecord> records = new ArrayList<>();
        MessagePart payload = message.getPayload();
        String from = extractEmailAddress(header(payload, "From"));
        String to = header(payload, "To");
        String cc = header(payload, "Cc");
        String subject = header(payload, "Subject");
        String date = header(payload, "Date");
        String messageId = header(payload, "Message-ID");
        Instant occurredAt = Instant.ofEpochMilli(message.getInternalDate());
        String naturalId = "EMAIL|" + message.getId();
        // Deterministic (type-3 name-based), not randomUUID() - a resend of the same Gmail
        // message (retry of a partially-failed batch, or a resync) must mint the same
        // correlationId every time, or Mind's dedupKey() (correlationId + ":" + revision, see
        // RawObjectEvent) sees it as a brand-new logical item and duplicates it.
        String correlationId = UUID.nameUUIDFromBytes(
                (tenantId.value() + "|" + naturalId).getBytes(StandardCharsets.UTF_8)).toString();
        List<String> participants = participants(payload, from);

        List<MessagePart> attachmentParts = new ArrayList<>();
        String text = collectBodyAndAttachments(payload, attachmentParts, naturalId);
        int attachmentCount = attachmentParts.size();
        String readableBody = buildReadableBody(from, to, cc, subject, date, text);

        records.add(new SourceRecord(
                RawSourceType.EMAIL, tenantId.value(), naturalId, correlationId, occurredAt, from,
                participants, subject, readableBody, readableBody.getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=UTF-8", null, 0, attachmentCount, List.of(), 0)
                .withSourceRef(messageId));

        records.add(new SourceRecord(
                RawSourceType.EMAIL, tenantId.value(), naturalId + "|metadata", correlationId, occurredAt, from,
                participants, subject, null, message.toString().getBytes(StandardCharsets.UTF_8),
                "application/json", null, 0, attachmentCount, List.of(), 0)
                .withRole(Role.SOURCE_METADATA));

        int index = 0;
        for (MessagePart attachment : attachmentParts) {
            index++;
            records.add(new SourceRecord(
                    RawSourceType.EMAIL, tenantId.value(), naturalId + "|attachment-" + index, correlationId,
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
     * walks it depth-first, concatenating every text/plain part found (in document order) and
     * collecting every part with a filename as an attachment reference. Falls back to the first
     * text/html part (stripped to plain text via jsoup) only when no text/plain part was found at
     * all. A leaf part that is neither an attachment, text/plain, nor (as a fallback) text/html is
     * logged, never silently dropped.
     */
    private String collectBodyAndAttachments(MessagePart part, List<MessagePart> attachments, String naturalId) {
        List<String> textPlainParts = new ArrayList<>();
        List<MessagePart> htmlParts = new ArrayList<>();
        collectParts(part, attachments, textPlainParts, htmlParts, naturalId);
        if (!textPlainParts.isEmpty()) {
            return String.join("\n\n", textPlainParts);
        }
        if (!htmlParts.isEmpty()) {
            MessagePart html = htmlParts.get(0);
            if (html.getBody().getData() != null) {
                String htmlText = new String(
                        Base64.getUrlDecoder().decode(html.getBody().getData()), StandardCharsets.UTF_8);
                return Jsoup.parse(htmlText).text();
            }
        }
        return "";
    }

    private void collectParts(MessagePart part, List<MessagePart> attachments, List<String> textPlainParts,
                               List<MessagePart> htmlParts, String naturalId) {
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                collectParts(child, attachments, textPlainParts, htmlParts, naturalId);
            }
            return;
        }
        if (part.getFilename() != null && !part.getFilename().isBlank()) {
            attachments.add(part);
        } else if ("text/plain".equals(part.getMimeType())) {
            if (part.getBody().getData() != null) {
                textPlainParts.add(
                        new String(Base64.getUrlDecoder().decode(part.getBody().getData()), StandardCharsets.UTF_8));
            }
        } else if ("text/html".equals(part.getMimeType())) {
            htmlParts.add(part);
        } else {
            log.warn("Email naturalId={} has an unhandled leaf MIME part mimeType={} - skipped, not landed",
                    naturalId, part.getMimeType());
        }
    }

    /** From/To/Cc/Subject/Date header block, blank line, then the extracted text - Mind's
     * requested readable-text shape for the BODY object. Cc is omitted entirely when the message
     * has none; every other header line is always present even if the header itself was absent
     * (an empty value, not a missing line - keeps the shape predictable for Mind's parsing). */
    private static String buildReadableBody(String from, String to, String cc, String subject, String date,
                                              String text) {
        StringBuilder header = new StringBuilder();
        header.append("From: ").append(nullToEmpty(from)).append('\n');
        header.append("To: ").append(nullToEmpty(to)).append('\n');
        if (cc != null && !cc.isBlank()) {
            header.append("Cc: ").append(cc).append('\n');
        }
        header.append("Subject: ").append(nullToEmpty(subject)).append('\n');
        header.append("Date: ").append(nullToEmpty(date)).append('\n');
        header.append('\n').append(text);
        return header.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
