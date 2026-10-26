package com.rnd.inbox.auth.service;
import com.rnd.inbox.auth.dto.AuthUserResponseDto;
import com.rnd.inbox.auth.dto.AuthVerifyRequestDto;
import com.rnd.inbox.auth.exception.InvalidCredentialsException;
import com.rnd.inbox.auth.exception.TooManyLoginAttemptsException;
import com.rnd.inbox.user.model.AuthType;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserAuthIdentity;
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserAuthIdentityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAuthIdentityRepository userAuthIdentityRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(UserAuthIdentityRepository userAuthIdentityRepository, PasswordEncoder passwordEncoder,
                       LoginAttemptLimiter loginAttemptLimiter, Clock clock) {
        this.userAuthIdentityRepository = userAuthIdentityRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthUserResponseDto verify(AuthVerifyRequestDto request) {
        String login = UserAuthIdentity.normalizeIdentifier(request.getLogin());
        if (loginAttemptLimiter.isLocked(login)) {
            log.warn("Login rejected for {}: too many failed attempts", login);
            throw new TooManyLoginAttemptsException();
        }
        Optional<UserAuthIdentity> identity =
                userAuthIdentityRepository.findByAuthTypeAndIdentifier(AuthType.PASSWORD, login);

        // Hash even for unknown logins so response time doesn't reveal which exist.
        String hash = identity.map(UserAuthIdentity::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hash);

        if (identity.isEmpty() || !passwordMatches) {
            loginAttemptLimiter.recordFailure(login);
            log.warn("Failed login attempt for {}", login);
            throw new InvalidCredentialsException();
        }
        User user = identity.get().getUser();
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        loginAttemptLimiter.recordSuccess(login);
        user.setLastLoginAt(Instant.now(clock));
        return toResponseDto(user);
    }

    private AuthUserResponseDto toResponseDto(User user) {
        return new AuthUserResponseDto(user.getId(), user.getDisplayName(), user.getRole(), user.getTenant().getId());
    }
}
