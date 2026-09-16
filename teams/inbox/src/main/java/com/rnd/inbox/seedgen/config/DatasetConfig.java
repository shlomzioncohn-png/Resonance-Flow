package com.rnd.inbox.seedgen.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for every number in the realistic 100,000-task dataset design.
 *
 * <p>Mirrors {@code teams/inbox/docs/Task_Dataset_Design.md} (and its Hebrew twin,
 * {@code Task_Dataset_Design.he.md}) section by section. If a number changes, it changes here
 * and in the docs together — the two are never allowed to drift apart. Every subsequent
 * generation stage (tenants, teams, users, tasks, assignments) reads its parameters from this
 * class alone; nothing is hard-coded downstream.
 *
 * <p>Nothing here is random on its own — {@link #SEED} feeds every random draw the generator
 * makes, so the same run always reproduces the exact same dataset (see design doc §1).
 */
public final class DatasetConfig {

    private DatasetConfig() {
    }

    /** Fixed seed for every random draw in the generator — reproducibility (design doc §1). */
    public static final long SEED = 12345L;

    /** Exact total across all 100 tenants (design doc §1–§2). */
    public static final int TOTAL_TASKS = 100_000;

    /** Map key representing NULL in a percentage table (e.g. {@link #TIER}, {@link #ROLE_CONTEXT}). */
    public static final String NULL_KEY = "__NULL__";

    /** An inclusive [min, max] integer range, e.g. a task or user count. */
    public record IntRange(int min, int max) {
        public IntRange {
            if (min > max) {
                throw new IllegalArgumentException("min (" + min + ") > max (" + max + ")");
            }
        }
    }

    /** An inclusive [min, max] double range, e.g. a rate or a Pareto shape parameter. */
    public record DoubleRange(double min, double max) {
        public DoubleRange {
            if (min > max) {
                throw new IllegalArgumentException("min (" + min + ") > max (" + max + ")");
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // §2 — Tenant size tiers
    // ------------------------------------------------------------------------------------------

    /**
     * One tenant-size tier. {@code tasks} is {@code null} only for the Mega tier, whose task
     * count is not drawn from a range — it absorbs whatever remains so the grand total is
     * exactly {@link #TOTAL_TASKS} (design doc §1, "Exact total").
     */
    public record TenantTier(String name, int tenantCount, IntRange tasks, IntRange users) {
    }

    public static final TenantTier MEGA =
            new TenantTier("Mega", 1, null, new IntRange(180, 220));
    public static final TenantTier ENTERPRISE =
            new TenantTier("Enterprise", 4, new IntRange(3_500, 6_500), new IntRange(40, 90));
    public static final TenantTier LARGE =
            new TenantTier("Large", 15, new IntRange(900, 2_500), new IntRange(15, 40));
    public static final TenantTier MEDIUM =
            new TenantTier("Medium", 30, new IntRange(300, 1_200), new IntRange(5, 15));
    public static final TenantTier SMALL =
            new TenantTier("Small", 35, new IntRange(100, 700), new IntRange(2, 8));
    public static final TenantTier MICRO_TRIAL =
            new TenantTier("Micro/Trial", 15, new IntRange(2, 120), new IntRange(1, 3));

    /** All six tiers, Mega first — matches the table order in the design doc. */
    public static final List<TenantTier> TENANT_TIERS =
            List.of(MEGA, ENTERPRISE, LARGE, MEDIUM, SMALL, MICRO_TRIAL);

    // ------------------------------------------------------------------------------------------
    // §5 — Team hierarchy within a tenant
    // ------------------------------------------------------------------------------------------

    /**
     * Edge case: flat share of each tenant's users with no team at all (no {@code team_members}
     * row) — an exec, a freelancer, a not-yet-onboarded hire. Global, not per-tenant (design doc
     * §5). Distinct from {@link #TEAM_ASSIGNMENT_RATE}, which is about task assignment, not team
     * membership.
     */
    public static final double UNAFFILIATED_USER_SHARE = 0.05;

    /** Size of a single team, in members. Not uniform across a tenant's teams. */
    public static final IntRange TEAM_SIZE = new IntRange(2, 12);

    /**
     * Per-tenant average team size, drawn once per tenant and used only to derive how many
     * teams that tenant needs ({@code round(tenantUsers / avgTeamSize)}, minimum 1) — the
     * teams' actual individual sizes still come from {@link #TEAM_SIZE} with remainder
     * correction against the tenant's real user count (design doc §5).
     */
    public static final IntRange AVG_TEAM_SIZE = new IntRange(4, 8);

    // ------------------------------------------------------------------------------------------
    // §4 — Task-to-user / task-to-team distribution within a tenant (3 steps, per tenant)
    // ------------------------------------------------------------------------------------------

    /** Step 1 — share of a tenant's tasks that get more than one assignee. */
    public static final DoubleRange SHARING_RATE = new DoubleRange(0.05, 0.35);

    /** Step 2 — share of assignment rows that target a team (`team_id`) instead of a user. */
    public static final DoubleRange TEAM_ASSIGNMENT_RATE = new DoubleRange(0.10, 0.40);

    /**
     * Step 3 — Pareto/power-law shape parameter. Applied identically to spread both the
     * user-targeted pool across a tenant's users and the team-targeted pool across its teams
     * (same value for both — a tenant's shape is consistent whichever pool it lands in).
     * Low = closer to even; high = a handful hold most of the rows.
     */
    public static final DoubleRange CONCENTRATION = new DoubleRange(0.5, 2.5);

    // ------------------------------------------------------------------------------------------
    // §6 — Field-level distributions (global across all 100,000 tasks, not per tenant)
    // ------------------------------------------------------------------------------------------

    /** {@code tasks.status} — percentages sum to 100. */
    public static final Map<String, Double> STATUS = percentMap(
            "done", 45,
            "in_progress", 15,
            "new", 12,
            "approved", 8,
            "in_review", 8,
            "snoozed", 5,
            "rejected", 4,
            "cancelled", 3
    );

    /** {@code tasks.source_type} — percentages sum to 100. */
    public static final Map<String, Double> SOURCE_TYPE = percentMap(
            "email", 20,
            "slack", 18,
            "github_issue", 15,
            "manual", 12,
            "meeting", 10,
            "crm_deal", 8,
            "github_pr", 7,
            "doc", 4,
            "calendar", 3,
            "whatsapp", 2,
            "scanned_doc", 1
    );

    /** {@code tasks.extraction_provenance} — percentages sum to 100 (never null). */
    public static final Map<String, Double> EXTRACTION_PROVENANCE = percentMap(
            "ml_inferred", 45,
            "etl", 35,
            "manual", 20
    );

    /** Confidence range for {@code ml_inferred} tasks — required by schema when provenance is ml_inferred. */
    public static final DoubleRange ML_INFERRED_CONFIDENCE = new DoubleRange(0.4, 0.99);

    /** Edge-case tail: near-guess ML confidence (design doc §6, edge cases). */
    public static final DoubleRange ML_INFERRED_LOW_CONFIDENCE_TAIL = new DoubleRange(0.4, 0.5);
    public static final double ML_INFERRED_LOW_CONFIDENCE_TAIL_SHARE = 0.05;

    /** {@code tasks.tier} — nullable; NULL bucket + percentages, sums to 100. */
    public static final Map<String, Double> TIER = percentMap(
            NULL_KEY, 20,
            "operational", 52,
            "tactical", 20,
            "strategic", 8
    );

    /** {@code tasks.role_context} — nullable; NULL bucket + percentages, sums to 100. */
    public static final Map<String, Double> ROLE_CONTEXT = percentMap(
            NULL_KEY, 20,
            "developer", 28,
            "manager", 16,
            "field_engineer", 12,
            "salesperson", 12,
            "higher_manager", 8,
            "ceo", 4
    );

    /** {@code tasks.deadline} buckets — percentages sum to 100. */
    public static final String DEADLINE_NONE = "none";
    public static final String DEADLINE_OVERDUE = "overdue";
    public static final String DEADLINE_NEXT_7_DAYS = "next_7_days";
    public static final String DEADLINE_NEXT_30_DAYS = "next_30_days";
    public static final String DEADLINE_FURTHER_OUT = "further_out";

    public static final Map<String, Double> DEADLINE = percentMap(
            DEADLINE_NONE, 30,
            DEADLINE_OVERDUE, 15,
            DEADLINE_NEXT_7_DAYS, 20,
            DEADLINE_NEXT_30_DAYS, 20,
            DEADLINE_FURTHER_OUT, 15
    );

    /**
     * Within {@link #DEADLINE_NEXT_7_DAYS}, the split between "due in a few hours" (same-day
     * urgency) and "due in a few days" — both plausible in a live system (design doc §6).
     */
    public static final double DEADLINE_NEXT_7_DAYS_WITHIN_HOURS_SHARE = 0.35;

    /** Edge-case tail: extremely overdue (6+ months), not just recently past due. */
    public static final double DEADLINE_EXTREME_OVERDUE_SHARE = 0.01;

    /**
     * Actual offsets from "now" for each deadline bucket, used only when materializing a real
     * timestamp from a {@code DeadlineSeed} (a later, deliberately non-reproducible step — see
     * {@code DeadlineSeed}'s Javadoc). Bucket boundaries don't overlap, so a materialized
     * timestamp always lands back in the bucket it came from.
     */
    public static final IntRange DEADLINE_RECENT_OVERDUE_DAYS_AGO = new IntRange(1, 14);
    public static final IntRange DEADLINE_EXTREME_OVERDUE_DAYS_AGO = new IntRange(180, 365);
    public static final IntRange DEADLINE_WITHIN_HOURS_FROM_NOW = new IntRange(1, 12);
    public static final IntRange DEADLINE_WITHIN_7_DAYS_FROM_NOW = new IntRange(1, 7);
    public static final IntRange DEADLINE_WITHIN_30_DAYS_FROM_NOW = new IntRange(8, 30);
    public static final IntRange DEADLINE_FURTHER_OUT_DAYS_FROM_NOW = new IntRange(31, 180);

    /** How far in the future {@code tasks.snoozed_until} lands when {@code status = 'snoozed'} —
     * required by the schema's {@code chk_tasks_snoozed_until} constraint, not part of §6's
     * original field list (a gap noticed while building the CSV export). */
    public static final IntRange SNOOZED_UNTIL_DAYS_FROM_NOW = new IntRange(1, 14);

    /**
     * {@code tasks.is_available} — computed by Chain, materialized here (design doc §6).
     * For a "closed" status (done/cancelled/rejected/snoozed), always {@code false}.
     * For an "open" status, this is the share that is {@code true}; the rest ({@code false})
     * is the "blocked task" edge case.
     */
    public static final List<String> CLOSED_STATUSES = List.of("done", "cancelled", "rejected", "snoozed");
    public static final double OPEN_STATUS_AVAILABLE_SHARE = 0.75;

    /** {@code tasks.priority_score} — share with a value (1–10); rest is NULL. Core field, weighted high. */
    public static final double PRIORITY_SCORE_PRESENT_SHARE = 0.85;
    public static final IntRange PRIORITY_SCORE_RANGE = new IntRange(1, 10);
    /** Edge-case tail: exact boundary values (1 or 10) among scored tasks. */
    public static final double PRIORITY_SCORE_BOUNDARY_TAIL_SHARE = 0.025;

    /** {@code tasks.urgency} — share with a value (1–5); rest is NULL. */
    public static final double URGENCY_PRESENT_SHARE = 0.70;
    public static final IntRange URGENCY_RANGE = new IntRange(1, 5);

    /**
     * {@code tasks.effort_estimate_minutes} / {@code effort_estimate_confidence}. When no
     * estimate is entered, confidence is set to the lowest end of its range instead of NULL —
     * it marks the default a downstream algorithm will substitute as a guess, not a real
     * estimate (design doc §6).
     */
    public static final double EFFORT_ESTIMATE_PRESENT_SHARE = 0.60;
    /** A quarter hour to a full 8-hour workday. */
    public static final IntRange EFFORT_ESTIMATE_MINUTES = new IntRange(15, 480);
    public static final DoubleRange EFFORT_ESTIMATE_CONFIDENCE_WHEN_PRESENT = new DoubleRange(0.5, 0.95);
    public static final DoubleRange EFFORT_ESTIMATE_CONFIDENCE_WHEN_ABSENT = new DoubleRange(0.0, 0.1);

    /** Edge-case tail: "minimal" rows — most optional fields NULL at once. */
    public static final double MINIMAL_ROW_SHARE = 0.02;

    // ------------------------------------------------------------------------------------------

    private static Map<String, Double> percentMap(Object... namePercentPairs) {
        if (namePercentPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Expected name/percent pairs");
        }
        Map<String, Double> map = new LinkedHashMap<>();
        double total = 0;
        for (int i = 0; i < namePercentPairs.length; i += 2) {
            String name = (String) namePercentPairs[i];
            double percent = ((Number) namePercentPairs[i + 1]).doubleValue();
            map.put(name, percent);
            total += percent;
        }
        if (Math.abs(total - 100.0) > 0.001) {
            throw new IllegalStateException("Percentages must sum to 100, got " + total + " for " + map.keySet());
        }
        return Map.copyOf(map);
    }
}
