package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.repository.ConstraintHistoryRepository;
import com.rnd.chain.repository.ConstraintRepository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Proves a failing history write rolls back the {@code task_constraint} write itself, against a real
 * Postgres transaction. {@code @Tag("postgres")}: run with {@code -Ppostgres-integration}.
 */
@SpringBootTest
@Tag("postgres")
class ConstraintHistoryRollbackPostgresTest {

    @Autowired
    private ConstraintEligibilityService service;

    @Autowired
    private ConstraintRepository constraintRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private ConstraintHistoryRepository historyRepository;

    private UUID seededTask() {
        UUID taskId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO task (id, status) VALUES (?, 'ACTIVE')", taskId);
        return taskId;
    }

    private Constraint newConstraint(UUID taskId, String constraintId) {
        LocalDateTime now = LocalDateTime.now();
        return new Constraint(constraintId, taskId, Constraint.ConstraintType.APPROVAL_NEEDED,
                Constraint.ConstraintStatus.PENDING, null, "source-ref", now, now);
    }

    private int historyRows(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM constraint_history WHERE task_id = ?", Integer.class, taskId);
    }

    @Test
    void onConstraintAdded_historyWriteFails_constraintInsertIsRolledBack() {
        UUID taskId = seededTask();
        String constraintId = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("history down")).when(historyRepository).record(any());

        assertThatThrownBy(() -> service.onConstraintAdded(newConstraint(taskId, constraintId)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(constraintRepository.findById(constraintId)).isEmpty();
        assertThat(historyRows(taskId)).isZero();
    }

    @Test
    void onConstraintStatusChanged_historyWriteFails_statusChangeIsRolledBack() {
        UUID taskId = seededTask();
        String constraintId = UUID.randomUUID().toString();
        constraintRepository.save(newConstraint(taskId, constraintId));
        doThrow(new IllegalStateException("history down")).when(historyRepository).record(any());

        assertThatThrownBy(() -> service.onConstraintStatusChanged(
                taskId, constraintId, Constraint.ConstraintStatus.SATISFIED, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(constraintRepository.findById(constraintId).orElseThrow().getStatus())
                .isEqualTo(Constraint.ConstraintStatus.PENDING);
        assertThat(historyRows(taskId)).isZero();
    }
}
