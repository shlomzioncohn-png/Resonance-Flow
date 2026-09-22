package com.rnd.inbox.shared.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.protobuf.Message;
import com.google.protobuf.Struct;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> protobufKafkaTemplate;

    @Mock
    private ScheduledExecutorService retryScheduler;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        lenient().when(retryScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    Runnable task = invocation.getArgument(0);
                    task.run();
                    return null;
                });

        logAppender = new ListAppender<>();
        logAppender.start();
        publisherLogger().addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        publisherLogger().detachAppender(logAppender);
    }

    private static Logger publisherLogger() {
        return (Logger) LoggerFactory.getLogger(KafkaEventPublisher.class);
    }

    private KafkaEventPublisher newPublisher(int maxAttempts, ProtoEventMapper<?>... mappers) {
        return new KafkaEventPublisher(
                protobufKafkaTemplate, List.of(mappers), retryScheduler, maxAttempts, 10L, 2.0, 100L);
    }

    private ProtoEventMapper<Message> mapperFor(EventType eventType, Message toProtoResult) {
        @SuppressWarnings("unchecked")
        ProtoEventMapper<Message> mapper = mock(ProtoEventMapper.class);
        lenient().when(mapper.supportedType()).thenReturn(eventType);
        lenient().when(mapper.toProto(any())).thenReturn(toProtoResult);
        return mapper;
    }

    private Event<Map<String, Object>> newEvent(EventType eventType, UUID correlationId) {
        return Event.newEvent(eventType, "inbox", correlationId, Map.of("taskId", "task-1"));
    }

    @Test
    void publish_successfulSend_completesResultFutureSuccessfully() {
        when(protobufKafkaTemplate.send(any(ProducerRecord.class))).thenReturn(succeeded());
        KafkaEventPublisher publisher = newPublisher(3, mapperFor(EventType.TASK_CREATED, Struct.getDefaultInstance()));

        Event<Map<String, Object>> event = newEvent(EventType.TASK_CREATED, null);
        CompletableFuture<Void> result = publisher.publish("task.created", "task-1", event);

        assertThat(result).isCompleted();
        verify(protobufKafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void publish_retryableFailureThenSuccess_isTreatedAsSuccessAfterRetry() {
        when(protobufKafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new TimeoutException("broker unavailable")), succeeded());
        KafkaEventPublisher publisher = newPublisher(3, mapperFor(EventType.TASK_CREATED, Struct.getDefaultInstance()));

        Event<Map<String, Object>> event = newEvent(EventType.TASK_CREATED, null);
        CompletableFuture<Void> result = publisher.publish("task.created", "task-1", event);

        assertThat(result).isCompleted();
        verify(protobufKafkaTemplate, times(2)).send(any(ProducerRecord.class));
        verify(retryScheduler, times(1)).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void publish_retryableFailureExhaustsMaxAttempts_completesExceptionallyWithWrappedCause() {
        TimeoutException timeoutException = new TimeoutException("broker unavailable");
        when(protobufKafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(timeoutException), failed(timeoutException), failed(timeoutException));
        KafkaEventPublisher publisher = newPublisher(3, mapperFor(EventType.TASK_CREATED, Struct.getDefaultInstance()));

        Event<Map<String, Object>> event = newEvent(EventType.TASK_CREATED, null);
        CompletableFuture<Void> result = publisher.publish("task.created", "task-1", event);

        assertThat(result).isCompletedExceptionally();
        verify(protobufKafkaTemplate, times(3)).send(any(ProducerRecord.class));
        verify(retryScheduler, times(2)).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));

        Throwable thrown = catchThrowable(result::join);
        assertThat(thrown).isInstanceOf(CompletionException.class);
        assertThat(thrown.getCause()).isInstanceOf(EventPublishException.class);
        assertThat(thrown.getCause().getCause()).isSameAs(timeoutException);
    }

    @Test
    void publish_retryableFailureExhaustsMaxAttempts_logsEventTypeTopicEventIdTaskIdAttemptsAndLastError() {
        TimeoutException timeoutException = new TimeoutException("broker unavailable");
        when(protobufKafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(timeoutException), failed(timeoutException), failed(timeoutException));
        KafkaEventPublisher publisher = newPublisher(3, mapperFor(EventType.TASK_CREATED, Struct.getDefaultInstance()));

        Event<Map<String, Object>> event = newEvent(EventType.TASK_CREATED, null);
        CompletableFuture<Void> result = publisher.publish("task.created", "task-1", event);
        catchThrowable(result::join);

        ILoggingEvent errorLog = logAppender.list.stream()
                .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an ERROR log entry for the exhausted publish"));

        String message = errorLog.getFormattedMessage();
        assertThat(message).contains("after 3 attempt(s)");
        assertThat(message).contains("eventType=task.created");
        assertThat(message).contains("topic=task.created");
        assertThat(message).contains("eventId=" + event.getEventId());
        assertThat(message).contains("task_id=task-1");
        assertThat(message).contains("lastError=broker unavailable");
        assertThat(errorLog.getThrowableProxy()).isNotNull();
        assertThat(errorLog.getThrowableProxy().getMessage()).isEqualTo("broker unavailable");
    }

    @Test
    void publish_nonRetryableFailure_failsImmediatelyWithoutSchedulingRetry() {
        when(protobufKafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new SerializationException("bad payload")));
        KafkaEventPublisher publisher = newPublisher(3, mapperFor(EventType.TASK_CREATED, Struct.getDefaultInstance()));

        Event<Map<String, Object>> event = newEvent(EventType.TASK_CREATED, null);
        CompletableFuture<Void> result = publisher.publish("task.created", "task-1", event);

        assertThat(result).isCompletedExceptionally();
        verify(protobufKafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verify(retryScheduler, times(0)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void publish_buildsHeadersFromMetadata_andSendsTheMappedProtoValue_notTheRawPayload() {
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(protobufKafkaTemplate.send(captor.capture())).thenReturn(succeeded());
        Struct mappedValue = Struct.getDefaultInstance();
        KafkaEventPublisher publisher = newPublisher(3, mapperFor(EventType.TASK_CREATED, mappedValue));

        UUID correlationId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("taskId", "task-1");
        Event<Map<String, Object>> event = Event.newEvent(EventType.TASK_CREATED, "inbox", correlationId, payload);

        publisher.publish("task.created", "task-1", event);

        @SuppressWarnings("unchecked")
        ProducerRecord<String, Object> record = captor.getValue();
        assertThat(record.value()).isSameAs(mappedValue);
        assertThat(record.value()).isNotSameAs(payload);
        assertThat(headerValue(record, "event_id")).isEqualTo(event.getEventId().toString());
        assertThat(headerValue(record, "event_type")).isEqualTo("task.created");
        assertThat(headerValue(record, "source_service")).isEqualTo("inbox");
        assertThat(headerValue(record, "correlation_id")).isEqualTo(correlationId.toString());
    }

    @Test
    void publish_omitsCorrelationIdHeader_whenCorrelationIdIsNull() {
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(protobufKafkaTemplate.send(captor.capture())).thenReturn(succeeded());
        KafkaEventPublisher publisher = newPublisher(3, mapperFor(EventType.TASK_CREATED, Struct.getDefaultInstance()));

        Event<Map<String, Object>> event = newEvent(EventType.TASK_CREATED, null);
        publisher.publish("task.created", "task-1", event);

        @SuppressWarnings("unchecked")
        ProducerRecord<String, Object> record = captor.getValue();
        assertThat(record.headers().lastHeader("correlation_id")).isNull();
    }

    @Test
    void publish_dispatchesEachEventTypeThroughItsOwnRegisteredMapper() {
        when(protobufKafkaTemplate.send(any(ProducerRecord.class))).thenReturn(succeeded(), succeeded());
        Struct createdValue = Struct.getDefaultInstance();
        Struct updatedValue = Struct.newBuilder().putFields("marker", com.google.protobuf.Value.newBuilder()
                .setBoolValue(true).build()).build();
        KafkaEventPublisher publisher = newPublisher(3,
                mapperFor(EventType.TASK_CREATED, createdValue), mapperFor(EventType.TASK_UPDATED, updatedValue));

        Event<Map<String, Object>> created = newEvent(EventType.TASK_CREATED, null);
        Event<Map<String, Object>> updated = newEvent(EventType.TASK_UPDATED, null);

        CompletableFuture<Void> result1 = publisher.publish(EventType.TASK_CREATED.getTopic(), "task-1", created);
        CompletableFuture<Void> result2 = publisher.publish(EventType.TASK_UPDATED.getTopic(), "task-1", updated);

        assertThat(result1).isCompleted();
        assertThat(result2).isCompleted();
        verify(protobufKafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void publish_throwsIllegalStateException_whenNoMapperRegisteredForEventType() {
        KafkaEventPublisher publisher = newPublisher(3);

        Event<Map<String, Object>> event = newEvent(EventType.TASK_CREATED, null);

        assertThatThrownBy(() -> publisher.publish("task.created", "task-1", event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TASK_CREATED");
    }

    @Test
    void publish_throwsIllegalStateException_whenPayloadIsNotAMap() {
        KafkaEventPublisher publisher = newPublisher(3, mapperFor(EventType.TASK_CREATED, Struct.getDefaultInstance()));

        Event<String> event = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "not-a-map");

        assertThatThrownBy(() -> publisher.publish("task.created", "task-1", event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Map");
    }

    private static CompletableFuture<SendResult<String, Object>> succeeded() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, Object>> failed(Throwable ex) {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }

    private static String headerValue(ProducerRecord<String, Object> record, String key) {
        return new String(record.headers().lastHeader(key).value(), StandardCharsets.UTF_8);
    }
}
