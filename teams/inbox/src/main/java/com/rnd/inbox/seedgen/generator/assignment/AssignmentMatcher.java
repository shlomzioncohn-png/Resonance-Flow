package com.rnd.inbox.seedgen.generator.assignment;
import com.rnd.inbox.seedgen.generator.shared.IntArrayMath;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.task.TaskSeed;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Turns the target row *counts* {@link AssignmentGenerator} computed ({@link UserAssignmentLoad}/
 * {@link TeamAssignmentLoad}) into actual task-to-assignee {@link AssignmentRow}s (design doc
 * §4) — the missing "who gets which specific task" step.
 *
 * <p>Processes one task at a time (not a flat list of slots) so that "never give the same task
 * to the same user/team twice" (schema's {@code uq_assignments_task_user}/
 * {@code uq_assignments_task_team}) is a same-task check, not a global one. Within a task, each
 * slot goes to whichever entity (in its pool — user or team) currently has the largest remaining
 * target, skipping the entity already used on this task's other slot.
 *
 * <p>The first {@code sharedTaskCount} tasks in a tenant's slice (an arbitrary but deterministic
 * choice — task order carries no meaning here) get two slots instead of one; slot types
 * (team- vs. user-targeted) are handed out in that same fixed order until
 * {@code teamRowsTarget} is reached, matching {@link AssignmentGenerator}'s split exactly.
 *
 * <p><b>Uniqueness beats hitting the exact target.</b> A task's second slot needs a *distinct*
 * entity from its first — only possible in a tiny tenant with an extreme concentration draw.
 * When neither pool has a distinct candidate left within target, the search widens in this
 * order: team pool over target → user pool over target → drop the slot entirely (this task gets
 * one assignee instead of two). Dropping a slot is the one place a tenant's actual row count can
 * fall short of {@link AssignmentGenerator}'s target — always preferred over creating a
 * duplicate row, which the schema forbids outright.
 */
public final class AssignmentMatcher {

    private AssignmentMatcher() {
    }

    public static List<AssignmentRow> generate(
            List<TenantSeed> tenants, Map<TenantKey, List<TaskSeed>> tasksByTenant, AssignmentLoads loads) {

        Map<TenantKey, List<UserAssignmentLoad>> userLoadsByTenant = loads.userLoads().stream()
                .collect(Collectors.groupingBy(UserAssignmentLoad::tenantKey));
        Map<TenantKey, List<TeamAssignmentLoad>> teamLoadsByTenant = loads.teamLoads().stream()
                .collect(Collectors.groupingBy(TeamAssignmentLoad::tenantKey));

        List<AssignmentRow> rows = new ArrayList<>();
        for (TenantSeed tenant : tenants) {
            rows.addAll(matchTenant(
                    tenant,
                    tasksByTenant.getOrDefault(tenant.tenantKey(), List.of()),
                    userLoadsByTenant.getOrDefault(tenant.tenantKey(), List.of()),
                    teamLoadsByTenant.getOrDefault(tenant.tenantKey(), List.of())));
        }
        return List.copyOf(rows);
    }

    private static List<AssignmentRow> matchTenant(
            TenantSeed tenant, List<TaskSeed> tasks, List<UserAssignmentLoad> userLoads, List<TeamAssignmentLoad> teamLoads) {

        EntityPool users = EntityPool.of(userLoads, UserAssignmentLoad::userIndexInTenant, UserAssignmentLoad::rowCount);
        EntityPool teams = EntityPool.of(teamLoads, TeamAssignmentLoad::teamIndex, TeamAssignmentLoad::rowCount);

        int teamRowsTarget = teams.targetTotal();
        int totalRows = teamRowsTarget + users.targetTotal();
        int sharedTaskCount = totalRows - tasks.size();

        List<AssignmentRow> rows = new ArrayList<>();
        int teamSlotsAssigned = 0;

        for (int taskIndex = 0; taskIndex < tasks.size(); taskIndex++) {
            int numSlots = taskIndex < sharedTaskCount ? 2 : 1;
            int usedUserIdx = -1;
            int usedTeamIdx = -1;

            for (int slot = 0; slot < numSlots; slot++) {
                boolean preferTeam = teamSlotsAssigned < teamRowsTarget;

                Integer idx = preferTeam ? teams.pickWithinTarget(usedTeamIdx) : null;
                if (idx != null) {
                    teams.take(idx);
                    usedTeamIdx = idx;
                    teamSlotsAssigned++;
                    rows.add(new AssignmentRow(tenant.tierName(), tenant.tierIndex(), taskIndex, null, teams.idAt(idx)));
                    continue;
                }

                idx = users.pickWithinTarget(usedUserIdx);
                if (idx != null) {
                    users.take(idx);
                    usedUserIdx = idx;
                    rows.add(new AssignmentRow(tenant.tierName(), tenant.tierIndex(), taskIndex, users.idAt(idx), null));
                    continue;
                }

                // Neither pool has a distinct candidate within target — widen the search
                // (over-target borrow), team first, then user; see class Javadoc.
                idx = teams.pickAny(usedTeamIdx);
                if (idx != null) {
                    teams.take(idx);
                    usedTeamIdx = idx;
                    rows.add(new AssignmentRow(tenant.tierName(), tenant.tierIndex(), taskIndex, null, teams.idAt(idx)));
                    continue;
                }
                idx = users.pickAny(usedUserIdx);
                if (idx != null) {
                    users.take(idx);
                    usedUserIdx = idx;
                    rows.add(new AssignmentRow(tenant.tierName(), tenant.tierIndex(), taskIndex, users.idAt(idx), null));
                }

                // If idx is still null here: no distinct entity exists anywhere (both pools have
                // size <= 1) — drop the slot rather than duplicate. This task ends up with fewer
                // assignees than planned.
            }
        }
        return rows;
    }

    /**
     * A group of entities (a tenant's users, or its teams) each with a remaining target row
     * count — the two pools {@link #matchTenant} draws from, sharing one implementation instead
     * of duplicated per-pool logic.
     */
    private static final class EntityPool {
        private final int[] ids;
        private final int[] remaining;

        private EntityPool(int[] ids, int[] remaining) {
            this.ids = ids;
            this.remaining = remaining;
        }

        static <T> EntityPool of(List<T> loads, ToIntFunction<T> idFn, ToIntFunction<T> rowCountFn) {
            int[] ids = loads.stream().mapToInt(idFn).toArray();
            int[] remaining = loads.stream().mapToInt(rowCountFn).toArray();
            return new EntityPool(ids, remaining);
        }

        int idAt(int idx) {
            return ids[idx];
        }

        int targetTotal() {
            return IntArrayMath.sumOf(remaining);
        }

        void take(int idx) {
            remaining[idx]--;
        }

        /** Largest remaining value, excluding {@code excludeIdx} and any non-positive entry.
         * {@code null} if nothing qualifies. */
        Integer pickWithinTarget(int excludeIdx) {
            int best = -1;
            for (int i = 0; i < remaining.length; i++) {
                if (i == excludeIdx || remaining[i] <= 0) {
                    continue;
                }
                if (best == -1 || remaining[i] > remaining[best]) {
                    best = i;
                }
            }
            return best == -1 ? null : best;
        }

        /** Any index other than {@code excludeIdx}, regardless of its remaining value — the
         * last-resort "just give me a distinct entity" search. {@code null} if the pool has at
         * most one entity at all (no alternative exists). */
        Integer pickAny(int excludeIdx) {
            for (int i = 0; i < ids.length; i++) {
                if (i != excludeIdx) {
                    return i;
                }
            }
            return null;
        }
    }
}
