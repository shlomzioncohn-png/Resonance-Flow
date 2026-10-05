package com.rnd.bridge.task.enrichment;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.task.BridgeTaskEventDto;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Automatic enrichment channel: listens on {@code llm.consumer.input-topic}
 * for {@link BridgeTaskEventDto} drafts, runs them through the rule-based
 * enricher, and publishes the result to {@code bridge.structured.tasks}.
 * Disabled by default via {@code llm.consumer.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "llm.consumer.enabled", havingValue = "true")
public class TaskEnrichmentConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskEnrichmentConsumer.class);

    private final EnrichmentProperties properties;
    private final TaskEnricher taskEnricher;
    private final TaskValidator taskValidator;
    private final PayloadPublisher<Task> payloadPublisher;

    public TaskEnrichmentConsumer(EnrichmentProperties properties,
                                  TaskEnricher taskEnricher,
                                  TaskValidator taskValidator,
                                  PayloadPublisher<Task> payloadPublisher) {
        this.properties = properties;
        this.taskEnricher = taskEnricher;
        this.taskValidator = taskValidator;
        this.payloadPublisher = payloadPublisher;
    }

    @KafkaListener(
            topics = "${llm.consumer.input-topic:bridge.tasks.to-enrich}",
            groupId = "${llm.consumer.group-id:bridge-llm-enricher}")
    public void consume(BridgeTaskEventDto event) {
        try {
            Task draft = toTask(event);
            if (!properties.isEnabled()
                    || !properties.getSupportedSources().contains(draft.sourceType())) {
                payloadPublisher.publish(draft);
                return;
            }

            EnrichmentInput input = new EnrichmentInput(
                    draft.sourceType(), draft.title(), draft.description(), draft.status(),
                    draft.sourceRef(), Instant.now(), draft.urgency(), draft.deadline());
            EnrichmentOutput output = taskEnricher.enrich(input);
            Task enriched = merge(draft, output);
            taskValidator.validate(enriched);
            payloadPublisher.publish(enriched);
        } catch (Exception e) {
            log.error("Failed to enrich task event {}, skipping", event.eventId(), e);
        }
    }

    private Task toTask(BridgeTaskEventDto event) {
        return new Task(
                event.tenantId(), // BridgeTaskEventDto.tenantId -> Task.organizationId, see BridgeTaskEventDto javadoc
                event.projectId(),
                event.title(),
                event.description(),
                parseStatus(event.status()),
                event.urgency(),
                event.confidence(),
                parseTier(event.tier()),
                ExtractionProvenance.ETL,
                parseRoleContext(event.roleContext()),
                parseSourceType(event.sourceType()),
                event.sourceRef(),
                event.effortEstimateMinutes(),
                event.deadline(),
                null,
                null);
    }

    private Task merge(Task draft, EnrichmentOutput output) {
        if (output.isEmpty()) {
            return draft;
        }
        return new Task(
                draft.organizationId(),
                draft.projectId(),
                output.title() != null ? output.title() : draft.title(),
                output.description() != null ? output.description() : draft.description(),
                draft.status(),
                output.urgency() != null ? output.urgency() : draft.urgency(),
                output.confidence() != null ? output.confidence() : draft.confidence(),
                draft.tier(),
                ExtractionProvenance.ML_INFERRED,
                draft.roleContext(),
                draft.sourceType(),
                draft.sourceRef(),
                output.effortEstimateMinutes() != null ? output.effortEstimateMinutes() : draft.effortEstimateMinutes(),
                output.deadline() != null ? output.deadline() : draft.deadline(),
                draft.snoozedUntil(),
                draft.completedAt());
    }

    private TaskStatus parseStatus(String value) {
        return value == null ? null : TaskStatus.valueOf(value);
    }

    private Tier parseTier(String value) {
        return value == null ? null : Tier.valueOf(value);
    }

    private RoleContext parseRoleContext(String value) {
        return value == null ? null : RoleContext.valueOf(value);
    }

    private SourceType parseSourceType(String value) {
        return value == null ? null : SourceType.valueOf(value);
    }
}
