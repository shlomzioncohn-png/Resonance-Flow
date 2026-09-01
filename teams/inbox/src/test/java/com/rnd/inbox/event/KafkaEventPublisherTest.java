package com.rnd.inbox.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

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

    private KafkaEventPublisher newPublisher(int maxAttempts) {
        return new KafkaEventPublisher(kafkaTemplate, retryScheduler, maxAttempts, 10L, 2.0, 100L);
    }

    @Test
    void publish_successfulSend_completesResultFutureSuccessfully() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(succeeded());

        Event<String> event = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "payload");
        CompletableFuture<Void> result = newPublisher(3).publish("task.created", "task-1", event);

        assertThat(result).isCompleted();
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void publish_retryableFailureThenSuccess_isTreatedAsSuccessAfterRetry() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new TimeoutException("broker unavailable")), succeeded());

        Event<String> event = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "payload");
        CompletableFuture<Void> result = newPublisher(3).publish("task.created", "task-1", event);

        assertThat(result).isCompleted();
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        verify(retryScheduler, times(1)).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void publish_retryableFailureExhaustsMaxAttempts_completesExceptionallyWithWrappedCause() {
        TimeoutException timeoutException = new TimeoutException("broker unavailable");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(timeoutException), failed(timeoutException), failed(timeoutException));

        Event<String> event = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "payload");
        CompletableFuture<Void> result = newPublisher(3).publish("task.created", "task-1", event);

        assertThat(result).isCompletedExceptionally();
        verify(kafkaTemplate, times(3)).send(any(ProducerRecord.class));
        verify(retryScheduler, times(2)).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));

        Throwable thrown = catchThrowable(result::join);
        assertThat(thrown).isInstanceOf(CompletionException.class);
        assertThat(thrown.getCause()).isInstanceOf(EventPublishException.class);
        assertThat(thrown.getCause().getCause()).isSameAs(timeoutException);
    }

    @Test
    void publish_retryableFailureExhaustsMaxAttempts_logsEventTypeTopicEventIdTaskIdAttemptsAndLastError() {
        TimeoutException timeoutException = new TimeoutException("broker unavailable");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(timeoutException), failed(timeoutException), failed(timeoutException));

        Event<String> event = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "payload");
        CompletableFuture<Void> result = newPublisher(3).publish("task.created", "task-1", event);
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
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(failed(new SerializationException("bad payload")));

        Event<String> event = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "payload");
        CompletableFuture<Void> result = newPublisher(3).publish("task.created", "task-1", event);

        assertThat(result).isCompletedExceptionally();
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verifyNoInteractions(retryScheduler);
    }

    @Test
    void publish_buildsHeadersFromMetadataOnly_notFromPayload() {
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(captor.capture())).thenReturn(succeeded());

        UUID correlationId = UUID.randomUUID();
        SamplePayload payload = new SamplePayload("hello");
        Event<SamplePayload> event = Event.newEvent(EventType.TASK_CREATED, "inbox", correlationId, payload);

        newPublisher(3).publish("task.created", "task-1", event);

        @SuppressWarnings("unchecked")
        ProducerRecord<String, Object> record = captor.getValue();
        assertThat(record.value()).isSameAs(payload);
        assertThat(headerValue(record, "event_id")).isEqualTo(event.getEventId().toString());
        assertThat(headerValue(record, "event_type")).isEqualTo("task.created");
        assertThat(headerValue(record, "source_service")).isEqualTo("inbox");
        assertThat(headerValue(record, "correlation_id")).isEqualTo(correlationId.toString());
    }

    @Test
    void publish_omitsCorrelationIdHeader_whenCorrelationIdIsNull() {
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(captor.capture())).thenReturn(succeeded());

        Event<String> event = Event.newEvent(EventType.TASK_CREATED, "inbox", null, "payload");
        newPublisher(3).publish("task.created", "task-1", event);

        @SuppressWarnings("unchecked")
        ProducerRecord<String, Object> record = captor.getValue();
        assertThat(record.headers().lastHeader("correlation_id")).isNull();
    }

    @Test
    void publish_worksForDifferentPayloadTypesWithoutAnyCodeChange() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(succeeded(), succeeded());

        KafkaEventPublisher publisher = newPublisher(3);

        Event<SamplePayload> created = Event.newEvent(EventType.TASK_CREATED, "inbox", null, new SamplePayload("created"));
        Event<OtherPayload> updated = Event.newEvent(EventType.TASK_UPDATED, "inbox", null, new OtherPayload(42));

        CompletableFuture<Void> result1 = publisher.publish(EventType.TASK_CREATED.getTopic(), "task-1", created);
        CompletableFuture<Void> result2 = publisher.publish(EventType.TASK_UPDATED.getTopic(), "task-1", updated);

        assertThat(result1).isCompleted();
        assertThat(result2).isCompleted();
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
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

    private static final class SamplePayload {
        private final String value;

        private SamplePayload(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private static final class OtherPayload {
        private final int count;

        private OtherPayload(int count) {
            this.count = count;
        }

        public int getCount() {
            return count;
        }
    }
}
