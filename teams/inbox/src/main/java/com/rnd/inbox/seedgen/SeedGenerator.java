package com.rnd.inbox.seedgen;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import com.rnd.inbox.seedgen.generator.assignment.AssignmentGenerator;
import com.rnd.inbox.seedgen.generator.assignment.AssignmentLoads;
import com.rnd.inbox.seedgen.generator.assignment.AssignmentMatcher;
import com.rnd.inbox.seedgen.generator.assignment.AssignmentRow;
import com.rnd.inbox.seedgen.generator.task.TaskGenerator;
import com.rnd.inbox.seedgen.generator.task.TaskSeed;
import com.rnd.inbox.seedgen.generator.task.TaskTenantSlicer;
import com.rnd.inbox.seedgen.generator.team.TeamGenerator;
import com.rnd.inbox.seedgen.generator.team.TeamSeed;
import com.rnd.inbox.seedgen.generator.tenant.TenantGenerator;
import com.rnd.inbox.seedgen.generator.shared.TenantKey;
import com.rnd.inbox.seedgen.generator.tenant.TenantSeed;
import com.rnd.inbox.seedgen.generator.user.UserGenerator;
import com.rnd.inbox.seedgen.generator.user.UserSeed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The "conductor" — runs every generation stage in the one fixed order that makes the whole
 * dataset reproducible (design doc §1): a single {@link Random}, seeded once from
 * {@link DatasetConfig#SEED}, threaded through in this exact sequence. Change the order (or
 * insert/remove a random draw anywhere upstream) and every seed downstream of that point draws a
 * different sequence of values — this method's body *is* the reproducibility contract.
 */
public final class SeedGenerator {

    private SeedGenerator() {
    }

    public static GeneratedDataset generate() {
        Random random = new Random(DatasetConfig.SEED);

        List<TenantSeed> tenants = TenantGenerator.generate();
        List<TeamSeed> teams = TeamGenerator.generate(tenants, random);
        List<UserSeed> users = UserGenerator.generate(tenants, teams);
        List<TaskSeed> tasks = TaskGenerator.generate(DatasetConfig.TOTAL_TASKS, random);
        Map<TenantKey, List<TaskSeed>> tasksByTenant = TaskTenantSlicer.slice(tenants, tasks);
        AssignmentLoads loads = AssignmentGenerator.generate(tenants, teams, users, random);
        List<AssignmentRow> assignments = AssignmentMatcher.generate(tenants, tasksByTenant, loads);

        return new GeneratedDataset(tenants, teams, users, tasksByTenant, assignments);
    }

    /** Runs the full generator, writes the six CSV files (design doc's final stage), and prints
     * a summary. Output defaults to {@code teams/inbox/seeds/} (found regardless of the working
     * directory — see {@link #defaultOutputDir()}), or {@code args[0]} if given. */
    public static void main(String[] args) throws IOException {
        GeneratedDataset dataset = generate();

        int totalTasks = dataset.tasksByTenant().values().stream().mapToInt(List::size).sum();
        int totalUsers = dataset.users().size();
        long unaffiliated = dataset.users().stream().filter(UserSeed::unaffiliated).count();

        System.out.println("Tenants:          " + dataset.tenants().size());
        System.out.println("Teams:            " + dataset.teams().size());
        System.out.println("Users:            " + totalUsers + " (" + unaffiliated + " unaffiliated)");
        System.out.println("Tasks:            " + totalTasks);
        System.out.println("Assignment rows:  " + dataset.assignments().size());

        Path outputDir = args.length > 0 ? Path.of(args[0]) : defaultOutputDir();
        DatasetCsvWriter.writeAll(dataset, outputDir, Instant.now(), new Random());
        System.out.println("CSV files written to: " + outputDir.toAbsolutePath().normalize());
    }

    /**
     * Finds {@code teams/inbox/seeds} regardless of the JVM's working directory — which
     * {@code mvn} and an IDE "Run" button don't agree on (Maven starts in the module itself;
     * IntelliJ's default "Run" button starts in the repo root instead). A plain
     * {@code Path.of("seeds")} lands wherever the process happens to be started from, silently
     * writing to the wrong place — which is exactly what happened before this method existed.
     *
     * <p>Instead this looks for this module's own root — a folder literally named {@code inbox}
     * directly inside one named {@code teams} — first as a subfolder *below* the working
     * directory (the repo-root case), then by walking *upward* through its ancestors (the
     * module-root-or-below case). Either search finds the same folder regardless of where the
     * process was started from.
     */
    private static Path defaultOutputDir() {
        Path cwd = Path.of("").toAbsolutePath();

        Path belowCwd = cwd.resolve("teams").resolve("inbox");
        if (Files.isDirectory(belowCwd)) {
            return belowCwd.resolve("seeds");
        }

        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path parent = dir.getParent();
            if ("inbox".equals(nameOf(dir)) && parent != null && "teams".equals(nameOf(parent))) {
                return dir.resolve("seeds");
            }
        }

        return cwd.resolve("seeds");
    }

    private static String nameOf(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? null : fileName.toString();
    }
}
