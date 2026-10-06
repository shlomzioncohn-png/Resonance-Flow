package com.rnd.inbox.seedgen;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import com.rnd.inbox.seedgen.generator.assignment.AssignmentRow;
import com.rnd.inbox.seedgen.generator.shared.RandomUtil;
import com.rnd.inbox.seedgen.generator.task.TaskSeed;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.user.UserSeed;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Writes a {@link GeneratedDataset} to six CSV files — one per table — ready for
 * {@code psql ... COPY table (col1, col2, ...) FROM 'file.csv' WITH (FORMAT csv, HEADER true)}.
 * Every column with a schema {@code DEFAULT} (timestamps, {@code assignments.id}) is left out of
 * both the header and the rows, so the {@code COPY} column list must name only what's here —
 * Postgres fills the rest in itself.
 *
 * <p>{@code now} and {@code random} are for {@code DeadlineMaterializer} and
 * {@code snoozed_until} — real, deliberately non-reproducible values (see
 * {@code DeadlineMaterializer}'s Javadoc); callers should pass {@link Instant#now()} and a fresh
 * {@code Random}, never {@link SeedGenerator}'s shared one.
 */
public final class DatasetCsvWriter {

    private DatasetCsvWriter() {
    }

    public static void writeAll(GeneratedDataset dataset, Path outputDir, Instant now, Random random) throws IOException {
        Files.createDirectories(outputDir);
        writeTenants(dataset, outputDir);
        writeTeams(dataset, outputDir);
        writeTeamMembers(dataset, outputDir);
        writeUsers(dataset, outputDir);
        writeTasks(dataset, outputDir, now, random);
        writeAssignments(dataset, outputDir);
    }

    private static void writeTenants(GeneratedDataset dataset, Path outputDir) throws IOException {
        try (BufferedWriter out = open(outputDir, "tenants.csv")) {
            writeLine(out, CsvUtil.row("id", "name", "slug", "status"));
            for (TenantSeed tenant : dataset.tenants()) {
                TenantKey key = tenant.tenantKey();
                writeLine(out, CsvUtil.row(
                        IdFactory.tenantId(key), ContentFactory.tenantName(key), ContentFactory.tenantSlug(key), "active"));
            }
        }
    }

    private static void writeTeams(GeneratedDataset dataset, Path outputDir) throws IOException {
        Map<String, Integer> leadUserIndexByTeam = leadUserIndexByTeam(dataset);

        try (BufferedWriter out = open(outputDir, "teams.csv")) {
            writeLine(out, CsvUtil.row("id", "tenant_id", "name", "team_lead_id", "status"));
            for (TeamSeed team : dataset.teams()) {
                TenantKey key = team.tenantKey();
                int leadUserIndex = leadUserIndexByTeam.get(teamMapKey(key, team.teamIndex()));
                writeLine(out, CsvUtil.row(
                        IdFactory.teamId(key, team.teamIndex()),
                        IdFactory.tenantId(key),
                        ContentFactory.teamName(team.teamIndex()),
                        IdFactory.userId(key, leadUserIndex),
                        "active"));
            }
        }
    }

    private static void writeTeamMembers(GeneratedDataset dataset, Path outputDir) throws IOException {
        try (BufferedWriter out = open(outputDir, "team_members.csv")) {
            writeLine(out, CsvUtil.row("team_id", "user_id"));
            for (UserSeed user : dataset.users()) {
                if (user.unaffiliated()) {
                    continue;
                }
                TenantKey key = user.tenantKey();
                writeLine(out, CsvUtil.row(
                        IdFactory.teamId(key, user.teamIndex()), IdFactory.userId(key, user.userIndexInTenant())));
            }
        }
    }

    private static void writeUsers(GeneratedDataset dataset, Path outputDir) throws IOException {
        try (BufferedWriter out = open(outputDir, "users.csv")) {
            writeLine(out, CsvUtil.row("id", "tenant_id", "email", "username", "display_name", "role", "status"));
            for (UserSeed user : dataset.users()) {
                TenantKey key = user.tenantKey();
                int idx = user.userIndexInTenant();
                writeLine(out, CsvUtil.row(
                        IdFactory.userId(key, idx),
                        IdFactory.tenantId(key),
                        ContentFactory.userEmail(key, idx),
                        ContentFactory.userUsername(key, idx),
                        ContentFactory.userDisplayName(idx),
                        ContentFactory.userRole(user.teamLead()),
                        "active"));
            }
        }
    }

    private static void writeTasks(GeneratedDataset dataset, Path outputDir, Instant now, Random random) throws IOException {
        try (BufferedWriter out = open(outputDir, "tasks.csv")) {
            writeLine(out, CsvUtil.row("id", "tenant_id", "title", "source_type", "tier", "role_context",
                    "urgency", "effort_estimate_minutes", "deadline", "status", "is_available", "priority_score",
                    "snoozed_until", "extraction_provenance", "confidence", "effort_estimate_confidence"));

            for (Map.Entry<TenantKey, List<TaskSeed>> entry : dataset.tasksByTenant().entrySet()) {
                TenantKey key = entry.getKey();
                List<TaskSeed> tasks = entry.getValue();
                for (int i = 0; i < tasks.size(); i++) {
                    TaskSeed task = tasks.get(i);
                    Instant deadline = DeadlineMaterializer.materialize(task.deadline(), now, random);
                    Instant snoozedUntil = task.status().equals("snoozed")
                            ? now.plus(Duration.ofDays(Math.round(RandomUtil.inRange(random, DatasetConfig.SNOOZED_UNTIL_DAYS_FROM_NOW))))
                            : null;

                    writeLine(out, CsvUtil.row(
                            IdFactory.taskId(key, i),
                            IdFactory.tenantId(key),
                            ContentFactory.taskTitle(task.sourceType(), i),
                            task.sourceType(),
                            task.tier(),
                            task.roleContext(),
                            task.urgency(),
                            task.effortEstimateMinutes(),
                            deadline,
                            task.status(),
                            task.isAvailable(),
                            task.priorityScore(),
                            snoozedUntil,
                            task.extractionProvenance(),
                            task.confidence(),
                            task.effortEstimateConfidence()));
                }
            }
        }
    }

    private static void writeAssignments(GeneratedDataset dataset, Path outputDir) throws IOException {
        try (BufferedWriter out = open(outputDir, "assignments.csv")) {
            writeLine(out, CsvUtil.row("task_id", "user_id", "team_id", "assignment_role"));

            Set<String> tasksAlreadyAssigned = new HashSet<>();
            for (AssignmentRow row : dataset.assignments()) {
                TenantKey key = row.tenantKey();
                String taskMapKey = key + ":" + row.taskIndexInTenant();
                String role = tasksAlreadyAssigned.add(taskMapKey) ? "owner" : "collaborator";

                writeLine(out, CsvUtil.row(
                        IdFactory.taskId(key, row.taskIndexInTenant()),
                        row.targetsTeam() ? null : IdFactory.userId(key, row.userIndexInTenant()),
                        row.targetsTeam() ? IdFactory.teamId(key, row.teamIndex()) : null,
                        role));
            }
        }
    }

    private static Map<String, Integer> leadUserIndexByTeam(GeneratedDataset dataset) {
        Map<String, Integer> leadByTeam = new HashMap<>();
        for (UserSeed user : dataset.users()) {
            if (user.teamLead()) {
                leadByTeam.put(teamMapKey(user.tenantKey(), user.teamIndex()), user.userIndexInTenant());
            }
        }
        return leadByTeam;
    }

    private static String teamMapKey(TenantKey tenant, int teamIndex) {
        return tenant + ":" + teamIndex;
    }

    private static BufferedWriter open(Path outputDir, String fileName) throws IOException {
        return Files.newBufferedWriter(outputDir.resolve(fileName), StandardCharsets.UTF_8);
    }

    private static void writeLine(BufferedWriter out, String line) throws IOException {
        out.write(line);
        out.newLine();
    }
}
