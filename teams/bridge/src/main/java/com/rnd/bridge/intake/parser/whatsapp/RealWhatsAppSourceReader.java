package com.rnd.bridge.intake.parser.whatsapp;

import com.rnd.bridge.intake.model.ChatMessage;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder for real WhatsApp reading. There is no official export API for personal
 * WhatsApp — "real" here means parsing a real exported .txt file from a configured directory
 * instead of a classpath fixture (see plan.md's rollout order, where WhatsApp goes real
 * first for exactly this reason). Not implemented yet; enabling
 * bridge.intake.whatsapp.mode=real activates this bean but every call fails until it's
 * filled in.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.whatsapp", name = "mode", havingValue = "real")
public class RealWhatsAppSourceReader implements WhatsAppSourceReader {

    @Override
    public List<ChatMessage> read() {
        throw new UnsupportedOperationException(
                "Real WhatsApp reading is not implemented yet - see plan.md's rollout order");
    }
}
