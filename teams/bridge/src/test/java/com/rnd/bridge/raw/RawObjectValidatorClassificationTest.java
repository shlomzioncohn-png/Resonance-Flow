package com.rnd.bridge.raw;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.ClassifierInfo;
import com.rnd.bridge.raw.model.DecidedBy;
import com.rnd.bridge.raw.model.DocumentCategory;
import com.rnd.bridge.raw.model.ItemCategory;
import com.rnd.bridge.raw.model.Label;
import com.rnd.bridge.raw.model.MeetingCategory;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.Quality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.raw.model.Sensitivity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawObjectValidatorClassificationTest {

    private final RawObjectValidator validator = new RawObjectValidator();

    @Test
    void acceptsEventWithoutClassification() {
        assertThatCode(() -> validator.validate(event(RawSourceType.ZOOM))).doesNotThrowAnyException();
    }

    @Test
    void acceptsCategoryMatchingSourceType() {
        RawObjectEvent event = event(RawSourceType.ZOOM)
                .withClassification(classification(MeetingCategory.TEST));

        assertThatCode(() -> validator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void everyMeetingPlatformAcceptsMeetingCategories() {
        RawObjectEvent event = event(RawSourceType.BIGBLUEBUTTON)
                .withClassification(classification(MeetingCategory.STANDUP));

        assertThatCode(() -> validator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void rejectsCategoryFromAnotherSourceType() {
        RawObjectEvent event = event(RawSourceType.ZOOM)
                .withClassification(classification(DocumentCategory.INVOICE));

        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("classification.category");
    }

    private static Classification classification(ItemCategory category) {
        return new Classification(
                DecidedBy.RULE, Label.fromRule(category), Label.fromRule(Quality.GOOD),
                Label.fromRule(Sensitivity.NONE), "duration under 60s",
                new ClassifierInfo(ClassifierInfo.RULES_MODEL, "v1"));
    }

    private static RawObjectEvent event(RawSourceType sourceType) {
        RawObjectRef recording = new RawObjectRef(
                Role.RECORDING, sourceType + "/kamatech/2026-10-11/x.mp4", "sha256:" + "a".repeat(64),
                "audio/mp4", 10L, Modality.AUDIO);
        return RawObjectEvent.create(sourceType, "kamatech", Instant.now(),
                ScopeDecision.INCLUDED, null, null, "corr-1", 0, ChangeType.UPSERT, List.of(recording));
    }
}
