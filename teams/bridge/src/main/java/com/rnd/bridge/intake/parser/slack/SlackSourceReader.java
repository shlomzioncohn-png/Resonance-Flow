package com.rnd.bridge.intake.parser.slack;

import com.rnd.bridge.intake.model.ChatMessage;
import java.time.Instant;
import java.util.List;

/**
 * Reads raw Slack messages (pre session-grouping). Selected implementation (Fixture vs.
 * Real) is chosen by bridge.intake.slack.mode — see plan.md. SourceRecordLoader routes the
 * returned ChatMessages through ChatGroupStateReconciler, which groups them into sessions
 * using ChatSessionGrouper's grouping helpers before persisting/publishing each one.
 * since is the incremental-sync cursor (null means "read everything") — see plan.md "Cursor
 * persistence". Only RealSlackSourceReader honors it today (via Slack's conversations.history
 * "oldest" parameter); FixtureSlackSourceReader ignores it, fixtures don't need incremental sync.
 */
public interface SlackSourceReader {

    List<ChatMessage> read(Instant since);
}
