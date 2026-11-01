package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.raw.model.RawSourceType;
import java.util.Optional;

/**
 * Resolution helpers for {@link ChatGroupStateReconciler}: the read-only question "which group
 * does this message currently live in?", kept separate from the merge/factory concerns so the
 * reconciler's real-time and deletion paths can ask it without mixing in any mutation. Touches
 * only {@link GroupStateStore}'s load/index lookups - never writes.
 */
class ChatGroupResolver {

    private final GroupStateStore groupStateStore;

    ChatGroupResolver(GroupStateStore groupStateStore) {
        this.groupStateStore = groupStateStore;
    }

    /**
     * Resolves which group a message currently lives in for the purpose of an edit or a
     * deletion, trying a direct load first (succeeds only when messageId is itself a group's own
     * groupId - i.e. a thread's root, since groupId==threadId==root's ts for threaded groups;
     * never a time-gap group, whose groupId is an unrelated minted UUID) before falling back to
     * the messageId index (resolves the ordinary non-thread case, and a reply's id - replies are
     * indexed to their thread group, unlike the root - see GroupStateStore.writeMessageIndex).
     * This order is what fixes the pre-existing thread-root edit bug: GroupStateStore.
     * writeMessageIndex deliberately never indexes a thread group under its own groupId (the
     * root), so findGroupIdForMessage(rootTs) alone permanently resolves to the root's ORIGINAL
     * channel (non-thread) group - see GroupStateStore's javadoc and
     * ChatGroupFactory.newThreadGroupState. The direct load bypasses that stale index
     * entirely whenever a thread group actually exists at that id, so this method alone resolves
     * to exactly the thread group for a single edit/delete lookup; a root edit/delete also
     * needing to reach the channel group's own copy (see §2 of docs/plans/slack-superseded-fix.md)
     * is handled by a second, explicit lookup alongside this one in reconcileNonThreadedMessage/
     * removeMessage, not by widening what this method itself returns.
     * The caller inspects the resolved GroupState's own threadId()/groupId() fields (see
     * isThreadGroup) to decide routing - never which lookup succeeded.
     */
    Optional<GroupState> resolveCurrentGroupId(RawSourceType sourceType, String tenantId, String messageId) {
        Optional<GroupState> direct = groupStateStore.load(sourceType, tenantId, messageId);
        if (direct.isPresent()) {
            return direct;
        }
        return groupStateStore.findGroupIdForMessage(sourceType, tenantId, messageId)
                .flatMap(groupId -> groupStateStore.load(sourceType, tenantId, groupId));
    }

    /** A group is a thread group iff threadId() != null && groupId().equals(threadId()) -
     * true regardless of whether it was reached via resolveCurrentGroupId's direct-load (root)
     * or index-fallback (reply) branch, and false for every ordinary time-gap group (see
     * resolveCurrentGroupId's javadoc). */
    boolean isThreadGroup(GroupState state) {
        return state.threadId() != null && state.groupId().equals(state.threadId());
    }

    /**
     * Resolves the channel (non-thread) group that still holds messageId's own index entry, if
     * that is a different group than the thread already resolved for it - the same filter idiom
     * migratedRootSnapshot already uses (findGroupIdForMessage(...).filter(id -> !id.equals(...))).
     * Only ever non-empty for messageId == a thread's own root (threadId==groupId==root's id, see
     * GroupState's javadoc): a reply's id is indexed to the thread itself, so this always returns
     * empty for a reply edit/delete. Used by reconcileNonThreadedMessage/removeMessage to also
     * reach the channel group's own still-living copy of the root (see
     * docs/plans/slack-superseded-fix.md §2) alongside resolveCurrentGroupId's single-group
     * resolution, rather than widening what that method itself returns.
     */
    Optional<String> findChannelGroupIfDifferentFromThread(
            RawSourceType sourceType, String tenantId, String messageId, String threadGroupId) {
        return groupStateStore.findGroupIdForMessage(sourceType, tenantId, messageId)
                .filter(id -> !id.equals(threadGroupId));
    }
}
