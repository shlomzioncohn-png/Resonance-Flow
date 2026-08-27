package com.rnd.bridge.task;

import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;

import java.time.Instant;
import java.util.Objects;

/**
 * Owns what is true for every Bridge structured lane that produces a
 * {@link Task}, so no lane can get it wrong or drift: extraction is always
 * deterministic ETL, and urgency is always clamped to Inbox's 1..5 range
 * regardless of what a subclass computes. Lanes supply the constants and
 * per-record logic that vary.
 */
public abstract class AbstractTaskMapper<S> implements PayloadMapper<S, Task> {

    protected abstract SourceType sourceType();

    protected abstract Tier tier();

    protected abstract RoleContext roleContext();

    /**
     * Whether this lane's rows carry a meaningful confidence score. Inbox
     * only requires confidence for ML-inferred rows; a deterministic ETL
     * lane has nothing to be confident about, so the default is null.
     * <p>
     * Whether a fixed value (e.g. 1.0) should apply uniformly instead is an
     * open question shared across lane owners - not resolved here.
     */
    protected Double confidence() {
        return null;
    }

    protected abstract String toTitle(S source);

    protected abstract String toDescription(S source);

    protected abstract String toSourceRef(S source);

    protected abstract TaskStatus toStatus(S source);

    protected abstract Integer toUrgency(S source, Instant asOf);

    protected abstract Instant toDeadline(S source);

    protected abstract Instant toSnoozedUntil(S source);

    protected abstract Instant toCompletedAt(S source);

    @Override
    public final Task map(S source, Instant asOf) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(asOf, "asOf must not be null");

        Integer urgency = toUrgency(source, asOf);

        return new Task(
                // organizationId - no lane in this tree can source a tenant id yet.
                // Inbox's tasks.organization_id is NOT NULL; this is a cross-team
                // contract gap awaiting a decision - do not default or invent one.
                null,
                null, // projectId - no lane currently sources this
                toTitle(source),
                toDescription(source),
                toStatus(source),
                urgency == null ? null : clamp(urgency, 1, 5),
                confidence(),
                tier(),
                ExtractionProvenance.ETL,
                roleContext(),
                sourceType(),
                toSourceRef(source),
                null, // effortEstimateMinutes - no lane currently sources this
                toDeadline(source),
                toSnoozedUntil(source),
                toCompletedAt(source));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
