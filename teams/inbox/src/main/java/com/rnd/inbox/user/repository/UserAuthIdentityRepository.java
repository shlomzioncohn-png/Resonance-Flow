package com.rnd.inbox.user.repository;
import com.rnd.inbox.user.model.AuthType;
import com.rnd.inbox.user.model.UserAuthIdentity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserAuthIdentityRepository extends JpaRepository<UserAuthIdentity, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<UserAuthIdentity> findByAuthTypeAndIdentifier(AuthType authType, String identifier);

    boolean existsByAuthTypeAndIdentifier(AuthType authType, String identifier);
}
