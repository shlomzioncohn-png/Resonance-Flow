package com.rnd.bridge.publish.kafka;

import com.rnd.bridge.core.AbstractIntakeLane;
import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.core.error.PublishException;
import com.rnd.bridge.task.BridgeTaskEventDto;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uses a mocked {@link KafkaTemplate} throughout - no test here talks to a
 * broker. The full suite already loses ~40s to KafkaAdmin retrying against
 * an absent broker in the tests that boot a real Spring context; nothing in
 * this class should add to that.
 */
@ExtendWith(MockitoExtension.class)
class KafkaPayloadPublisherTest {

    private static final String TOPIC = "bridge.structured.tasks";

    @Mock
    private KafkaTemplate<String, BridgeTaskEventDto> kafkaTemplate;

    private KafkaPayloadPublisher publisher() {
        return new KafkaPayloadPublisher(kafkaTemplate, TOPIC);
    }

    private static Task task(String sourceRef) {
        return new Task(
                null, null,
                "Title", "Description",
                TaskStatus.NEW, 3, null,
                Tier.TACTICAL, ExtractionProvenance.ETL, RoleContext.SALESPERSON,
                SourceType.CRM_DEAL, sourceRef,
                null, null, null, null);
    }

    private static SendResult<String, BridgeTaskEventDto> sendResult(Task task) {
        BridgeTaskEventDto event = BridgeTaskEventDto.from(task);
        ProducerRecord<String, BridgeTaskEventDto> record = new ProducerRecord<>(TOPIC, task.dedupKey(), event);
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(TOPIC, 0), 0, 0, 0, 0, 0);
        return new SendResult<>(record, metadata);
    }

    @Test
    void publishesToTheConfiguredTopicKeyedByDedupKey() {
        Task task = task("crm:D-1");
        when(kafkaTemplate.send(anyString(), anyString(), any(BridgeTaskEventDto.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult(task)));

        publisher().publish(task);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BridgeTaskEventDto> eventCaptor = ArgumentCaptor.forClass(BridgeTaskEventDto.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), eventCaptor.capture());

        assertEquals(TOPIC, topicCaptor.getValue());
        assertEquals(task.dedupKey(), keyCaptor.getValue());
        assertEquals(task.sourceRef(), eventCaptor.getValue().sourceRef());
    }

    @Test
    void aSendFailureSurfacesAsAPublishException() {
        Task task = task("crm:D-1");
        CompletableFuture<SendResult<String, BridgeTaskEventDto>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaTemplate.send(anyString(), anyString(), any(BridgeTaskEventDto.class)))
                .thenReturn(failed);

        assertThrows(PublishException.class, () -> publisher().publish(task));
    }

    @Test
    void aSendFailureIsRecordedByTheLaneAsAFailedRecordNotAnAbortedRun() {
        Task failing = task("crm:D-1");
        Task succeeding = task("crm:D-2");

        CompletableFuture<SendResult<String, BridgeTaskEventDto>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaTemplate.send(anyString(), eq(failing.dedupKey()), any(BridgeTaskEventDto.class)))
                .thenReturn(failedFuture);
        when(kafkaTemplate.send(anyString(), eq(succeeding.dedupKey()), any(BridgeTaskEventDto.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult(succeeding)));

        PayloadPublisher<Task> publisher = publisher();
        AbstractIntakeLane<Task, Task> lane = new AbstractIntakeLane<>() {
            @Override
            protected SourceReader<Task> sourceReader() {
                return cursor -> Stream.of(failing, succeeding);
            }

            @Override
            protected PayloadMapper<Task, Task> payloadMapper() {
                return (source, asOf) -> source;
            }

            @Override
            protected PayloadValidator<Task> payloadValidator() {
                return payload -> { };
            }

            @Override
            protected PayloadPublisher<Task> payloadPublisher() {
                return publisher;
            }
        };

        IntakeResult result = lane.run(ReadCursor.full(), Instant.parse("2026-08-26T00:00:00Z"));

        assertEquals(2, result.read());
        assertEquals(1, result.published());
        assertEquals(1, result.rejected());
        assertInstanceOf(PublishException.class, result.errors().get(0));
    }
}
