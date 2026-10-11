package com.rnd.bridge.media.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.ClassifierInfo;
import com.rnd.bridge.raw.model.DecidedBy;
import com.rnd.bridge.raw.model.ItemCategory;
import com.rnd.bridge.raw.model.Label;
import com.rnd.bridge.raw.model.MeetingCategory;
import com.rnd.bridge.raw.model.Quality;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.raw.model.Sensitivity;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MediaClassificationServiceTest {

    private static final MeetingClassificationInput INPUT = new MeetingClassificationInput(null, "transcript");

    private final ClassificationRules rules = mock(ClassificationRules.class);
    private final ModelMediaClassifier model = mock(ModelMediaClassifier.class);

    @Test
    void disabledLeavesEverythingIncludedAndUnclassified() {
        Triage triage = service(false, "key").triageMeeting(INPUT, "m.mp4");

        assertThat(triage).isEqualTo(Triage.UNCLASSIFIED);
        verifyNoInteractions(rules, model);
    }

    @Test
    void ruleVerdictWinsWithoutCallingTheModel() {
        when(rules.classifyMeeting(INPUT)).thenReturn(Optional.of(verdict(MeetingCategory.TEST, 1.0)));

        Triage triage = service(true, "key").triageMeeting(INPUT, "m.mp4");

        assertThat(triage.scopeDecision()).isEqualTo(ScopeDecision.EXCLUDED);
        verifyNoInteractions(model);
    }

    @Test
    void modelIsAskedWhenRulesCannotDecide() {
        when(rules.classifyMeeting(INPUT)).thenReturn(Optional.empty());
        when(model.classifyMeeting(INPUT)).thenReturn(Optional.of(verdict(MeetingCategory.PLANNING, 0.95)));

        Triage triage = service(true, "key").triageMeeting(INPUT, "m.mp4");

        assertThat(triage.scopeDecision()).isEqualTo(ScopeDecision.INCLUDED);
        assertThat(triage.classification().category().value()).isEqualTo(MeetingCategory.PLANNING);
    }

    @Test
    void excludedCategoryBelowConfidenceThresholdStaysIncludedButKeepsItsClassification() {
        when(rules.classifyMeeting(INPUT)).thenReturn(Optional.empty());
        when(model.classifyMeeting(INPUT)).thenReturn(Optional.of(verdict(MeetingCategory.SOCIAL, 0.5)));

        Triage triage = service(true, "key").triageMeeting(INPUT, "m.mp4");

        assertThat(triage.scopeDecision()).isEqualTo(ScopeDecision.INCLUDED);
        assertThat(triage.classification()).isNotNull();
    }

    @Test
    void excludedCategoryAtThresholdIsExcluded() {
        when(rules.classifyMeeting(INPUT)).thenReturn(Optional.empty());
        when(model.classifyMeeting(INPUT)).thenReturn(Optional.of(verdict(MeetingCategory.SOCIAL, 0.6)));

        assertThat(service(true, "key").triageMeeting(INPUT, "m.mp4").scopeDecision())
                .isEqualTo(ScopeDecision.EXCLUDED);
    }

    @Test
    void noApiKeyMeansRulesOnly() {
        when(rules.classifyMeeting(INPUT)).thenReturn(Optional.empty());

        Triage triage = service(true, "").triageMeeting(INPUT, "m.mp4");

        assertThat(triage).isEqualTo(Triage.UNCLASSIFIED);
        verifyNoInteractions(model);
    }

    @Test
    void modelFailureFailsOpen() {
        when(rules.classifyMeeting(INPUT)).thenReturn(Optional.empty());
        when(model.classifyMeeting(any())).thenThrow(new IllegalStateException("429 Too Many Requests"));

        assertThat(service(true, "key").triageMeeting(INPUT, "m.mp4")).isEqualTo(Triage.UNCLASSIFIED);
    }

    private MediaClassificationService service(boolean enabled, String apiKey) {
        return new MediaClassificationService(rules, model, ClassificationTestSupport.properties(enabled, apiKey));
    }

    private static Classification verdict(ItemCategory category, double confidence) {
        return new Classification(DecidedBy.MODEL, new Label<>(category, confidence),
                new Label<>(Quality.GOOD, 0.9), new Label<>(Sensitivity.NONE, 0.9), "reason",
                new ClassifierInfo("test-model", "v1"));
    }
}
