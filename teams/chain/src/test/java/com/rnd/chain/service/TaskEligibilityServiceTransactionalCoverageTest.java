package com.rnd.chain.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #445 Phase 3 guardrail: every public mutator on {@link TaskEligibilityService} MUST be
 * {@code @Transactional}, because {@link TaskEligibilityService#requestPublishAfterCommit}
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
 */
class TaskEligibilityServiceTransactionalCoverageTest {

    /** Pure reads never mutate state, so they have nothing to publish and don't need a
     * transaction boundary for #445's purposes. Any future read-only method should be added
     * here, not given a pointless {@code @Transactional}. */
    private static final Set<String> EXEMPT_READ_ONLY_METHODS = Set.of("getTaskEligibility");

    @Test
    void everyPublicMutatorMethod_isTransactional() {
        List<Method> notTransactional = List.of(TaskEligibilityService.class.getDeclaredMethods()).stream()
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .filter(method -> !EXEMPT_READ_ONLY_METHODS.contains(method.getName()))
                .filter(method -> method.getAnnotation(Transactional.class) == null)
                .collect(Collectors.toList());

        assertThat(notTransactional)
                .withFailMessage(() -> "These public TaskEligibilityService methods are missing "
                        + "@Transactional, so issue #445's scheduling-update publish will be "
                        + "silently dropped for them (fallbackExecution=false - see "
                        + "requestPublishAfterCommit's Javadoc): "
                        + notTransactional.stream().map(Method::getName).collect(Collectors.joining(", "))
                        + ". Add @Transactional, or add the method's name to "
                        + "EXEMPT_READ_ONLY_METHODS above if it's a genuine read with nothing to publish.")
                .isEmpty();
    }
}
