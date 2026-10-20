package com.rnd.bridge.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.raw.RawObjectEventProtoMapper;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class MediaEventPublisherTest {

    private static final String TOPIC = "bridge.raw.landed";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final MediaEventPublisher publisher = new MediaEventPublisher(
            kafkaTemplate, new RawObjectEventProtoMapper(), new RawObjectValidator(), TOPIC);

    @Test
    void publishesTheEventAsProtobufKeyedByItsDedupKey() {
        when(kafkaTemplate.send(eq(TOPIC), any(), any())).thenReturn(CompletableFuture.completedFuture(sendResult()));
        RawObjectEvent event = scannedDoc().withSourceRef("rec-1");

        publisher.publish(event);

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("corr-1:0"), value.capture());
        assertThat(value.getValue()).isInstanceOf(com.rnd.bridge.shared.unstructured.dto.RawObjectEvent.class);
        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent proto =
                (com.rnd.bridge.shared.unstructured.dto.RawObjectEvent) value.getValue();
        assertThat(proto.getSourceType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_SCANNED_DOC);
        assertThat(proto.getSourceRef()).isEqualTo("rec-1");
    }

    @Test
    void failedSendIsReportedSoTheFileIsNotTreatedAsPublished() {
        when(kafkaTemplate.send(eq(TOPIC), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> publisher.publish(scannedDoc()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to publish");
    }

    @Test
    void invalidEventIsNeverSent() {
        RawObjectEvent noObjects = RawObjectEvent.create(
                RawSourceType.SCANNED_DOC, "demo-tenant", Instant.now(), ScopeDecision.INCLUDED, null, null,
                "corr-1", 0, ChangeType.UPSERT, List.of());

        assertThatThrownBy(() -> publisher.publish(noObjects)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(kafkaTemplate);
    }

    private static RawObjectEvent scannedDoc() {
        RawObjectRef body = new RawObjectRef(Role.BODY, "SCANNED_DOC/demo-tenant/2026-10-18/x.pdf",
                "sha256:" + "a".repeat(64), "application/pdf", 10L, Modality.SCANNED_DOC);
        return RawObjectEvent.create(
                RawSourceType.SCANNED_DOC, "demo-tenant", Instant.now(), ScopeDecision.INCLUDED, null, null,
                "corr-1", 0, ChangeType.UPSERT, List.of(body));
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, Object> sendResult() {
        return mock(SendResult.class);
    }
}
