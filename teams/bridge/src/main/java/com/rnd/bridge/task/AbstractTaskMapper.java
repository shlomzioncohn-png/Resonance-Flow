package com.rnd.bridge.task;

import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.enrichment.EnrichmentInput;
import com.rnd.bridge.task.enrichment.EnrichmentOutput;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import com.rnd.bridge.task.model.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(AbstractTaskMapper.class);

    private final EnrichmentProperties enrichmentProperties;
    private final TaskEnricher taskEnricher;

    protected AbstractTaskMapper(EnrichmentProperties enrichmentProperties, TaskEnricher taskEnricher) {
        this.enrichmentProperties = enrichmentProperties;
        this.taskEnricher = taskEnricher;
    }

    protected abstract SourceType sourceType();

    protected abstract Tier tier();

    protected abstract RoleContext roleContext();

    /**
     * Inbox's {@code tasks.tenant_id} is NOT NULL, but no lane has a
     * general-purpose source for it - a subclass overrides this only once it
     * has an actual value to supply (e.g. a configured account id), never a
     * made-up one. Defaults to null, which is the cross-team contract gap
     * for every lane that doesn't override it.
     * <p>
     * Named {@code organizationId()}, not {@code tenantId()}, to match
     * {@link com.rnd.bridge.task.model.Task#organizationId()} - Task is
     * deliberately not renamed alongside Inbox's organization-to-tenant
     * rename, see {@code CLAUDE.md} §4/§6. {@code BridgeTaskEventDto.from()}
     * is what actually renames this value onto the wire as {@code tenantId}.
     */
    protected String organizationId() {
        return null;
    }

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

    protected abstract Instant toCompletedAt(S source);

    @Override
    public final Task map(S source, Instant asOf) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(asOf, "asOf must not be null");

        String title = toTitle(source);
        String description = toDescription(source);
        TaskStatus status = toStatus(source);
        String sourceRef = toSourceRef(source);
        Integer urgency = toUrgency(source, asOf);
        Instant deadline = toDeadline(source);

        EnrichmentOutput enrichment = EnrichmentOutput.empty();
        if (enrichmentProperties.isEnabled()
                && enrichmentProperties.getSupportedSources().contains(sourceType())) {
            try {
                EnrichmentInput input = new EnrichmentInput(
                        sourceType(), title, description, status, sourceRef, asOf, urgency, deadline);
                enrichment = taskEnricher.enrich(input);
            } catch (Exception e) {
                log.warn("Enrichment failed for sourceRef={} sourceType={}, falling back to ETL",
                        sourceRef, sourceType(), e);
                enrichment = EnrichmentOutput.empty();
            }
        }

        Integer finalUrgency = enrichment.urgency() != null ? enrichment.urgency() : urgency;
        Instant finalDeadline = enrichment.deadline() != null ? enrichment.deadline() : deadline;
        String finalTitle = enrichment.title() != null ? enrichment.title() : title;
        String finalDescription = enrichment.description() != null ? enrichment.description() : description;
        Double finalConfidence = enrichment.confidence() != null ? enrichment.confidence() : confidence();
        ExtractionProvenance provenance = enrichment.isEmpty()
                ? ExtractionProvenance.ETL
                : ExtractionProvenance.ML_INFERRED;

        return new Task(
                organizationId(),
                null, // projectId - no lane currently sources this
                finalTitle,
                finalDescription,
                status,
                finalUrgency == null ? null : clamp(finalUrgency, 1, 5),
                finalConfidence,
                tier(),
                provenance,
                roleContext(),
                sourceType(),
                sourceRef,
                enrichment.effortEstimateMinutes() != null ? enrichment.effortEstimateMinutes() : null,
                finalDeadline,
                toCompletedAt(source));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
