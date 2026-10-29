package com.rnd.bridge.crm.enrichment;

import com.rnd.bridge.task.enrichment.EnrichmentInput;
import com.rnd.bridge.task.enrichment.EnrichmentOutput;
import com.rnd.bridge.task.enrichment.RuleBasedTaskEnricher;
import com.rnd.bridge.task.enrichment.TaskEnricher;
import com.rnd.bridge.task.model.SourceType;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@code @Primary} {@link TaskEnricher}: routes {@code CRM_DEAL} to
 * {@link CrmDealLlmEnricher}, every other source type to the existing
 * {@link RuleBasedTaskEnricher} - unchanged, injected by its concrete type (not
 * {@code TaskEnricher}) so this class doesn't end up injecting itself as its own
 * fallback (design doc section 6).
 * <p>
 * Every {@code AbstractTaskMapper} subclass (CRM, GitHub, Jira) takes a plain
 * {@code TaskEnricher}, so becoming {@code @Primary} here is what makes every lane
 * resolve to this router instead of directly to {@code RuleBasedTaskEnricher} -
 * {@code AbstractTaskMapper} itself is unchanged; it still only calls
 * {@code taskEnricher.enrich(input)} when {@code llm.enrichment.*} allows that
 * source type through in the first place.
 */
@Component
@Primary
public final class SourceRoutingTaskEnricher implements TaskEnricher {

    private final CrmDealLlmEnricher crmDealLlmEnricher;
    private final RuleBasedTaskEnricher ruleBasedTaskEnricher;

    public SourceRoutingTaskEnricher(CrmDealLlmEnricher crmDealLlmEnricher,
                                     RuleBasedTaskEnricher ruleBasedTaskEnricher) {
        this.crmDealLlmEnricher = crmDealLlmEnricher;
        this.ruleBasedTaskEnricher = ruleBasedTaskEnricher;
    }

    @Override
    public EnrichmentOutput enrich(EnrichmentInput input) {
        TaskEnricher delegate = input.sourceType() == SourceType.CRM_DEAL
                ? crmDealLlmEnricher
                : ruleBasedTaskEnricher;
        return delegate.enrich(input);
    }
}
