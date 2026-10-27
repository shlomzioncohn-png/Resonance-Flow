package com.rnd.bridge.intake.parser.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.Profile;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.config.BridgeTenantId;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.Role;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Covers the historyId lifecycle in RealEmailSourceReader.read: full vs. incremental request
 * shape, and 404 (expired/unknown historyId) recovery via a full resync - the Gmail-specific
 * counterpart to RealCalendarSourceReaderTest's syncToken/410 coverage.
 * Gmail/Gmail.Users/Gmail.Users.Messages/Gmail.Users.History are mocked (Mockito.RETURNS_SELF
 * for the fluent set* chain) because the real *.execute() calls would otherwise make a real
 * HTTP call; Message/MessagePart/History/HistoryMessageAdded are used as plain POJOs since
 * they're just GenericJson data classes with no I/O.
 */
class RealEmailSourceReaderTest {

    private static final BridgeTenantId TENANT_ID = new BridgeTenantId("kamatech");

    private final Gmail gmail = mock(Gmail.class);
    private final Gmail.Users users = mock(Gmail.Users.class);
    private final Gmail.Users.Messages messages = mock(Gmail.Users.Messages.class);
    private final Gmail.Users.History history = mock(Gmail.Users.History.class);
    private final Gmail.Users.GetProfile getProfileRequest =
            mock(Gmail.Users.GetProfile.class, Mockito.RETURNS_SELF);

    /** Wires getProfile("me") to the shared getProfileRequest mock, but leaves its execute()
     * unstubbed - each test sets that explicitly (success or failure) before calling read(), so
     * stub setup order can never flip which behavior "wins" (consecutive when().thenX() calls on
     * the same mock method apply in call order, not override each other, so sharing a single
     * default stub across tests with different needs is a trap). */
    private RealEmailSourceReader reader() throws IOException {
        when(gmail.users()).thenReturn(users);
        when(users.messages()).thenReturn(messages);
        when(users.history()).thenReturn(history);
        when(users.getProfile("me")).thenReturn(getProfileRequest);
        return new RealEmailSourceReader(null, TENANT_ID, gmail);
    }

    private Message message(String id, String historyId) {
        MessagePart payload = new MessagePart()
                .setHeaders(List.of(
                        new MessagePartHeader().setName("From").setValue("ruth@kamatech.org"),
                        new MessagePartHeader().setName("Subject").setValue("subject"),
                        new MessagePartHeader().setName("Message-ID").setValue("<" + id + "@mail.gmail.com>")));
        return new Message()
                .setId(id)
                .setInternalDate(1_700_000_000_000L)
                .setHistoryId(historyId == null ? null : new BigInteger(historyId))
                .setPayload(payload);
    }

