package com.rnd.bridge.intake.parser.slack;

import com.rnd.bridge.intake.model.ChatMessage;
import java.time.Instant;
import java.util.List;

/**
 * Reads raw Slack messages (pre session-grouping). Selected implementation (Fixture vs.
 * Real) is chosen by bridge.intake.slack.mode — see plan.md. SourceRecordLoader groups the
 * returned ChatMessages into sessions via ChatSessionGrouper.
 * since is the incremental-sync cursor (null means "read everything") — see plan.md "Cursor
 * persistence". Only RealSlackSourceReader honors it today (via Slack's conversations.history
 * "oldest" parameter); FixtureSlackSourceReader ignores it, fixtures don't need incremental sync.
 */
public interface SlackSourceReader {

    List<ChatMessage> read(Instant since);
}
