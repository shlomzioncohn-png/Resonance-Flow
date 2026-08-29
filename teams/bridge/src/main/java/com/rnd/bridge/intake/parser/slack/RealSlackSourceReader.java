package com.rnd.bridge.intake.parser.slack;

import com.rnd.bridge.intake.model.ChatMessage;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder for the real Slack Web API reader (conversations.history, OAuth bot token,
 * cursor-based pagination — see plan.md). Not implemented yet; enabling
 * bridge.intake.slack.mode=real activates this bean but every call fails until it's filled in.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.slack", name = "mode", havingValue = "real")
public class RealSlackSourceReader implements SlackSourceReader {

    @Override
    public List<ChatMessage> read() {
        throw new UnsupportedOperationException(
                "Real Slack reading is not implemented yet - see plan.md's rollout order");
    }
}
