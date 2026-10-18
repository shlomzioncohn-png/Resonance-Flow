package com.rnd.bridge.intake.parser;

import com.rnd.bridge.intake.model.SourceRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups a flat list of {@link SourceRecord}s (body/transcript + every attachment, however many
 * separate records a reader split a logical item into) into one sublist per correlationId - the
 * unit {@code UnstructuredIntakeLane} now publishes as exactly one {@code RawObjectEvent} per
 * group, never a partial one. Preserves encounter order of both groups and records within a
 * group. Every unstructured source already produces all of a logical item's records together in
 * one call (Email/WhatsApp build body+attachments in one pass; Calendar/Drive never split;
 * Slack's reconciler returns a batch's complete new/changed set per group - see
 * ChatGroupStateReconciler.mergeAndMaybePublish), so this grouping step is purely mechanical -
 * it never needs to wait across calls for a group to "complete".
 */
public final class SourceRecordGrouper {

    private SourceRecordGrouper() {
    }

    public static List<List<SourceRecord>> groupByCorrelationId(List<SourceRecord> records) {
        Map<String, List<SourceRecord>> byCorrelationId = new LinkedHashMap<>();
        for (SourceRecord record : records) {
            byCorrelationId.computeIfAbsent(record.correlationId(), id -> new ArrayList<>()).add(record);
        }
        return byCorrelationId.values().stream().map(List::copyOf).toList();
    }
}
