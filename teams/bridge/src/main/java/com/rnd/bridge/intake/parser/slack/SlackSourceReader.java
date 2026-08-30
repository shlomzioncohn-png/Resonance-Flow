package com.rnd.bridge.intake.parser.slack;

import com.rnd.bridge.intake.model.ChatMessage;
import java.util.List;

/**
 * Reads raw Slack messages (pre session-grouping). Selected implementation (Fixture vs.
 * Real) is chosen by bridge.intake.slack.mode — see plan.md. SourceRecordLoader groups the
 * returned ChatMessages into sessions via ChatSessionGrouper.
 */
public interface SlackSourceReader {

    List<ChatMessage> read();
}
