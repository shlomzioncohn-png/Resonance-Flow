package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance benchmark for TaskTraversalService against a 10,000-task / 20,000-edge graph
 * (Open Question 9): one large connected structure, not many small isolated components, so a
 * single traversal call plausibly touches a large fraction of the graph.
 *
 * Runs as part of the regular `mvn test` suite - no @Tag, no exclusion, no new profile (Open
 * Question 7).
 *
 * <p><b>Graph construction</b> (fixed seed = 42, fully deterministic):
 * <ul>
 *   <li>10,000 tasks, indices 0..9999.
 *   <li>Dependency edges (20,000 total), always pointing from a higher index to a lower one,
 *       so the graph is a DAG and a single weakly-connected component by construction:
 *       <ul>
 *         <li>Backbone: for i = 1..9999, one edge (task[i] depends on task[random(0..i-1)]) -
 *             9,999 edges, guarantees every task is reachable from/to the rest of the graph.
 *         <li>Extra density: 10,001 more edges (i = random(1..9999), j = random(0..i-1)), same
 *             backward-pointing rule, adds branching without introducing a cycle.
 *       </ul>
 *   <li>Hierarchy (separate structure, via parentTaskId), deliberately shaped with both a
 *       high-fan-out node and a deep chain - not a shallow, evenly-branched tree - so the
 *       disclosed findByParentTaskId linear-scan risk is actually exercised, not coincidentally
 *       avoided:
 *       <ul>
 *         <li>HUB (task index 0): 4,999 direct children (indices 1..4999). Branching factor:
 *             4,999.
 *         <li>CHAIN_ROOT (task index 5000): a single-child-per-level chain of depth 4,999
 *             (indices 5000..9999, each the parent of the next).
 *       </ul>
 *       Together these cover all 10,000 tasks under two separate hierarchy roots.
 * </ul>
 *
 * <p><b>Methodology:</b> {@value #WARMUP_ITERATIONS} warm-up iterations (discarded, to let the
 * JIT compile hot paths) followed by {@value #MEASURED_ITERATIONS} measured iterations per
 * traversal type. p95 is the 95th-percentile value of the sorted measured latencies. Every
 * result - and the JVM/OS environment - is printed to test output regardless of pass/fail: this
 * benchmark exists to get real numbers, not to be silently green, so the target is reported,
 * never asserted. Given InMemoryTaskDependencyRepository and InMemoryTaskRepository are copied
 * verbatim from Riki-Kaplan (out of scope to modify - see PLAN.md), a miss here is reported as
 * a finding, not "fixed" by touching either repository.
 */
class TaskTraversalPerformanceTest {

    private static final int TASK_COUNT = 10_000;
    private static final int EDGE_COUNT = 20_000;
    private static final long SEED = 42L;
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURED_ITERATIONS = 10;
    private static final long TARGET_P95_MILLIS = 300L;
    private static final int HUB_CHILD_COUNT = 4999;
    private static final int CHAIN_DEPTH = 4999;

    private static TaskTraversalService service;
    private static UUID hub;
    private static UUID chainRoot;
    private static UUID chainLeaf;
    private static UUID highIndexTask;
    private static UUID lowIndexTask;

    @BeforeAll
    static void buildGraphAndPrintEnvironment() {
        Random random = new Random(SEED);
        UUID[] ids = new UUID[TASK_COUNT];
        for (int i = 0; i < TASK_COUNT; i++) {
            ids[i] = UUID.randomUUID();
        }

        List<TaskDependency> edges = new ArrayList<>(EDGE_COUNT);
        for (int i = 1; i < TASK_COUNT; i++) {
            int dependsOn = random.nextInt(i);
            edges.add(new TaskDependency(ids[i], ids[dependsOn]));
        }
        while (edges.size() < EDGE_COUNT) {
            int i = 1 + random.nextInt(TASK_COUNT - 1);
            int j = random.nextInt(i);
            edges.add(new TaskDependency(ids[i], ids[j]));
        }

        UUID[] parentTaskId = new UUID[TASK_COUNT];
        for (int i = 1; i <= HUB_CHILD_COUNT; i++) {
            parentTaskId[i] = ids[0];
        }
        for (int i = HUB_CHILD_COUNT + 2; i < TASK_COUNT; i++) {
            parentTaskId[i] = ids[i - 1];
        }

        List<Task> tasks = new ArrayList<>(TASK_COUNT);
        for (int i = 0; i < TASK_COUNT; i++) {
            tasks.add(Task.builder()
                    .id(ids[i])
                    .parentTaskId(parentTaskId[i])
                    .ownerId("owner")
                    .build());
        }

        service = new TaskTraversalService(new InMemoryTaskRepository(tasks), new InMemoryTaskDependencyRepository(edges));

        hub = ids[0];
        chainRoot = ids[HUB_CHILD_COUNT + 1];
        chainLeaf = ids[TASK_COUNT - 1];
        highIndexTask = ids[TASK_COUNT - 1];
        lowIndexTask = ids[0];

        System.out.println("=== TaskTraversalPerformanceTest environment ===");
        System.out.println("java.version=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " availableProcessors=" + Runtime.getRuntime().availableProcessors()
                + " maxHeapMB=" + (Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        System.out.println("=== graph ===");
        System.out.println("tasks=" + tasks.size() + " edges=" + edges.size()
                + " hub fan-out=" + HUB_CHILD_COUNT + " chain depth=" + CHAIN_DEPTH);
        System.out.println("getAllDependencies(highest-index task) reaches "
                + service.getAllDependencies(highIndexTask).size() + " nodes");
        System.out.println("getAllDependents(lowest-index task) reaches "
                + service.getAllDependents(lowIndexTask).size() + " nodes");
    }

    @Test
    void graphIsBuiltAsSpecified() {
        assertEquals(HUB_CHILD_COUNT, service.getDirectChildren(hub).size());
        assertEquals(CHAIN_DEPTH, service.getAncestors(chainLeaf).size());
        // Both stress structures should reach a large fraction of the 10,000-task graph -
        // otherwise this benchmark wouldn't actually be exercising the disclosed risk.
        assertTrue(service.getAllDescendants(hub).size() >= HUB_CHILD_COUNT);
        assertTrue(service.getAllDescendants(chainRoot).size() >= CHAIN_DEPTH);
        assertTrue(service.getAllDependencies(highIndexTask).size() > 1000,
                "expected the dependency-axis stress node to reach a large fraction of the graph");
        assertTrue(service.getAllDependents(lowIndexTask).size() > 1000,
                "expected the dependents-axis stress node to reach a large fraction of the graph");
    }

    @Test
    void directDependencies_p95() {
        measureAndReport("getDirectDependencies", () -> service.getDirectDependencies(highIndexTask));
    }

    @Test
    void allDependencies_p95() {
        measureAndReport("getAllDependencies (dependency axis)", () -> service.getAllDependencies(highIndexTask));
    }

    @Test
    void directDependents_p95() {
        measureAndReport("getDirectDependents", () -> service.getDirectDependents(lowIndexTask));
    }

    @Test
    void allDependents_p95() {
        measureAndReport("getAllDependents (dependency axis)", () -> service.getAllDependents(lowIndexTask));
    }

    @Test
    void ancestors_p95() {
        measureAndReport("getAncestors (hierarchy axis, deep chain)", () -> service.getAncestors(chainLeaf));
    }

    @Test
    void directChildren_p95() {
        measureAndReport("getDirectChildren", () -> service.getDirectChildren(hub));
    }

    @Test
    void allDescendantsFanOut_p95() {
        measureAndReport("getAllDescendants (hierarchy axis, hub fan-out)", () -> service.getAllDescendants(hub));
    }

    @Test
    void allDescendantsDeepChain_p95() {
        measureAndReport("getAllDescendants (hierarchy axis, deep chain)", () -> service.getAllDescendants(chainRoot));
    }

    @Test
    void trace_p95() {
        measureAndReport("getTrace (both axes combined, from hub)", () -> service.getTrace(hub));
    }

    /**
     * Runs {@code traversal} {@value #WARMUP_ITERATIONS} times (discarded) then
     * {@value #MEASURED_ITERATIONS} times (measured), prints every sample plus the p95 and
     * pass/fail against {@value #TARGET_P95_MILLIS}ms - but does not assert on it. Whether a
     * miss here should fail the build is a separate decision from measuring and reporting it;
     * see the class Javadoc.
     */
    private void measureAndReport(String label, Supplier<?> traversal) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            traversal.get();
        }
        long[] millis = new long[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            long start = System.nanoTime();
            traversal.get();
            millis[i] = (System.nanoTime() - start) / 1_000_000;
        }
        Arrays.sort(millis);
        int p95Index = Math.min((int) Math.ceil(0.95 * MEASURED_ITERATIONS) - 1, MEASURED_ITERATIONS - 1);
        long p95 = millis[p95Index];
        boolean pass = p95 < TARGET_P95_MILLIS;

        System.out.printf(
                "[BENCHMARK] %-50s samples(ms)=%s p95=%dms target=%dms %s%n",
                label, Arrays.toString(millis), p95, TARGET_P95_MILLIS, pass ? "PASS" : "FAIL");
    }
}
