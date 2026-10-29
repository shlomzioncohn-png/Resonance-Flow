package com.rnd.bff.service;

import com.rnd.bff.client.InboxUserClient;
import com.rnd.bff.client.InboxUserRecord;
import com.rnd.bff.exception.InboxRejectedException;
import com.rnd.bff.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The signed-in user's tenant, read from their own Inbox record - the tenant sent
 * to Chain is never a parameter from the frontend.
 */
@Service
public class UserTenantResolver {

    private final InboxUserClient inboxUserClient;
    private final String devActorId;

    public UserTenantResolver(InboxUserClient inboxUserClient,
                              @Value("${inbox.dev-actor-id:}") String devActorId) {
        this.inboxUserClient = inboxUserClient;
        this.devActorId = devActorId;
    }

    public String tenantOf(String userId) {
        String actorId = devActorId.isBlank() ? userId : devActorId;
        InboxUserRecord user;
        try {
            user = inboxUserClient.getUser(actorId, actorId);
        } catch (HttpClientErrorException ex) {
            throw new InboxRejectedException(ex.getStatusCode().value() == 403 ? 403 : 401,
                    "Couldn't identify the signed-in user");
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("inbox", "Inbox is unavailable", ex);
        }
        if (user == null || user.getTenant() == null || user.getTenant().getId() == null) {
            throw new ServiceUnavailableException("inbox", "Inbox didn't return the user's tenant");
        }
        return user.getTenant().getId();
    }
}
