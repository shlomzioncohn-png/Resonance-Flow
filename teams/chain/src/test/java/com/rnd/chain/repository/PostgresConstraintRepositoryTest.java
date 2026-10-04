package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link PostgresConstraintRepository} against a real Postgres instance (see
 * teams/chain/CLAUDE.md - mirrors Inbox's real-DB integration test convention, no Testcontainers).
 *
 * <p>{@code @Tag("postgres")}: excluded from the default {@code mvn test} run (pom.xml surefire
 * {@code excludedGroups}), like the other Postgres-backed tests - run with
 * {@code -Ppostgres-integration} against a reachable Postgres.
 */
@SpringBootTest
@Tag("postgres")
class PostgresConstraintRepositoryTest {

    @Autowired
    private ConstraintRepository constraintRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private UUID seededTask() {
        UUID id = UUID.randomUUID();
        seedTask(id);
        return id;
    }

    private void seedTask(UUID taskId) {
        jdbcTemplate.update("INSERT INTO task (id, owner_id, status) VALUES (?, 'owner', 'ACTIVE') ON CONFLICT (id) DO NOTHING", taskId);
    }

    private Constraint newConstraint(String constraintId, UUID taskId, Constraint.ConstraintStatus status,
                                      Map<String, Object> reason) {
        seedTask(taskId);
        LocalDateTime now = LocalDateTime.now();
        return new Constraint(constraintId, taskId, Constraint.ConstraintType.APPROVAL_NEEDED,
            status, reason, "source-ref", now, now);
    }

    @Test
    void save_newConstraint_returnsTrueAndIsRetrievableByIdAndByTaskId() {
        UUID taskId = UUID.randomUUID();
        Constraint constraint = newConstraint(UUID.randomUUID().toString(), taskId,
            Constraint.ConstraintStatus.PENDING, Map.of("note", "needs manager sign-off"));

        assertThat(constraintRepository.save(constraint)).isTrue();

        // Constraint.equals() is constraintId-only (see its Javadoc), so field-by-field
        // assertions here, not a bare .contains(constraint), actually verify the round trip.
        Constraint found = constraintRepository.findById(constraint.getConstraintId()).orElseThrow();
        assertThat(found.getTaskId()).isEqualTo(taskId);
        assertThat(found.getType()).isEqualTo(Constraint.ConstraintType.APPROVAL_NEEDED);
        assertThat(found.getStatus()).isEqualTo(Constraint.ConstraintStatus.PENDING);
        assertThat(found.getSourceRef()).isEqualTo("source-ref");

        assertThat(constraintRepository.findByTaskId(taskId))
            .extracting(Constraint::getConstraintId)
            .containsExactly(constraint.getConstraintId());
    }

    @Test
    void save_reasonRoundTripsThroughJsonb() {
        Map<String, Object> reason = Map.of("approverId", "user-42", "level", 2);
        LocalDateTime now = LocalDateTime.now();
        Constraint constraint = new Constraint(UUID.randomUUID().toString(), seededTask(),
            Constraint.ConstraintType.APPROVAL_NEEDED, Constraint.ConstraintStatus.PENDING, reason,
            "source-ref", now, now);

        constraintRepository.save(constraint);

        assertThat(constraintRepository.findById(constraint.getConstraintId()))
            .get()
            .extracting(Constraint::getReason)
            .isEqualTo(reason);
    }

    @Test
    void save_nullReason_roundTripsAsNull() {
        LocalDateTime now = LocalDateTime.now();
        Constraint constraint = new Constraint(UUID.randomUUID().toString(), seededTask(),
            Constraint.ConstraintType.APPROVAL_NEEDED, Constraint.ConstraintStatus.PENDING, null,
            "source-ref", now, now);

        constraintRepository.save(constraint);

        assertThat(constraintRepository.findById(constraint.getConstraintId()))
            .get()
            .extracting(Constraint::getReason)
            .isNull();
    }

    @Test
    void save_sameConstraintIdRedelivered_returnsFalseAndUpsertsInsteadOfDuplicating() {
        String constraintId = UUID.randomUUID().toString();
        UUID taskId = UUID.randomUUID();
        Constraint original = newConstraint(constraintId, taskId, Constraint.ConstraintStatus.PENDING, null);
        assertThat(constraintRepository.save(original)).isTrue();

        // Same constraintId, same content - simulates a redelivered "constraint added" event.
        // false is exactly the signal TaskEligibilityService.onConstraintAdded relies on to
        // avoid double-counting openConstraintCount.
        assertThat(constraintRepository.save(
                newConstraint(constraintId, taskId, Constraint.ConstraintStatus.PENDING, null))).isFalse();

        assertThat(constraintRepository.findByTaskId(taskId)).hasSize(1);
    }

    @Test
    void save_statusTransition_updatesExistingRowRatherThanInserting() {
        String constraintId = UUID.randomUUID().toString();
        UUID taskId = UUID.randomUUID();
        constraintRepository.save(newConstraint(constraintId, taskId, Constraint.ConstraintStatus.PENDING, null));

        boolean secondSaveWasInsert = constraintRepository.save(
            newConstraint(constraintId, taskId, Constraint.ConstraintStatus.SATISFIED,
                Map.of("resolvedBy", "user-7")));

        assertThat(secondSaveWasInsert).isFalse();
        assertThat(constraintRepository.findByTaskId(taskId)).hasSize(1);
        assertThat(constraintRepository.findById(constraintId))
            .get()
            .extracting(Constraint::getStatus)
            .isEqualTo(Constraint.ConstraintStatus.SATISFIED);
    }

    @Test
    void save_taskDoesNotExist_rejectedByForeignKey() {
        LocalDateTime now = LocalDateTime.now();
        Constraint constraint = new Constraint(UUID.randomUUID().toString(), UUID.randomUUID(),
            Constraint.ConstraintType.APPROVAL_NEEDED, Constraint.ConstraintStatus.PENDING, null,
            "source-ref", now, now);

        assertThatThrownBy(() -> constraintRepository.save(constraint))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updateStatus_existingConstraint_returnsPreviousStatusAndPersistsNewOne() {
        String constraintId = UUID.randomUUID().toString();
        UUID taskId = UUID.randomUUID();
        constraintRepository.save(newConstraint(constraintId, taskId, Constraint.ConstraintStatus.PENDING, null));

        Constraint.ConstraintStatus previous = constraintRepository.updateStatus(
            taskId, constraintId, Constraint.ConstraintStatus.SATISFIED,
            Map.of("resolvedBy", "user-7"), LocalDateTime.now());

        assertThat(previous).isEqualTo(Constraint.ConstraintStatus.PENDING);
        assertThat(constraintRepository.findById(constraintId))
            .get()
            .extracting(Constraint::getStatus)
            .isEqualTo(Constraint.ConstraintStatus.SATISFIED);
    }

    @Test
    void updateStatus_reopening_returnsPreviousClosedStatus() {
        String constraintId = UUID.randomUUID().toString();
        UUID taskId = UUID.randomUUID();
        constraintRepository.save(newConstraint(constraintId, taskId, Constraint.ConstraintStatus.SATISFIED, null));

        Constraint.ConstraintStatus previous = constraintRepository.updateStatus(
            taskId, constraintId, Constraint.ConstraintStatus.PENDING, null, LocalDateTime.now());

        assertThat(previous).isEqualTo(Constraint.ConstraintStatus.SATISFIED);
    }

    @Test
    void updateStatus_redeliveredTransition_returnsTheAlreadyAppliedStatus() {
        // Simulates a redelivered status-change event: the DB already reflects the target
        // state, so the caller (TaskEligibilityService) can detect "nothing actually changed"
        // from the returned value being equal to newStatus, instead of trusting a
        // caller-supplied previousStatus.
        String constraintId = UUID.randomUUID().toString();
        UUID taskId = UUID.randomUUID();
        constraintRepository.save(newConstraint(constraintId, taskId, Constraint.ConstraintStatus.SATISFIED, null));

        Constraint.ConstraintStatus previous = constraintRepository.updateStatus(
            taskId, constraintId, Constraint.ConstraintStatus.SATISFIED, null, LocalDateTime.now());

        assertThat(previous).isEqualTo(Constraint.ConstraintStatus.SATISFIED);
    }

    @Test
    void updateStatus_constraintDoesNotExist_throws() {
        assertThatThrownBy(() -> constraintRepository.updateStatus(
                UUID.randomUUID(), UUID.randomUUID().toString(), Constraint.ConstraintStatus.SATISFIED,
                null, LocalDateTime.now()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateStatus_constraintBelongsToAnotherTask_throwsAndWritesNothing() {
        String constraintId = UUID.randomUUID().toString();
        constraintRepository.save(newConstraint(constraintId, UUID.randomUUID(),
            Constraint.ConstraintStatus.PENDING, null));

        assertThatThrownBy(() -> constraintRepository.updateStatus(
                UUID.randomUUID(), constraintId, Constraint.ConstraintStatus.SATISFIED, null, LocalDateTime.now()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(constraintRepository.findById(constraintId))
            .get()
            .extracting(Constraint::getStatus)
            .isEqualTo(Constraint.ConstraintStatus.PENDING);
    }

    @Test
    void findByTaskId_noConstraints_returnsEmptyList() {
        assertThat(constraintRepository.findByTaskId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findById_notFound_returnsEmpty() {
        assertThat(constraintRepository.findById(UUID.randomUUID().toString())).isEmpty();
    }
}
