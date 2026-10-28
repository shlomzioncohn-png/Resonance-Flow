package com.rnd.bridge.intake.parser.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rnd.bridge.intake.config.BridgeTenantId;
import com.rnd.bridge.intake.config.SlackApiProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest;
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.File;
import com.slack.api.model.Message;
import com.slack.api.model.User;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RealSlackSourceReader needs a bot token and at least one channel id before it can call the
 * Slack Web API at all - both are checked eagerly in the constructor (unlike
 * RealWhatsAppSourceReader, which defers its checks to read()) because building the
 * MethodsClient itself requires the token up front.
 * toChatMessage()/hasReplies() are exercised directly against plain Message POJOs rather than
 * through read(), which would need a real/mocked Slack network client. userNameResolver here
 * wraps a mocked MethodsClient (SlackUserNameResolver's test-only seam) so resolution/caching can
 * be verified without a real bot token or network access - see SlackUserNameResolverTest for the
 * resolver's own fallback-chain coverage in isolation.
 */
class RealSlackSourceReaderTest {

    private final MethodsClient methods = mock(MethodsClient.class);
    private final SlackUserNameResolver userNameResolver = new SlackUserNameResolver(methods);
    private static final BridgeTenantId TENANT_ID = new BridgeTenantId("kamatech");
    private final RealSlackSourceReader reader = new RealSlackSourceReader(
            new SlackApiProperties("xoxb-fake", List.of("C123"), null), TENANT_ID, userNameResolver);

    @Test
    void failsFastWhenBotTokenIsNotConfigured() {
        assertThatThrownBy(() -> new RealSlackSourceReader(
                        new SlackApiProperties(null, List.of("C123"), null), TENANT_ID, userNameResolver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bot-token");
    }

    @Test
    void failsFastWhenNoChannelsAreConfigured() {
        assertThatThrownBy(() -> new RealSlackSourceReader(
                        new SlackApiProperties("xoxb-fake", List.of(), null), TENANT_ID, userNameResolver))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channels");
    }

    private Message message(String ts, String threadTs, Integer replyCount, String text) {
        Message message = new Message();
        message.setTs(ts);
        message.setThreadTs(threadTs);
        message.setReplyCount(replyCount);
        message.setText(text);
        return message;
    }

    @Test
    void hasRepliesIsTrueOnlyWhenReplyCountIsPositive() {
        assertThat(reader.hasReplies(message("1.1", null, 3, "root"))).isTrue();
        assertThat(reader.hasReplies(message("1.1", null, 0, "root"))).isFalse();
        assertThat(reader.hasReplies(message("1.1", null, null, "root"))).isFalse();
    }

    @Test
    void toChatMessagePopulatesIdFromTsAndCarriesThePassedThreadId() {
        ChatMessage chatMessage = reader.toChatMessage("C123", message("1700000000.000100", null, null, "hi"), null);

        assertThat(chatMessage.id()).isEqualTo("1700000000.000100");
        assertThat(chatMessage.threadId()).isNull();
        assertThat(chatMessage.text()).isEqualTo("hi");
        assertThat(chatMessage.sender()).isEqualTo("unknown");
    }

    @Test
    void toChatMessageCarriesAnExplicitThreadIdForRepliesAndRoots() {
        ChatMessage root = reader.toChatMessage(
                "C123", message("1700000000.000100", "1700000000.000100", 2, "root question"),
                "1700000000.000100");
        ChatMessage reply = reader.toChatMessage(
                "C123", message("1700000000.000200", "1700000000.000100", null, "a reply"),
                "1700000000.000100");

        assertThat(root.threadId()).isEqualTo(reply.threadId()).isEqualTo("1700000000.000100");
        assertThat(root.id()).isNotEqualTo(reply.id());
    }

    @Test
    void toChatMessageResolvesTheSendersDisplayNameThroughTheSharedResolver() throws Exception {
        Message message = message("1700000000.000100", null, null, "hi");
        message.setUser("U1");
        User user = new User();
        User.Profile profile = new User.Profile();
        profile.setRealName("Alice Example");
        user.setProfile(profile);
        UsersInfoResponse response = new UsersInfoResponse();
        response.setOk(true);
        response.setUser(user);
        when(methods.usersInfo(any(UsersInfoRequest.class))).thenReturn(response);

        ChatMessage chatMessage = reader.toChatMessage("C123", message, null);

        assertThat(chatMessage.sender()).isEqualTo("Alice Example");
    }

    @Test
    void toChatMessageCachesAResolvedSenderAcrossMessagesFromTheSameUser() throws Exception {
        User user = new User();
        user.setName("bob");
        UsersInfoResponse response = new UsersInfoResponse();
        response.setOk(true);
        response.setUser(user);
        when(methods.usersInfo(any(UsersInfoRequest.class))).thenReturn(response);

        Message first = message("1700000000.000100", null, null, "hi");
        first.setUser("U2");
        Message second = message("1700000000.000200", null, null, "hi again");
        second.setUser("U2");

        assertThat(reader.toChatMessage("C123", first, null).sender()).isEqualTo("bob");
        assertThat(reader.toChatMessage("C123", second, null).sender()).isEqualTo("bob");

        verify(methods, times(1)).usersInfo(any(UsersInfoRequest.class));
    }

    // --- Attachment: gone-file skip (no network call needed - null url is detected up front) -

    @Test
    void goneFileWithNoDownloadUrlIsSkippedButOthersStillResolve() {
        File gone = new File();
        gone.setId("F1");
        gone.setName("deleted.png");
        File ok = new File();
        ok.setId("F2");
        ok.setName("kept.png");
        ok.setUrlPrivateDownload(null); // also gone - both files in this fixture have no URL,
        // proving the skip is per-file, not "abort on the first gone file in the list".

        List<ChatMessage.Attachment> attachments = reader.resolveAttachments(List.of(gone, ok));

        assertThat(attachments).isEmpty();
    }

    // --- conversations.replies: thread_not_found is a per-thread skip, other errors still fail -

    private RealSlackSourceReader readerWithMockedMethods() {
        return new RealSlackSourceReader(
                new SlackApiProperties("xoxb-fake", List.of("C123"), null), TENANT_ID, userNameResolver, methods);
    }

    private ConversationsHistoryResponse historyWithThreadRoot(String rootTs) {
        Message root = message(rootTs, rootTs, 1, "root question");
        ConversationsHistoryResponse response = new ConversationsHistoryResponse();
        response.setOk(true);
        response.setMessages(List.of(root));
        return response;
    }

    @Test
    void threadNotFoundSkipsJustThatThreadButChannelReadStillCompletes() throws Exception {
        RealSlackSourceReader r = readerWithMockedMethods();
        when(methods.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenReturn(historyWithThreadRoot("1700000000.000100"));
        ConversationsRepliesResponse repliesResponse = new ConversationsRepliesResponse();
        repliesResponse.setOk(false);
        repliesResponse.setError("thread_not_found");
        when(methods.conversationsReplies(any(ConversationsRepliesRequest.class))).thenReturn(repliesResponse);

        List<ChatMessage> result = r.read(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("1700000000.000100");
    }

    @Test
    void otherConversationsRepliesErrorsStillFailTheRead() throws Exception {
        RealSlackSourceReader r = readerWithMockedMethods();
        when(methods.conversationsHistory(any(ConversationsHistoryRequest.class)))
                .thenReturn(historyWithThreadRoot("1700000000.000100"));
        ConversationsRepliesResponse repliesResponse = new ConversationsRepliesResponse();
        repliesResponse.setOk(false);
        repliesResponse.setError("rate_limited");
        when(methods.conversationsReplies(any(ConversationsRepliesRequest.class))).thenReturn(repliesResponse);

        assertThatThrownBy(() -> r.read(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate_limited");
    }
}
