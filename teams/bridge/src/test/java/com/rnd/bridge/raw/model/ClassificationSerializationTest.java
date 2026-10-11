package com.rnd.bridge.raw.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins classification's wire shape to shared/proto/raw-object-event.proto: snake_case names,
 * every label as {value, confidence}, enum values by name - and omitted entirely, not null,
 * when no classifier ran.
 */
class ClassificationSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesClassificationMatchingTheProtoContract() throws Exception {
        Classification classification = new Classification(
                DecidedBy.MODEL,
                new Label<>(DocumentCategory.INVOICE, 0.92),
                new Label<>(Quality.POOR, 0.75),
                new Label<>(Sensitivity.LOW, 0.85),
                "vendor invoice, partially blurred scan",
                new ClassifierInfo("gemini-2.5-flash", "v1"));

        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(scannedDoc().withClassification(classification)))
                .get("classification");

        assertThat(json.get("decided_by").asText()).isEqualTo("MODEL");
        assertThat(json.get("category").get("value").asText()).isEqualTo("INVOICE");
        assertThat(json.get("category").get("confidence").asDouble()).isEqualTo(0.92);
        assertThat(json.get("quality").get("value").asText()).isEqualTo("POOR");
        assertThat(json.get("sensitivity").get("value").asText()).isEqualTo("LOW");
        assertThat(json.get("reason").asText()).isEqualTo("vendor invoice, partially blurred scan");
        assertThat(json.get("classifier").get("model").asText()).isEqualTo("gemini-2.5-flash");
        assertThat(json.get("classifier").get("prompt_version").asText()).isEqualTo("v1");
        assertThat(json.has("decidedBy")).isFalse();
    }

    @Test
    void omitsClassificationWhenNoClassifierRan() throws Exception {
        String json = objectMapper.writeValueAsString(scannedDoc());

        assertThat(json).doesNotContain("classification");
    }

    @Test
    void ruleLabelsCarryFullConfidence() {
        assertThat(Label.fromRule(DocumentCategory.BLANK).confidence()).isEqualTo(1.0);
    }

    @Test
    void rejectsConfidenceOutsideZeroToOne() {
        assertThatThrownBy(() -> new Label<>(Quality.GOOD, 1.01)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Label<>(Quality.GOOD, -0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Label<>(Quality.GOOD, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingValue() {
        assertThatThrownBy(() -> new Label<Quality>(null, 0.5)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void documentCategoriesStartWithTheSixteenRvlCdipClassesInLabelOrder() {
        // RVL-CDIP labels are integers 0-15 in this order - a classifier evaluation maps them
        // by ordinal, so reordering these constants would silently corrupt every score.
        assertThat(List.of(DocumentCategory.values()).subList(0, 16)).containsExactly(
                DocumentCategory.LETTER, DocumentCategory.FORM, DocumentCategory.EMAIL,
                DocumentCategory.HANDWRITTEN, DocumentCategory.ADVERTISEMENT,
                DocumentCategory.SCIENTIFIC_REPORT, DocumentCategory.SCIENTIFIC_PUBLICATION,
                DocumentCategory.SPECIFICATION, DocumentCategory.FILE_FOLDER,
                DocumentCategory.NEWS_ARTICLE, DocumentCategory.BUDGET, DocumentCategory.INVOICE,
                DocumentCategory.PRESENTATION, DocumentCategory.QUESTIONNAIRE,
                DocumentCategory.RESUME, DocumentCategory.MEMO);
    }

    static RawObjectEvent scannedDoc() {
        RawObjectRef body = new RawObjectRef(
                Role.BODY, "SCANNED_DOC/kamatech/2026-10-11/x.png", "sha256:" + "a".repeat(64),
                "image/png", 10L, Modality.SCANNED_DOC);
        return RawObjectEvent.create(
                RawSourceType.SCANNED_DOC, "kamatech", "corr-1", Instant.parse("2026-10-11T09:00:00Z"),
                ScopeDecision.INCLUDED, 0, ChangeType.UPSERT, 100, null, null, null, List.of(body));
    }
}
