package com.rnd.inbox.seedgen.generator.task;
import com.rnd.inbox.seedgen.generator.user.UserSeed;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;

/**
 * One task's computed field values (design doc §6), before any row is actually built. Unlike
 * {@link TenantSeed}/{@link TeamSeed}/{@link UserSeed}, these are global draws, not per-tenant —
 * a task doesn't need to know which tenant it belongs to at this layer (the generator that
 * allocates {@code tenant.taskCount()} tasks to a tenant owns that, not the field values
 * themselves).
 *
 * @param status one of {@link com.rnd.inbox.seedgen.config.DatasetConfig#STATUS}'s keys
 * @param sourceType one of {@link com.rnd.inbox.seedgen.config.DatasetConfig#SOURCE_TYPE}'s keys
 * @param extractionProvenance one of {@link com.rnd.inbox.seedgen.config.DatasetConfig#EXTRACTION_PROVENANCE}'s keys
 * @param confidence required (non-null, 0-1) when {@code extractionProvenance} is {@code ml_inferred}; {@code null} otherwise
 * @param tier {@code null}, or one of {@link com.rnd.inbox.seedgen.config.DatasetConfig#TIER}'s non-NULL keys
 * @param roleContext {@code null}, or one of {@link com.rnd.inbox.seedgen.config.DatasetConfig#ROLE_CONTEXT}'s non-NULL keys
 * @param deadline see {@link DeadlineSeed}
 * @param isAvailable computed here from {@code status} (design doc §6) — Chain's real job, this is only a plausible stand-in
 * @param priorityScore {@code null}, or 1-10 (design doc §6 — deliberately independent of every other field)
 * @param urgency {@code null}, or 1-5 (same reasoning as {@code priorityScore})
 * @param effortEstimateMinutes {@code null}, or a positive number of minutes
 * @param effortEstimateConfidence always present (0-1) — low when {@code effortEstimateMinutes} is {@code null} (design doc §6)
 */
public record TaskSeed(
        String status,
        String sourceType,
        String extractionProvenance,
        Double confidence,
        String tier,
        String roleContext,
        DeadlineSeed deadline,
        boolean isAvailable,
        Integer priorityScore,
        Integer urgency,
        Integer effortEstimateMinutes,
        double effortEstimateConfidence) {

    public TaskSeed {
        requireNonBlank(status, "status");
        requireNonBlank(sourceType, "sourceType");
        requireNonBlank(extractionProvenance, "extractionProvenance");
        if (deadline == null) {
            throw new IllegalArgumentException("deadline must not be null — use DeadlineSeed.NONE");
        }
        requireUnitIntervalOrNull(confidence, "confidence");
        if (priorityScore != null && (priorityScore < 1 || priorityScore > 10)) {
            throw new IllegalArgumentException("priorityScore (" + priorityScore + ") must be between 1 and 10");
        }
        if (urgency != null && (urgency < 1 || urgency > 5)) {
            throw new IllegalArgumentException("urgency (" + urgency + ") must be between 1 and 5");
        }
        if (effortEstimateMinutes != null && effortEstimateMinutes <= 0) {
            throw new IllegalArgumentException(
                    "effortEstimateMinutes (" + effortEstimateMinutes + ") must be positive");
        }
        requireUnitInterval(effortEstimateConfidence, "effortEstimateConfidence");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireUnitInterval(double value, String name) {
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " (" + value + ") must be between 0 and 1");
        }
    }

    private static void requireUnitIntervalOrNull(Double value, String name) {
        if (value != null) {
            requireUnitInterval(value, name);
        }
    }
}
