package com.rnd.bridge.intake.model;

import com.rnd.bridge.raw.model.RawSourceType;
import java.util.List;

/**
 * Persisted state for one chat group (a Slack thread, or a time-gap session), letting
 * ChatGroupStateReconciler recognize and extend the same group across separate intake runs
 * instead of minting an unrelated one every time a chat is re-read. See
 * ChatGroupStateReconciler's javadoc for how groupId/threadId/revision are used.
 * groupId equals threadId for threaded groups (there is no separate identity to track); for
 * time-gap groups it is a UUID minted once, when that session is created within a single
 * intake batch, then persisted so a later edit (same messageId, changed content) can still
 * find and update it - but time-gap grouping itself never reconnects to this group across a
 * later intake run based on time proximity alone (see ChatGroupStateReconciler).
 * threadId is null for time-gap groups.
 * correlationId is the stable UUID exposed downstream as SourceRecord/RawObjectEvent's
 * correlationId (Mind requires this to always be a UUID, never a Slack threadId or message
 * id) — minted once, when the group is first created, and copied forward unchanged on every
 * subsequent edit/retry/thread-continuation update, so Mind sees the same id for every
 * revision of the same logical group even though groupId itself is never exposed downstream.
 * messages is the full accumulated set for this group (old + new across every run that has
 * touched it so far), always kept sorted by source timestamp.
 * published means this group's current content has been landed/published at least once.
 * revision is the logical content version of this group - it only advances when a content
 * change (any edit that produces a different merged message set) supersedes a version that was
 * already confirmed published OR is currently claimed for publication (claimedRevision != null,
 * i.e. possibly already in flight to Kafka); any number of content changes against a version
 * that is neither confirmed published nor claimed collapse onto that same revision number
 * instead of each minting a new one, and an unchanged retry (identical content re-read) never
 * advances it either. A newly created group starts at -1 (see
 * ChatGroupStateReconciler.newGroupState) so its first real merge - always against an
 * unpublished, unclaimed (nonexistent) version - lands at revision 0, the same as any later
 * content change that lands while unpublished and unclaimed.
 * Internal bookkeeping only - never exposed on RawObjectEvent/the wire.
 * GroupStateStore.confirmPublished(expectedRevision) is the guard that stops a stale Kafka ACK
 * for an old revision from marking a since-superseded (edited) revision as published - see its
 * javadoc.
 * claimedRevision is the revision currently claimed for publication by
 * GroupStateStore.claimForPublication, or null if no claim is outstanding. It exists so two
 * concurrent execution paths (a timer callback and startup recovery, once those land) cannot
 * both decide to publish the same revision: only one atomic update can observe
 * claimedRevision != expectedRevision and win the claim. Cleared back to null once that
 * revision is either confirmed published or superseded by a new merge - see
 * GroupStateStore.claimForPublication and confirmPublished's javadoc.
 */
public record GroupState(
        String groupId,
        RawSourceType sourceType,
        String tenantId,
        String chatId,
        String threadId,
        String correlationId,
        List<MessageSnapshot> messages,
        boolean published,
        int revision,
        Integer claimedRevision) {
}
