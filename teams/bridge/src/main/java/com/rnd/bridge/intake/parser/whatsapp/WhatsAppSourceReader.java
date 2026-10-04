package com.rnd.bridge.intake.parser.whatsapp;

import com.rnd.bridge.intake.model.ChatMessage;
import java.util.List;

/**
 * Reads raw WhatsApp messages (pre session-grouping). Selected implementation (Fixture vs.
 * Real) is chosen by bridge.intake.whatsapp.mode — see plan.md. There is no official export
 * API for personal WhatsApp, so "real" here still means reading an exported .txt file, just
 * from a real, non-fixture location — see RealWhatsAppSourceReader.
 */
public interface WhatsAppSourceReader {

    List<ChatMessage> read();
}
