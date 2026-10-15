package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.raw.RawObjectEventProtoMapper;
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
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * publish() must block on the send future so a broker failure is observable synchronously -
 * a fire-and-forget send() would let AbstractIntakeLane count a record as published (and run
 * afterPublish, e.g. chat-group confirmation) before Kafka ever actually acknowledged it.
 * Asserts the Protobuf-mapped value is what's actually sent, not the domain record - the
 * wire format migrated from JSON to Protobuf; RawObjectEventProtoMapperTest covers the
 * mapping itself field-by-field.
 */
class KafkaRawObjectPublisherTest {

    // Modality.AUDIO (not null) - the RawObjectEventProtoMapper this publisher now depends on
    // switches on modality, and RawObjectValidator already requires it non-null in production;
    // this fixture predates both and used null harmlessly under the old JSON serializer.
    private static final RawObjectEvent EVENT = RawObjectEvent.create(
            RawSourceType.SLACK, "kamatech", Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
            List.of("ruth"), Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 0, ChangeType.UPSERT,
            List.of(new RawObjectRef(Role.TRANSCRIPT, "SLACK/kamatech/2026-08-18/x.json",
                    "sha256:" + "0".repeat(64), "application/json", 2L, Modality.AUDIO)));

    private static final com.rnd.bridge.shared.unstructured.dto.RawObjectEvent PROTO_EVENT =
            new RawObjectEventProtoMapper().toProto(EVENT);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final RawObjectEventProtoMapper protoMapper = new RawObjectEventProtoMapper();
    private final KafkaRawObjectPublisher publisher =
            new KafkaRawObjectPublisher(kafkaTemplate, protoMapper, "bridge.raw.landed");

    @Test
    void publishSucceedsWhenTheBrokerAcknowledgesTheSend() {
        SendResult<String, Object> sendResult = new SendResult<>(
                null, new RecordMetadata(new TopicPartition("bridge.raw.landed", 0), 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(eq("bridge.raw.landed"), eq(EVENT.dedupKey()), eq(PROTO_EVENT)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publish(EVENT);
    }

    @Test
    void publishSendsTheProtobufMappedValueNotTheDomainRecord() {
        SendResult<String, Object> sendResult = new SendResult<>(
                null, new RecordMetadata(new TopicPartition("bridge.raw.landed", 0), 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(eq("bridge.raw.landed"), eq(EVENT.dedupKey()), eq(PROTO_EVENT)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publish(EVENT);

        org.mockito.Mockito.verify(kafkaTemplate)
                .send(eq("bridge.raw.landed"), eq(EVENT.dedupKey()), org.mockito.ArgumentMatchers.isA(
                        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent.class));
    }

    @Test
    void publishThrowsLaneExceptionWhenTheSendFutureFailsInsteadOfSilentlySucceeding() {
        CompletableFuture<SendResult<String, Object>> failedSend = new CompletableFuture<>();
        failedSend.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(eq("bridge.raw.landed"), any(), any())).thenReturn(failedSend);

        assertThatThrownBy(() -> publisher.publish(EVENT)).isInstanceOf(LaneException.class);
    }
}
