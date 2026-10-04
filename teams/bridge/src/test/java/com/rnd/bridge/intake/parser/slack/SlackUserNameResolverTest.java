package com.rnd.bridge.intake.parser.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.User;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Exercises SlackUserNameResolver directly against a mocked MethodsClient (the test-only
 * package-private constructor seam) so live resolution, fallback, and caching behavior can be
 * verified without a real Slack bot token or network access - mirrors RealCalendarSourceReader's
 * injected-client test pattern.
 */
class SlackUserNameResolverTest {

    private final MethodsClient methods = mock(MethodsClient.class);
    private final SlackUserNameResolver resolver = new SlackUserNameResolver(methods);

    private static UsersInfoResponse okResponse(User user) {
        UsersInfoResponse response = new UsersInfoResponse();
        response.setOk(true);
        response.setUser(user);
        return response;
    }

    private static User userWithProfileRealName(String realName) {
        User user = new User();
        User.Profile profile = new User.Profile();
        profile.setRealName(realName);
        user.setProfile(profile);
        return user;
    }

    @Test
    void resolvesToTheUsersProfileRealName() throws IOException, SlackApiException {
        when(methods.usersInfo(any(UsersInfoRequest.class))).thenReturn(okResponse(userWithProfileRealName("Alice Example")));

        assertThat(resolver.resolveUserName("U1")).isEqualTo("Alice Example");
    }

    @Test
    void fallsBackToTopLevelRealNameWhenProfileRealNameIsBlank() throws IOException, SlackApiException {
        User user = new User();
        user.setProfile(new User.Profile());
        user.setRealName("Bob Example");
        when(methods.usersInfo(any(UsersInfoRequest.class))).thenReturn(okResponse(user));

        assertThat(resolver.resolveUserName("U2")).isEqualTo("Bob Example");
    }

    @Test
    void fallsBackToUsernameWhenNoRealNameIsSet() throws IOException, SlackApiException {
        User user = new User();
        user.setName("bob");
        when(methods.usersInfo(any(UsersInfoRequest.class))).thenReturn(okResponse(user));

        assertThat(resolver.resolveUserName("U3")).isEqualTo("bob");
    }

    @Test
    void fallsBackToTheRawUserIdWhenNoNameFieldIsSet() throws IOException, SlackApiException {
        when(methods.usersInfo(any(UsersInfoRequest.class))).thenReturn(okResponse(new User()));

        assertThat(resolver.resolveUserName("U4")).isEqualTo("U4");
    }

    @Test
    void fallsBackToTheRawUserIdWhenTheApiResponseIsNotOk() throws IOException, SlackApiException {
        UsersInfoResponse response = new UsersInfoResponse();
        response.setOk(false);
        when(methods.usersInfo(any(UsersInfoRequest.class))).thenReturn(response);

        assertThat(resolver.resolveUserName("U5")).isEqualTo("U5");
    }

    @Test
    void fallsBackToTheRawUserIdWhenTheLookupThrowsASlackApiException() throws IOException, SlackApiException {
        when(methods.usersInfo(any(UsersInfoRequest.class))).thenThrow(mock(SlackApiException.class));

        assertThat(resolver.resolveUserName("U6")).isEqualTo("U6");
    }

    @Test
    void fallsBackToTheRawUserIdWhenTheLookupThrowsAnIOException() throws IOException, SlackApiException {
        when(methods.usersInfo(any(UsersInfoRequest.class))).thenThrow(new IOException("boom"));

        assertThat(resolver.resolveUserName("U7")).isEqualTo("U7");
    }

    @Test
    void returnsUnknownForANullUserIdWithoutCallingTheApi() throws IOException, SlackApiException {
        assertThat(resolver.resolveUserName(null)).isEqualTo("unknown");

        verify(methods, times(0)).usersInfo(any(UsersInfoRequest.class));
    }

    @Test
    void cachesAResolvedNameSoARepeatedLookupNeverHitsTheApiAgain() throws IOException, SlackApiException {
        when(methods.usersInfo(any(UsersInfoRequest.class))).thenReturn(okResponse(userWithProfileRealName("Alice Example")));

        assertThat(resolver.resolveUserName("U1")).isEqualTo("Alice Example");
        assertThat(resolver.resolveUserName("U1")).isEqualTo("Alice Example");

        verify(methods, times(1)).usersInfo(any(UsersInfoRequest.class));
    }
}
