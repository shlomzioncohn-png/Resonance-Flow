package com.rnd.inbox.auth.service;
import com.rnd.inbox.auth.dto.AuthUserResponseDto;
import com.rnd.inbox.auth.dto.AuthVerifyRequestDto;
import com.rnd.inbox.auth.exception.InvalidCredentialsException;
import com.rnd.inbox.user.model.AuthType;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserAuthIdentity;
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserAuthIdentityRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserAuthIdentityRepository userAuthIdentityRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(UserAuthIdentityRepository userAuthIdentityRepository, PasswordEncoder passwordEncoder,
                       Clock clock) {
        this.userAuthIdentityRepository = userAuthIdentityRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthUserResponseDto verify(AuthVerifyRequestDto request) {
        String login = UserAuthIdentity.normalizeIdentifier(request.getLogin());
        Optional<UserAuthIdentity> identity =
                userAuthIdentityRepository.findByAuthTypeAndIdentifier(AuthType.PASSWORD, login);

        // Hash even for unknown logins so response time doesn't reveal which exist.
        String hash = identity.map(UserAuthIdentity::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hash);

        if (identity.isEmpty() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        User user = identity.get().getUser();
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        user.setLastLoginAt(Instant.now(clock));
        return toResponseDto(user);
    }

    private AuthUserResponseDto toResponseDto(User user) {
        return new AuthUserResponseDto(user.getId(), user.getDisplayName(), user.getRole(), user.getTenant().getId());
    }
}
