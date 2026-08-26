package com.rnd.bridge.task;

import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskValidatorTest {

    private static final TaskValidator VALIDATOR = new TaskValidator();

    private static Task baseTask() {
        return new Task(
                null, null,
                "Title",
                "Description",
                TaskStatus.NEW,
                3,
                null,
                Tier.TACTICAL,
                ExtractionProvenance.ETL,
                RoleContext.SALESPERSON,
                SourceType.CRM_DEAL,
                "crm:D-1",
                null,
                null,
                null);
    }

    @Test
    void acceptsAWellFormedTask() {
        assertDoesNotThrow(() -> VALIDATOR.validate(baseTask()));
    }

    @Test
    void rejectsUrgencyOutOfRange() {
        Task task = withUrgency(baseTask(), 6);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("urgency"));
    }

    @Test
    void rejectsConfidenceOutOfRange() {
        Task task = withConfidence(baseTask(), 1.5);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("confidence"));
    }

    @Test
    void rejectsNonPositiveEffortEstimate() {
        Task task = withEstimatedDurationMinutes(baseTask(), 0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("estimatedDurationMinutes"));
    }

    @Test
    void rejectsSnoozedStatusWithoutSnoozedUntil() {
        Task task = withStatus(baseTask(), TaskStatus.SNOOZED);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("snoozedUntil"));
    }

    @Test
    void rejectsSnoozedUntilWithoutSnoozedStatus() {
        Task task = withSnoozedUntil(withStatus(baseTask(), TaskStatus.NEW), Instant.parse("2026-09-01T00:00:00Z"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("snoozedUntil"));
    }

    @Test
    void rejectsMissingTitle() {
        Task task = withTitle(baseTask(), null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> VALIDATOR.validate(task));
        assertTrue(ex.getMessage().contains("title"));
    }

    private static Task withUrgency(Task t, Integer urgency) {
        return new Task(t.organizationId(), t.projectId(), t.title(), t.description(), t.status(), urgency,
                t.confidence(), t.tier(), t.extractionProvenance(), t.roleContext(), t.sourceType(), t.sourceRef(),
                t.estimatedDurationMinutes(), t.deadline(), t.snoozedUntil());
    }

    private static Task withConfidence(Task t, Double confidence) {
        return new Task(t.organizationId(), t.projectId(), t.title(), t.description(), t.status(), t.urgency(),
                confidence, t.tier(), t.extractionProvenance(), t.roleContext(), t.sourceType(), t.sourceRef(),
                t.estimatedDurationMinutes(), t.deadline(), t.snoozedUntil());
    }

    private static Task withEstimatedDurationMinutes(Task t, Integer minutes) {
        return new Task(t.organizationId(), t.projectId(), t.title(), t.description(), t.status(), t.urgency(),
                t.confidence(), t.tier(), t.extractionProvenance(), t.roleContext(), t.sourceType(), t.sourceRef(),
                minutes, t.deadline(), t.snoozedUntil());
    }

    private static Task withStatus(Task t, TaskStatus status) {
        return new Task(t.organizationId(), t.projectId(), t.title(), t.description(), status, t.urgency(),
                t.confidence(), t.tier(), t.extractionProvenance(), t.roleContext(), t.sourceType(), t.sourceRef(),
                t.estimatedDurationMinutes(), t.deadline(), t.snoozedUntil());
    }

    private static Task withSnoozedUntil(Task t, Instant snoozedUntil) {
        return new Task(t.organizationId(), t.projectId(), t.title(), t.description(), t.status(), t.urgency(),
                t.confidence(), t.tier(), t.extractionProvenance(), t.roleContext(), t.sourceType(), t.sourceRef(),
                t.estimatedDurationMinutes(), t.deadline(), snoozedUntil);
    }

    private static Task withTitle(Task t, String title) {
        return new Task(t.organizationId(), t.projectId(), title, t.description(), t.status(), t.urgency(),
                t.confidence(), t.tier(), t.extractionProvenance(), t.roleContext(), t.sourceType(), t.sourceRef(),
                t.estimatedDurationMinutes(), t.deadline(), t.snoozedUntil());
    }
}
