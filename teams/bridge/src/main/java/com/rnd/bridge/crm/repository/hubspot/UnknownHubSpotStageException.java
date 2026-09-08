package com.rnd.bridge.crm.repository.hubspot;

import com.rnd.bridge.crm.model.DealStage;

/**
 * A HubSpot pipeline stage - either one returned by the startup pipeline
 * fetch, or one referenced by a deal's {@code dealstage} history - has no
 * known mapping to a {@link DealStage}. Deliberately not swallowed or
 * defaulted: see {@link HubSpotStageMapping}.
 */
public class UnknownHubSpotStageException extends RuntimeException {

    public UnknownHubSpotStageException(String message) {
        super(message);
    }
}