    @Test
    void fullSyncNeverCallsHistoryListAndCapturesTheLatestMessageHistoryId() throws IOException {
        Gmail.Users.Messages.List listRequest =
                mock(Gmail.Users.Messages.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get getRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(messages.list("me")).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("m1"))));
        when(messages.get("me", "m1")).thenReturn(getRequest);
        when(getRequest.execute()).thenReturn(message("m1", "100"));
        when(getProfileRequest.execute()).thenThrow(new IOException("profile lookup unavailable"));

        EmailReadResult result = reader().read(ReadCursor.full());

        assertThat(result.records()).hasSize(2);
        assertThat(result.nextHistoryId()).isEqualTo("100");
        assertThat(result.records().get(0).sourceRef()).isEqualTo("<m1@mail.gmail.com>");
        verify(history, never()).list(any());
    }

    @Test
    void messageWithNoMessageIdHeaderGetsANullSourceRef() throws IOException {
        Gmail.Users.Messages.List listRequest =
                mock(Gmail.Users.Messages.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get getRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(messages.list("me")).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("m1"))));
        when(messages.get("me", "m1")).thenReturn(getRequest);
        MessagePart payloadWithoutMessageId = new MessagePart()
                .setHeaders(List.of(new MessagePartHeader().setName("From").setValue("ruth@kamatech.org")));
        when(getRequest.execute()).thenReturn(new Message().setId("m1").setInternalDate(1_700_000_000_000L)
                .setPayload(payloadWithoutMessageId));
        when(getProfileRequest.execute()).thenThrow(new IOException("profile lookup unavailable"));

        EmailReadResult result = reader().read(ReadCursor.full());

        assertThat(result.records().get(0).sourceRef()).isNull();
    }

    @Test
    void incrementalSyncUsesHistoryListWithTheStoredHistoryIdAndFetchesOnlyAddedMessages() throws IOException {
        Gmail.Users.History.List historyRequest =
                mock(Gmail.Users.History.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get getRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(history.list("me")).thenReturn(historyRequest);
        HistoryMessageAdded added = new HistoryMessageAdded().setMessage(new Message().setId("m2"));
        when(historyRequest.execute()).thenReturn(new ListHistoryResponse()
                .setHistory(List.of(new History().setMessagesAdded(List.of(added))))
                .setHistoryId(new BigInteger("200")));
        when(messages.get("me", "m2")).thenReturn(getRequest);
        when(getRequest.execute()).thenReturn(message("m2", "200"));

        EmailReadResult result = reader().read(new ReadCursor(null, "100"));

        assertThat(result.records()).hasSize(2);
        assertThat(result.nextHistoryId()).isEqualTo("200");
        verify(historyRequest).setStartHistoryId(new BigInteger("100"));
        verify(messages, never()).list(any());
    }

    @Test
    void fullSyncSearchesOutOnlyDraftsAndIngestsSentMailButStillSkipsDraftSpamTrashLabels() throws IOException {
        Gmail.Users.Messages.List listRequest =
                mock(Gmail.Users.Messages.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get inboxRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get sentRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get draftRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get spamRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get trashRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(messages.list("me")).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("inbox"), new Message().setId("sent"),
                        new Message().setId("draft"), new Message().setId("spam"), new Message().setId("trash"))));
        when(messages.get("me", "inbox")).thenReturn(inboxRequest);
        when(inboxRequest.execute()).thenReturn(message("inbox", "100").setLabelIds(List.of("INBOX")));
        when(messages.get("me", "sent")).thenReturn(sentRequest);
        when(sentRequest.execute()).thenReturn(message("sent", "102").setLabelIds(List.of("SENT")));
        when(messages.get("me", "draft")).thenReturn(draftRequest);
        when(draftRequest.execute()).thenReturn(message("draft", "101").setLabelIds(List.of("DRAFT")));
        when(messages.get("me", "spam")).thenReturn(spamRequest);
        when(spamRequest.execute()).thenReturn(message("spam", "103").setLabelIds(List.of("SPAM")));
        when(messages.get("me", "trash")).thenReturn(trashRequest);
        when(trashRequest.execute()).thenReturn(message("trash", "104").setLabelIds(List.of("TRASH")));
        when(getProfileRequest.execute()).thenReturn(new Profile().setHistoryId(new BigInteger("500")));

        EmailReadResult result = reader().read(ReadCursor.full());

        verify(listRequest).setQ("-in:drafts");
        // One message lands as two records (BODY + SOURCE_METADATA), so two records = one message.
        assertThat(result.records()).hasSize(4);
        assertThat(result.records()).extracting(SourceRecord::sourceRef)
                .contains("<inbox@mail.gmail.com>", "<sent@mail.gmail.com>")
                .doesNotContain("<draft@mail.gmail.com>", "<spam@mail.gmail.com>", "<trash@mail.gmail.com>");
        assertThat(result.nextHistoryId()).isEqualTo("500");
    }

    @Test
    void incrementalSyncFetchesSentMailButStillSkipsDraftSpamTrashWithoutFetchingThem() throws IOException {
        Gmail.Users.History.List historyRequest =
                mock(Gmail.Users.History.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get sentRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get getRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(history.list("me")).thenReturn(historyRequest);
        List<HistoryMessageAdded> added = List.of(
                new HistoryMessageAdded().setMessage(new Message().setId("draft").setLabelIds(List.of("DRAFT"))),
                new HistoryMessageAdded().setMessage(new Message().setId("spam").setLabelIds(List.of("SPAM"))),
                new HistoryMessageAdded().setMessage(new Message().setId("trash").setLabelIds(List.of("TRASH"))),
                new HistoryMessageAdded().setMessage(new Message().setId("sent").setLabelIds(List.of("SENT"))),
                new HistoryMessageAdded().setMessage(new Message().setId("m2").setLabelIds(List.of("INBOX"))));
        when(historyRequest.execute()).thenReturn(new ListHistoryResponse()
                .setHistory(List.of(new History().setMessagesAdded(added)))
                .setHistoryId(new BigInteger("200")));
        when(messages.get("me", "sent")).thenReturn(sentRequest);
        when(sentRequest.execute()).thenReturn(message("sent", "199").setLabelIds(List.of("SENT")));
        when(messages.get("me", "m2")).thenReturn(getRequest);
        when(getRequest.execute()).thenReturn(message("m2", "200").setLabelIds(List.of("INBOX")));

        EmailReadResult result = reader().read(new ReadCursor(null, "100"));

        assertThat(result.records()).hasSize(4);
        assertThat(result.records()).extracting(SourceRecord::sourceRef)
                .contains("<m2@mail.gmail.com>", "<sent@mail.gmail.com>");
        verify(messages, never()).get("me", "draft");
        verify(messages, never()).get("me", "spam");
        verify(messages, never()).get("me", "trash");
    }

    @Test
    void expiredHistoryIdTriggersAFullResyncAndReturnsTheFreshHistoryId() throws IOException {
        Gmail.Users.History.List historyRequest =
                mock(Gmail.Users.History.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.List listRequest =
                mock(Gmail.Users.Messages.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get getRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(history.list("me")).thenReturn(historyRequest);
        when(historyRequest.execute()).thenThrow(expiredHistoryIdException());
        when(messages.list("me")).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("m3"))));
        when(messages.get("me", "m3")).thenReturn(getRequest);
        when(getRequest.execute()).thenReturn(message("m3", "300"));
        when(getProfileRequest.execute()).thenThrow(new IOException("profile lookup unavailable"));

        EmailReadResult result = reader().read(new ReadCursor(null, "999"));

        assertThat(result.records()).hasSize(2);
        assertThat(result.nextHistoryId()).isEqualTo("300");
        verify(historyRequest).setStartHistoryId(new BigInteger("999"));
    }

    @Test
    void fullSyncUsesTheProfileHistoryIdWhenAvailableEvenIfItExceedsEveryMessageHistoryId() throws IOException {
        Gmail.Users.Messages.List listRequest =
                mock(Gmail.Users.Messages.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get getRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(messages.list("me")).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("m1"))));
        when(messages.get("me", "m1")).thenReturn(getRequest);
        when(getRequest.execute()).thenReturn(message("m1", "100"));

        RealEmailSourceReader reader = reader();
        when(getProfileRequest.execute()).thenReturn(new Profile().setHistoryId(new BigInteger("500")));

        EmailReadResult result = reader.read(ReadCursor.full());

        assertThat(result.nextHistoryId()).isEqualTo("500");
    }

    @Test
    void fullSyncFallsBackToTheMaxMessageHistoryIdWhenTheProfileLookupFails() throws IOException {
        // getProfileRequest.execute() throws by default (see reader()) - this test exercises
        // that fallback explicitly, across multiple messages whose historyIds are NOT in list
        // order, to prove it's a real max (BigInteger comparison), not "whichever came last".
        Gmail.Users.Messages.List listRequest =
                mock(Gmail.Users.Messages.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get getRequestA =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get getRequestB =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(messages.list("me")).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("newest"), new Message().setId("oldest"))));
        when(messages.get("me", "newest")).thenReturn(getRequestA);
        when(getRequestA.execute()).thenReturn(message("newest", "9000"));
        when(messages.get("me", "oldest")).thenReturn(getRequestB);
        when(getRequestB.execute()).thenReturn(message("oldest", "2019"));
        when(getProfileRequest.execute()).thenThrow(new IOException("profile lookup unavailable"));

        EmailReadResult result = reader().read(ReadCursor.full());

        assertThat(result.nextHistoryId()).isEqualTo("9000");
    }

    @Test
    void tokenReturnedByAFullReadIsHonoredAsAnIncrementalStartHistoryIdOnTheNextRead() throws IOException {
        Gmail.Users.Messages.List listRequest =
                mock(Gmail.Users.Messages.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get getRequest =
                mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(messages.list("me")).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("m1"))));
        when(messages.get("me", "m1")).thenReturn(getRequest);
        when(getRequest.execute()).thenReturn(message("m1", "100"));

        RealEmailSourceReader reader = reader();
        when(getProfileRequest.execute()).thenReturn(new Profile().setHistoryId(new BigInteger("500")));
        EmailReadResult firstRead = reader.read(ReadCursor.full());
        assertThat(firstRead.nextHistoryId()).isEqualTo("500");
        Mockito.clearInvocations(messages);

        Gmail.Users.History.List historyRequest =
                mock(Gmail.Users.History.List.class, Mockito.RETURNS_SELF);
        when(history.list("me")).thenReturn(historyRequest);
        when(historyRequest.execute()).thenReturn(new ListHistoryResponse()
                .setHistory(List.of())
                .setHistoryId(new BigInteger("500")));

        EmailReadResult secondRead = reader.read(new ReadCursor(null, firstRead.nextHistoryId()));

        assertThat(secondRead.records()).isEmpty();
        verify(historyRequest).setStartHistoryId(new BigInteger("500"));
        verify(messages, never()).list(any());
    }

    @Test
    void incrementalSyncSkipsA404MessageAndStillReadsTheOthers() throws IOException {
        Gmail.Users.History.List historyRequest =
                mock(Gmail.Users.History.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get okRequest1 = mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get missingRequest = mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get okRequest2 = mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(history.list("me")).thenReturn(historyRequest);
        List<HistoryMessageAdded> added = List.of(
                new HistoryMessageAdded().setMessage(new Message().setId("m1")),
                new HistoryMessageAdded().setMessage(new Message().setId("deleted")),
                new HistoryMessageAdded().setMessage(new Message().setId("m2")));
        when(historyRequest.execute()).thenReturn(new ListHistoryResponse()
                .setHistory(List.of(new History().setMessagesAdded(added)))
                .setHistoryId(new BigInteger("200")));
        when(messages.get("me", "m1")).thenReturn(okRequest1);
        when(okRequest1.execute()).thenReturn(message("m1", "150"));
        when(messages.get("me", "deleted")).thenReturn(missingRequest);
        when(missingRequest.execute()).thenThrow(expiredHistoryIdException());
        when(messages.get("me", "m2")).thenReturn(okRequest2);
        when(okRequest2.execute()).thenReturn(message("m2", "200"));

        EmailReadResult result = reader().read(new ReadCursor(null, "100"));

        assertThat(result.records()).hasSize(4);
        assertThat(result.records()).extracting(SourceRecord::sourceRef)
                .contains("<m1@mail.gmail.com>", "<m2@mail.gmail.com>")
                .doesNotContain("<deleted@mail.gmail.com>");
        assertThat(result.nextHistoryId()).isEqualTo("200");
    }

    private static GoogleJsonResponseException expiredHistoryIdException() {
        HttpResponseException.Builder builder = new HttpResponseException.Builder(404, "Not Found", new HttpHeaders());
        return new GoogleJsonResponseException(builder, null);
    }

    private static MessagePart textPart(String mimeType, String text) {
        String encoded = Base64.getUrlEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return new MessagePart().setMimeType(mimeType).setBody(new MessagePartBody().setData(encoded));
    }

    private static Message messageWithHeadersAndParts(String id, List<MessagePartHeader> headers,
                                                        List<MessagePart> parts) {
        MessagePart payload = new MessagePart().setHeaders(headers).setParts(parts);
        return new Message().setId(id).setInternalDate(1_700_000_000_000L).setPayload(payload);
    }

    private static List<MessagePartHeader> standardHeaders(String from, String to, String cc, String subject,
                                                             String date) {
        List<MessagePartHeader> headers = new java.util.ArrayList<>(List.of(
                new MessagePartHeader().setName("From").setValue(from),
                new MessagePartHeader().setName("To").setValue(to),
                new MessagePartHeader().setName("Subject").setValue(subject),
                new MessagePartHeader().setName("Date").setValue(date),
                new MessagePartHeader().setName("Message-ID").setValue("<" + from + "@mail.gmail.com>")));
        if (cc != null) {
            headers.add(new MessagePartHeader().setName("Cc").setValue(cc));
        }
        return headers;
    }

    private EmailReadResult readSingleMessage(Message message) throws IOException {
        Gmail.Users.Messages.List listRequest = mock(Gmail.Users.Messages.List.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Get getRequest = mock(Gmail.Users.Messages.Get.class, Mockito.RETURNS_SELF);
        when(messages.list("me")).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new ListMessagesResponse()
                .setMessages(List.of(new Message().setId(message.getId()))));
        when(messages.get("me", message.getId())).thenReturn(getRequest);
        when(getRequest.execute()).thenReturn(message);
        when(getProfileRequest.execute()).thenThrow(new IOException("profile lookup unavailable"));
        return reader().read(ReadCursor.full());
    }

    @Test
    void readableBodyContainsHeadersAndPlainText() throws IOException {
        Message message = messageWithHeadersAndParts("m1",
                standardHeaders("ruth@kamatech.org", "hadas@kamatech.org", "malki@kamatech.org", "PR review", "Tue, 1 Jan 2026 10:00:00 +0000"),
                List.of(textPart("text/plain", "Please review the PR.")));

        EmailReadResult result = readSingleMessage(message);
        SourceRecord body = result.records().get(0);

        assertThat(body.body())
                .contains("From: ruth@kamatech.org")
                .contains("To: hadas@kamatech.org")
                .contains("Cc: malki@kamatech.org")
                .contains("Subject: PR review")
                .contains("Date: Tue, 1 Jan 2026 10:00:00 +0000")
                .contains("Please review the PR.");
    }

    @Test
    void htmlOnlyBodyIsStrippedToPlainTextWhenNoTextPlainPartExists() throws IOException {
        Message message = messageWithHeadersAndParts("m1",
                standardHeaders("ruth@kamatech.org", "hadas@kamatech.org", null, "subject", "date"),
                List.of(textPart("text/html", "<p>Hello <b>world</b></p>")));

        EmailReadResult result = readSingleMessage(message);
        SourceRecord body = result.records().get(0);

        assertThat(body.body()).contains("Hello world").doesNotContain("<p>").doesNotContain("<b>");
    }

    @Test
    void multipleTextPlainPartsAreAllConcatenatedNotJustTheFirst() throws IOException {
        Message message = messageWithHeadersAndParts("m1",
                standardHeaders("ruth@kamatech.org", "hadas@kamatech.org", null, "subject", "date"),
                List.of(textPart("text/plain", "first part"), textPart("text/plain", "second part")));

        EmailReadResult result = readSingleMessage(message);
        SourceRecord body = result.records().get(0);

        assertThat(body.body()).contains("first part").contains("second part");
    }

    @Test
    void hebrewTextRoundTripsThroughTheReadableBody() throws IOException {
        Message message = messageWithHeadersAndParts("m1",
                standardHeaders("ruth@kamatech.org", "hadas@kamatech.org", null, "ארוחת ערב", "date"),
                List.of(textPart("text/plain", "בואי נקבע ארוחת ערב משפחתית בשישי")));

        EmailReadResult result = readSingleMessage(message);
        SourceRecord body = result.records().get(0);

        assertThat(body.body())
                .contains("Subject: ארוחת ערב")
                .contains("בואי נקבע ארוחת ערב משפחתית בשישי");
    }

    @Test
    void sourceMetadataRecordCarriesTheRawMessageJsonAndRole() throws IOException {
        Message message = messageWithHeadersAndParts("m1",
                standardHeaders("ruth@kamatech.org", "hadas@kamatech.org", null, "subject", "date"),
                List.of(textPart("text/plain", "hello")));

        EmailReadResult result = readSingleMessage(message);

        assertThat(result.records()).hasSize(2);
        SourceRecord metadata = result.records().get(1);
        assertThat(metadata.objectRole()).isEqualTo(Role.SOURCE_METADATA);
        assertThat(metadata.mimeType()).isEqualTo("application/json");
        assertThat(new String(metadata.rawContent(), StandardCharsets.UTF_8)).isEqualTo(message.toString());
    }

    // --- Attachment fetch (attachments().get): gone (404/410) is a per-attachment skip --------

    private static MessagePart attachmentPart(String filename, String attachmentId) {
        return new MessagePart()
                .setFilename(filename)
                .setMimeType("application/pdf")
                .setBody(new MessagePartBody().setAttachmentId(attachmentId));
    }

    @Test
    void goneAttachment404IsSkippedButMessageStillPublishesWithoutIt() throws IOException {
        Message message = messageWithHeadersAndParts("m1",
                standardHeaders("ruth@kamatech.org", "hadas@kamatech.org", null, "subject", "date"),
                List.of(textPart("text/plain", "see attached"), attachmentPart("gone.pdf", "att-gone")));

        Gmail.Users.Messages.Attachments attachments = mock(Gmail.Users.Messages.Attachments.class);
        Gmail.Users.Messages.Attachments.Get attachmentRequest =
                mock(Gmail.Users.Messages.Attachments.Get.class, Mockito.RETURNS_SELF);
        when(messages.attachments()).thenReturn(attachments);
        when(attachments.get("me", "m1", "att-gone")).thenReturn(attachmentRequest);
        when(attachmentRequest.execute()).thenThrow(expiredHistoryIdException());

        EmailReadResult result = readSingleMessage(message);

        assertThat(result.records()).hasSize(2); // BODY + SOURCE_METADATA, no attachment record
        assertThat(result.records()).extracting(SourceRecord::objectRole)
                .doesNotContain(Role.ATTACHMENT);
    }

    @Test
    void goneAttachment410IsSkippedButMessageStillPublishesWithoutIt() throws IOException {
        Message message = messageWithHeadersAndParts("m1",
                standardHeaders("ruth@kamatech.org", "hadas@kamatech.org", null, "subject", "date"),
                List.of(textPart("text/plain", "see attached"), attachmentPart("gone.pdf", "att-gone")));

        Gmail.Users.Messages.Attachments attachments = mock(Gmail.Users.Messages.Attachments.class);
        Gmail.Users.Messages.Attachments.Get attachmentRequest =
                mock(Gmail.Users.Messages.Attachments.Get.class, Mockito.RETURNS_SELF);
        when(messages.attachments()).thenReturn(attachments);
        when(attachments.get("me", "m1", "att-gone")).thenReturn(attachmentRequest);
        HttpResponseException.Builder builder = new HttpResponseException.Builder(410, "Gone", new HttpHeaders());
        when(attachmentRequest.execute()).thenThrow(new GoogleJsonResponseException(builder, null));

        EmailReadResult result = readSingleMessage(message);

        assertThat(result.records()).hasSize(2);
    }

    @Test
    void attachmentFetchedSuccessfullyLandsAsItsOwnRecordAlongsideOthers() throws IOException {
        Message message = messageWithHeadersAndParts("m1",
                standardHeaders("ruth@kamatech.org", "hadas@kamatech.org", null, "subject", "date"),
                List.of(textPart("text/plain", "see attached"), attachmentPart("gone.pdf", "att-gone"),
                        attachmentPart("kept.pdf", "att-ok")));

        Gmail.Users.Messages.Attachments attachments = mock(Gmail.Users.Messages.Attachments.class);
        Gmail.Users.Messages.Attachments.Get goneRequest =
                mock(Gmail.Users.Messages.Attachments.Get.class, Mockito.RETURNS_SELF);
        Gmail.Users.Messages.Attachments.Get okRequest =
                mock(Gmail.Users.Messages.Attachments.Get.class, Mockito.RETURNS_SELF);
        when(messages.attachments()).thenReturn(attachments);
        when(attachments.get("me", "m1", "att-gone")).thenReturn(goneRequest);
        when(goneRequest.execute()).thenThrow(expiredHistoryIdException());
        when(attachments.get("me", "m1", "att-ok")).thenReturn(okRequest);
        String encoded = Base64.getUrlEncoder().encodeToString("pdf bytes".getBytes(StandardCharsets.UTF_8));
        when(okRequest.execute()).thenReturn(new MessagePartBody().setData(encoded));

        EmailReadResult result = readSingleMessage(message);

        assertThat(result.records()).hasSize(3); // BODY + SOURCE_METADATA + the one kept attachment
        assertThat(result.records().get(2).rawContent())
                .isEqualTo("pdf bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void genericAttachmentFetchErrorStillPropagates() throws IOException {
        Message message = messageWithHeadersAndParts("m1",
                standardHeaders("ruth@kamatech.org", "hadas@kamatech.org", null, "subject", "date"),
                List.of(textPart("text/plain", "see attached"), attachmentPart("file.pdf", "att-1")));

        Gmail.Users.Messages.Attachments attachments = mock(Gmail.Users.Messages.Attachments.class);
        Gmail.Users.Messages.Attachments.Get attachmentRequest =
                mock(Gmail.Users.Messages.Attachments.Get.class, Mockito.RETURNS_SELF);
        when(messages.attachments()).thenReturn(attachments);
        when(attachments.get("me", "m1", "att-1")).thenReturn(attachmentRequest);
        when(attachmentRequest.execute()).thenThrow(new IOException("boom"));

        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.UncheckedIOException.class, () -> readSingleMessage(message));
    }
}
