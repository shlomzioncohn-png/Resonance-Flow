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
import com.google.api.services.gmail.model.MessagePartHeader;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.config.GmailImportProperties;
import java.io.IOException;
import java.math.BigInteger;
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

    private static final GmailImportProperties PROPERTIES = new GmailImportProperties("kamatech");

    private final Gmail gmail = mock(Gmail.class);
    private final Gmail.Users users = mock(Gmail.Users.class);
    private final Gmail.Users.Messages messages = mock(Gmail.Users.Messages.class);
    private final Gmail.Users.History history = mock(Gmail.Users.History.class);

    private RealEmailSourceReader reader() {
        when(gmail.users()).thenReturn(users);
        when(users.messages()).thenReturn(messages);
        when(users.history()).thenReturn(history);
        return new RealEmailSourceReader(null, PROPERTIES, gmail);
    }

    private Message message(String id, String historyId) {
        MessagePart payload = new MessagePart()
                .setHeaders(List.of(
                        new MessagePartHeader().setName("From").setValue("ruth@kamatech.org"),
                        new MessagePartHeader().setName("Subject").setValue("subject")));
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

        EmailReadResult result = reader().read(ReadCursor.full());

        assertThat(result.records()).hasSize(1);
        assertThat(result.nextHistoryId()).isEqualTo("100");
        verify(history, never()).list(any());
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

        assertThat(result.records()).hasSize(1);
        assertThat(result.nextHistoryId()).isEqualTo("200");
        verify(historyRequest).setStartHistoryId(new BigInteger("100"));
        verify(messages, never()).list(any());
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

        EmailReadResult result = reader().read(new ReadCursor(null, "999"));

        assertThat(result.records()).hasSize(1);
        assertThat(result.nextHistoryId()).isEqualTo("300");
        verify(historyRequest).setStartHistoryId(new BigInteger("999"));
    }

    private static GoogleJsonResponseException expiredHistoryIdException() {
        HttpResponseException.Builder builder = new HttpResponseException.Builder(404, "Not Found", new HttpHeaders());
        return new GoogleJsonResponseException(builder, null);
    }
}
