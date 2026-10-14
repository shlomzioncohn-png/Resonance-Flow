package com.rnd.chain.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #445 Phase 3 guardrail: every public mutator on the classes below MUST be
 * {@code @Transactional}, because {@link TaskCounterUpdater}'s {@code requestPublishAfterCommit}
 * silently drops a scheduling-update publish when there is no active transaction
 * ({@code fallbackExecution=false}, a locked decision - see that method's Javadoc for why "drop
 * silently" was chosen over "publish anyway").
 *
 * <p>That trade-off is only safe if "no active transaction" never actually happens in production,
 * which depends entirely on every trigger method being {@code @Transactional} - a missing
 * annotation would silently and permanently desync Inbox's copy of {@code priority_score}/
 * {@code is_available} for every task that method touches, with nothing in the logs louder than
 * one ERROR line per occurrence. This test turns "someone forgets @Transactional on a new public
 * method" from that silent runtime failure mode into an immediate, obvious test failure instead.
 *
 * <p>Covers every class issue #649 split {@code TaskEligibilityService} into that can reach
 * {@code TaskCounterUpdater.updateCounters}/{@code recomputePriorityScoreAndPublish} (directly or
 * through another one of these): {@link TaskEligibilityService}, {@link DependencyEdgeService},
 * {@link ConstraintEligibilityService}, and {@link TaskCounterUpdater} itself (its own
 * {@code onScoreProposed}). {@link DependencyGraphService} is deliberately excluded - it's pure
 * reads (getBlockers/getAncestors/getDescendants), nothing to publish.
 */
class SchedulingPublishTransactionalCoverageTest {

    /** Pure reads never mutate state, so they have nothing to publish and don't need a
     * transaction boundary for #445's purposes. Any future read-only method should be added
     * here, not given a pointless {@code @Transactional}. */
    private static final Map<Class<?>, Set<String>> EXEMPT_READ_ONLY_METHODS = Map.of(
            TaskEligibilityService.class, Set.of("getTaskEligibility"),
            TaskCounterUpdater.class, Set.of("requireTask", "requireUpdated", "copyOf", "withCounters"));

    private static final List<Class<?>> COVERED_CLASSES = List.of(
            TaskEligibilityService.class,
            DependencyEdgeService.class,
            ConstraintEligibilityService.class,
            TaskCounterUpdater.class);

    @Test
    void everyPublicMutatorMethod_isTransactional() {
        List<String> notTransactional = COVERED_CLASSES.stream()
                .flatMap(type -> List.of(type.getDeclaredMethods()).stream()
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> !method.isSynthetic() && !method.isBridge())
                        .filter(method -> !EXEMPT_READ_ONLY_METHODS.getOrDefault(type, Set.of())
                                .contains(method.getName()))
                        .filter(method -> method.getAnnotation(Transactional.class) == null)
                        .map(method -> type.getSimpleName() + "#" + method.getName()))
                .collect(Collectors.toList());

        assertThat(notTransactional)
                .withFailMessage(() -> "These public methods are missing @Transactional, so issue "
                        + "#445's scheduling-update publish will be silently dropped for them "
                        + "(fallbackExecution=false - see TaskCounterUpdater#requestPublishAfterCommit's "
                        + "Javadoc): " + String.join(", ", notTransactional)
                        + ". Add @Transactional, or add the method's name to "
                        + "EXEMPT_READ_ONLY_METHODS above if it's a genuine read with nothing to publish.")
                .isEmpty();
    }
}
