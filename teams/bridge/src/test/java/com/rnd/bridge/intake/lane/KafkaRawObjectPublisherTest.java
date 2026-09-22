package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
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
 */
class KafkaRawObjectPublisherTest {

    private static final RawObjectEvent EVENT = RawObjectEvent.create(
            "SLACK/kamatech/2026-08-18/x.json", RawSourceType.SLACK, "kamatech", Instant.parse("2026-08-18T09:15:00Z"),
            ScopeDecision.INCLUDED, "sha256:" + "0".repeat(64), null, List.of("ruth"), "application/json", 2L,
            Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 0, 0, 0, ChangeType.UPSERT);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaRawObjectPublisher publisher = new KafkaRawObjectPublisher(kafkaTemplate, "bridge.raw.landed");

    @Test
    void publishSucceedsWhenTheBrokerAcknowledgesTheSend() {
        SendResult<String, Object> sendResult = new SendResult<>(
                null, new RecordMetadata(new TopicPartition("bridge.raw.landed", 0), 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(eq("bridge.raw.landed"), eq(EVENT.dedupKey()), eq(EVENT)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publish(EVENT);
    }

    @Test
    void publishThrowsLaneExceptionWhenTheSendFutureFailsInsteadOfSilentlySucceeding() {
        CompletableFuture<SendResult<String, Object>> failedSend = new CompletableFuture<>();
        failedSend.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(eq("bridge.raw.landed"), any(), any())).thenReturn(failedSend);

        assertThatThrownBy(() -> publisher.publish(EVENT)).isInstanceOf(LaneException.class);
    }
}
