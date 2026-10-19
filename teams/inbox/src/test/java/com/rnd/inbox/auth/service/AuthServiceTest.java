package com.rnd.inbox.auth.service;
import com.rnd.inbox.auth.dto.AuthUserResponseDto;
import com.rnd.inbox.auth.dto.AuthVerifyRequestDto;
import com.rnd.inbox.auth.exception.InvalidCredentialsException;
import com.rnd.inbox.auth.exception.TooManyLoginAttemptsException;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.model.AuthType;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserAuthIdentity;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserAuthIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String LOGIN = "rachel";
    private static final String PASSWORD = "MyPass123";
    private static final String PASSWORD_HASH = "stored-hash";
    private static final String DUMMY_HASH = "dummy-hash";
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Mock
    private UserAuthIdentityRepository userAuthIdentityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LoginAttemptLimiter loginAttemptLimiter;

    private AuthService authService;

    private UUID tenantId;
    private User user;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(any())).thenReturn(DUMMY_HASH);
        authService = new AuthService(userAuthIdentityRepository, passwordEncoder, loginAttemptLimiter,
                Clock.fixed(NOW, ZoneOffset.UTC));

        tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setTenant(tenant);
        user.setDisplayName("Rachel Cohen");
        user.setRole(UserRole.EMPLOYEE);
        user.setStatus(UserStatus.ACTIVE);
    }

    private AuthVerifyRequestDto request(String login, String password) {
        AuthVerifyRequestDto request = new AuthVerifyRequestDto();
        request.setLogin(login);
        request.setPassword(password);
        return request;
    }

    private void givenIdentityExists() {
        UserAuthIdentity identity = new UserAuthIdentity();
        identity.setUser(user);
        identity.setAuthType(AuthType.PASSWORD);
        identity.setIdentifier(LOGIN);
        identity.setPasswordHash(PASSWORD_HASH);
        when(userAuthIdentityRepository.findByAuthTypeAndIdentifier(AuthType.PASSWORD, LOGIN))
                .thenReturn(Optional.of(identity));
    }

    @Test
    void verify_withCorrectPassword_returnsUser() {
        givenIdentityExists();
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

        AuthUserResponseDto result = authService.verify(request(LOGIN, PASSWORD));

        assertThat(result.getId()).isEqualTo(user.getId());
        assertThat(result.getDisplayName()).isEqualTo("Rachel Cohen");
        assertThat(result.getRole()).isEqualTo(UserRole.EMPLOYEE);
        assertThat(result.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void verify_withCorrectPassword_updatesLastLoginAt() {
        givenIdentityExists();
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

        authService.verify(request(LOGIN, PASSWORD));

        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void verify_normalizesLoginBeforeLookup() {
        givenIdentityExists();
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

        AuthUserResponseDto result = authService.verify(request("  Rachel ", PASSWORD));

        assertThat(result.getId()).isEqualTo(user.getId());
    }

    @Test
    void verify_withWrongPassword_throwsInvalidCredentials() {
        givenIdentityExists();
        when(passwordEncoder.matches("wrong", PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.verify(request(LOGIN, "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test
    void verify_withUnknownLogin_throwsInvalidCredentialsAfterHashingAgainstDummy() {
        when(userAuthIdentityRepository.findByAuthTypeAndIdentifier(AuthType.PASSWORD, "nobody"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verify(request("nobody", PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid login or password");
        verify(passwordEncoder).matches(PASSWORD, DUMMY_HASH);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void verify_withInactiveUser_throwsInvalidCredentials(UserStatus status) {
        user.setStatus(status);
        givenIdentityExists();
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

        assertThatThrownBy(() -> authService.verify(request(LOGIN, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid login or password");
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test
    void verify_whenLoginIsLocked_throwsTooManyAttemptsWithoutCheckingPassword() {
        when(loginAttemptLimiter.isLocked(LOGIN)).thenReturn(true);

        assertThatThrownBy(() -> authService.verify(request(LOGIN, PASSWORD)))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        verifyNoInteractions(userAuthIdentityRepository);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void verify_withWrongPassword_recordsFailure() {
        givenIdentityExists();
        when(passwordEncoder.matches("wrong", PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.verify(request(LOGIN, "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(loginAttemptLimiter).recordFailure(LOGIN);
        verify(loginAttemptLimiter, never()).recordSuccess(any());
    }

    @Test
    void verify_withCorrectPassword_recordsSuccess() {
        givenIdentityExists();
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

        authService.verify(request(LOGIN, PASSWORD));

        verify(loginAttemptLimiter).recordSuccess(LOGIN);
        verify(loginAttemptLimiter, never()).recordFailure(any());
    }
}
