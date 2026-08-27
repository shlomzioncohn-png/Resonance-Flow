package com.rnd.bridge.intake.parser.email;

import com.rnd.bridge.intake.model.SourceRecord;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder for the real Gmail API reader (OAuth2 + users.messages.list/get, historyId for
 * incremental sync — see plan.md). Not implemented yet; enabling bridge.intake.email.mode=real
 * activates this bean but every call fails until it's filled in.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.email", name = "mode", havingValue = "real")
public class RealEmailSourceReader implements EmailSourceReader {

    @Override
    public List<SourceRecord> read() {
        throw new UnsupportedOperationException(
                "Real Gmail reading is not implemented yet - see plan.md's rollout order");
    }
}
