package com.rnd.chain.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConstraintTest {

    private Constraint buildConstraint(String constraintId, Map<String, Object> reason) {
        return new Constraint(
                constraintId,
                UUID.randomUUID(),
                Constraint.ConstraintType.APPROVAL_NEEDED,
                Constraint.ConstraintStatus.PENDING,
                reason,
                "source",
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    @Test
    void equals_sameConstraintId_areEqual_regardlessOfOtherFields() {
        String constraintId = "c-1";
        Constraint a = new Constraint(constraintId, UUID.randomUUID(), Constraint.ConstraintType.APPROVAL_NEEDED,
                Constraint.ConstraintStatus.PENDING, null, "source-a", LocalDateTime.now(), LocalDateTime.now());
        Constraint b = new Constraint(constraintId, UUID.randomUUID(), Constraint.ConstraintType.DATE_WINDOW,
                Constraint.ConstraintStatus.SATISFIED, Map.of("k", "v"), "source-b", LocalDateTime.now().minusDays(1),
                LocalDateTime.now().minusDays(1));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentConstraintId_areNotEqual() {
        Constraint a = buildConstraint("c-1", null);
        Constraint b = buildConstraint("c-2", null);

        assertNotEquals(a, b);
    }

    @Test
    void constructor_mutatingOriginalMapAfterConstruction_doesNotAffectGetReason() {
        Map<String, Object> reason = new HashMap<>();
        reason.put("k", "v1");
        Constraint constraint = buildConstraint("c-1", reason);

        reason.put("k", "v2");
        reason.put("new-key", "new-value");

        assertEquals("v1", constraint.getReason().get("k"));
        assertFalse(constraint.getReason().containsKey("new-key"));
    }

    @Test
    void getReason_returnsUnmodifiableMap() {
        Constraint constraint = buildConstraint("c-1", Map.of("k", "v"));

        Map<String, Object> reason = constraint.getReason();

        assertThrows(UnsupportedOperationException.class, () -> reason.put("k2", "v2"));
    }

    @Test
    void getReason_nullReason_returnsNull() {
        Constraint constraint = buildConstraint("c-1", null);

        assertNull(constraint.getReason());
    }
}
