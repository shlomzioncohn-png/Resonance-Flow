package com.rnd.inbox.seedgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetCsvWriterTest {

    @Test
    void writeAll_always_everyFileHasTheExpectedRowCount(@TempDir Path tempDir) throws IOException {
        GeneratedDataset dataset = SeedGenerator.generate();

        DatasetCsvWriter.writeAll(dataset, tempDir, Instant.now(), new Random());

        assertThat(dataLineCount(tempDir, "tenants.csv")).isEqualTo(dataset.tenants().size());
        assertThat(dataLineCount(tempDir, "teams.csv")).isEqualTo(dataset.teams().size());
        assertThat(dataLineCount(tempDir, "users.csv")).isEqualTo(dataset.users().size());
        assertThat(dataLineCount(tempDir, "assignments.csv")).isEqualTo(dataset.assignments().size());

        long affiliatedUsers = dataset.users().stream().filter(u -> !u.unaffiliated()).count();
        assertThat(dataLineCount(tempDir, "team_members.csv")).isEqualTo(affiliatedUsers);

        int totalTasks = dataset.tasksByTenant().values().stream().mapToInt(List::size).sum();
        assertThat(dataLineCount(tempDir, "tasks.csv")).isEqualTo(totalTasks);
    }

    @Test
    void writeAll_always_everyFileStartsWithItsHeader(@TempDir Path tempDir) throws IOException {
        DatasetCsvWriter.writeAll(SeedGenerator.generate(), tempDir, Instant.now(), new Random());

        assertThat(firstLine(tempDir, "tenants.csv")).isEqualTo("id,name,slug,status");
        assertThat(firstLine(tempDir, "teams.csv")).isEqualTo("id,tenant_id,name,team_lead_id,status");
        assertThat(firstLine(tempDir, "team_members.csv")).isEqualTo("team_id,user_id");
        assertThat(firstLine(tempDir, "users.csv")).isEqualTo("id,tenant_id,email,username,display_name,role,status");
        assertThat(firstLine(tempDir, "assignments.csv")).isEqualTo("task_id,user_id,team_id,assignment_role");
    }

    @Test
    void writeAll_always_everyAssignmentRowHasExactlyOneOfUserOrTeam(@TempDir Path tempDir) throws IOException {
        DatasetCsvWriter.writeAll(SeedGenerator.generate(), tempDir, Instant.now(), new Random());

        List<String> lines = Files.readAllLines(tempDir.resolve("assignments.csv"));
        for (String line : lines.subList(1, lines.size())) {
            String[] cols = line.split(",", -1);
            boolean hasUser = !cols[1].isEmpty();
            boolean hasTeam = !cols[2].isEmpty();
            assertThat(hasUser ^ hasTeam).as("line: %s", line).isTrue();
        }
    }

    @Test
    void writeAll_always_snoozedTasksHaveASnoozedUntilAndOthersDont(@TempDir Path tempDir) throws IOException {
        DatasetCsvWriter.writeAll(SeedGenerator.generate(), tempDir, Instant.now(), new Random());

        List<String> lines = Files.readAllLines(tempDir.resolve("tasks.csv"));
        int statusIdx = 9; // id,tenant_id,title,source_type,tier,role_context,urgency,effort_estimate_minutes,deadline,status,...
        int snoozedUntilIdx = 12;

        for (String line : lines.subList(1, lines.size())) {
            String[] cols = line.split(",", -1);
            boolean isSnoozed = cols[statusIdx].equals("snoozed");
            boolean hasSnoozedUntil = !cols[snoozedUntilIdx].isEmpty();
            assertThat(hasSnoozedUntil).as("line: %s", line).isEqualTo(isSnoozed);
        }
    }

    private static long dataLineCount(Path dir, String fileName) throws IOException {
        return Files.lines(dir.resolve(fileName)).count() - 1; // minus the header
    }

    private static String firstLine(Path dir, String fileName) throws IOException {
        return Files.readAllLines(dir.resolve(fileName)).get(0);
    }
}
