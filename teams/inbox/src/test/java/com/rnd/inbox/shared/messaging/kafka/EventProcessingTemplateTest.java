package com.rnd.inbox.shared.messaging.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.tenant.TenantNotFoundException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EventProcessingTemplateTest {

    private static final String TOPIC = "bridge.structured.tasks";

    @Mock
    private ErrorTopicPublisher errorTopicPublisher;

    @Mock
    private Acknowledgment acknowledgment;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RetryTemplate retryTemplate(int maxAttempts) {
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(
                maxAttempts, Map.of(TransientProcessingException.class, true), true));
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(1L);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        return retryTemplate;
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(TOPIC, 3, 15234L, "key", value);
    }

    private JsonEventParser<Fixture> parser() {
        return new JsonEventParser<>(objectMapper, Fixture.class);
    }

    @Test
    void validEventIsPersistedAndAcknowledged() {
        Consumer<Fixture> persistHandler = event -> { /* success */ };
        EventProcessingTemplate<String, Fixture> template = new EventProcessingTemplate<>(
                parser(), retryTemplate(3), errorTopicPublisher,
                event -> { }, persistHandler, Fixture::getId);

        template.process(record("{\"id\":\"abc\"}"), acknowledgment);

        verify(acknowledgment).acknowledge();
        verify(errorTopicPublisher, never()).publish(any(), anyString(), anyString(), anyString(), any(), anyInt(), any());
    }

    @Test
    void validationFailureIsNotRetriedAndGoesStraightToErrorTopic() {
        Consumer<Fixture> persistHandler = event -> {
            throw new AssertionError("must not attempt to persist an invalid event");
        };
        EventProcessingTemplate<String, Fixture> template = new EventProcessingTemplate<>(
                parser(), retryTemplate(3), errorTopicPublisher,
                event -> {
                    throw new EventValidationException("bad event", List.of("id is required"));
                },
                persistHandler, Fixture::getId);

        ConsumerRecord<String, String> rec = record("{\"id\":\"abc\"}");
        template.process(rec, acknowledgment);

        verify(errorTopicPublisher).publish(eq(rec), anyString(), eq("VALIDATION_FAILURE"), anyString(),
                eq(List.of("id is required")), eq(0), anyString());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void malformedPayloadIsNotRetriedAndGoesStraightToErrorTopic() {
        Consumer<Fixture> persistHandler = event -> {
            throw new AssertionError("must not attempt to persist an unparseable event");
        };
        EventProcessingTemplate<String, Fixture> template = new EventProcessingTemplate<>(
                parser(), retryTemplate(3), errorTopicPublisher,
                event -> { }, persistHandler, Fixture::getId);

        ConsumerRecord<String, String> rec = record("not-json");
        template.process(rec, acknowledgment);

        verify(errorTopicPublisher).publish(eq(rec), anyString(), eq("VALIDATION_FAILURE"), anyString(),
                eq(List.of()), eq(0), isNull());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void payloadThatDoesNotMatchTheDtoIsTreatedAsOurBugNotAValidationFailure() {
        Consumer<Fixture> persistHandler = event -> {
            throw new AssertionError("must not attempt to persist an unmappable event");
        };
        EventProcessingTemplate<String, Fixture> template = new EventProcessingTemplate<>(
                parser(), retryTemplate(3), errorTopicPublisher,
                event -> { }, persistHandler, Fixture::getId);

        ConsumerRecord<String, String> rec = record("{\"id\":{\"nested\":\"object\"}}");
        template.process(rec, acknowledgment);

        verify(errorTopicPublisher).publish(eq(rec), anyString(), eq("UNEXPECTED_FAILURE"), anyString(),
                eq(List.of()), eq(0), isNull());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void transientFailureIsRetriedUpToMaxAttempts() {
        int[] callCount = {0};
        Consumer<Fixture> persistHandler = event -> {
            callCount[0]++;
            throw new TransientProcessingException("db down", new RuntimeException());
        };
        EventProcessingTemplate<String, Fixture> template = new EventProcessingTemplate<>(
                parser(), retryTemplate(3), errorTopicPublisher,
                event -> { }, persistHandler, Fixture::getId);

        ConsumerRecord<String, String> rec = record("{\"id\":\"abc\"}");
        template.process(rec, acknowledgment);

        assertThat(callCount[0]).isEqualTo(3);
        ArgumentCaptor<Integer> retryCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(errorTopicPublisher).publish(eq(rec), anyString(), eq("TECHNICAL_FAILURE"), anyString(),
                eq(List.of()), retryCountCaptor.capture(), anyString());
        assertThat(retryCountCaptor.getValue()).isEqualTo(3);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void succeedsAfterATransientFailureIsRetried() {
        int[] callCount = {0};
        Consumer<Fixture> persistHandler = event -> {
            callCount[0]++;
            if (callCount[0] < 2) {
                throw new TransientProcessingException("db down", new RuntimeException());
            }
        };
        EventProcessingTemplate<String, Fixture> template = new EventProcessingTemplate<>(
                parser(), retryTemplate(3), errorTopicPublisher,
                event -> { }, persistHandler, Fixture::getId);

        template.process(record("{\"id\":\"abc\"}"), acknowledgment);

        assertThat(callCount[0]).isEqualTo(2);
        verify(errorTopicPublisher, never()).publish(any(), anyString(), anyString(), anyString(), any(), anyInt(), any());
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    void unexpectedFailureIsNotRetriedAndGoesStraightToErrorTopic() {
        int[] callCount = {0};
        Consumer<Fixture> persistHandler = event -> {
            callCount[0]++;
            throw new IllegalStateException("bug: unmapped enum value");
        };
        EventProcessingTemplate<String, Fixture> template = new EventProcessingTemplate<>(
                parser(), retryTemplate(3), errorTopicPublisher,
                event -> { }, persistHandler, Fixture::getId);

        ConsumerRecord<String, String> rec = record("{\"id\":\"abc\"}");
        template.process(rec, acknowledgment);

        assertThat(callCount[0]).isEqualTo(1);
        verify(errorTopicPublisher).publish(eq(rec), anyString(), eq("UNEXPECTED_FAILURE"), anyString(),
                eq(List.of()), eq(1), anyString());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void anEventRejectedOnABusinessRuleIsNotRetriedAndIsReportedAsAValidationFailure() {
        int[] callCount = {0};
        UUID tenantId = UUID.randomUUID();
        Consumer<Fixture> persistHandler = event -> {
            callCount[0]++;
            throw new TenantNotFoundException(tenantId);
        };
        EventProcessingTemplate<String, Fixture> template = new EventProcessingTemplate<>(
                parser(), retryTemplate(3), errorTopicPublisher,
                event -> { }, persistHandler, Fixture::getId);

        ConsumerRecord<String, String> rec = record("{\"id\":\"abc\"}");
        template.process(rec, acknowledgment);

        assertThat(callCount[0]).isEqualTo(1);
        verify(errorTopicPublisher).publish(eq(rec), anyString(), eq("VALIDATION_FAILURE"), anyString(),
                eq(List.of("TENANT_NOT_FOUND")), eq(1), anyString());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void aDataAccessFailureIsClassifiedAsTransientAndRetried() {
        int[] callCount = {0};
        Consumer<Fixture> persistHandler = event -> {
            callCount[0]++;
            throw new QueryTimeoutException("db timeout");
        };
        EventProcessingTemplate<String, Fixture> template = new EventProcessingTemplate<>(
                parser(), retryTemplate(3), errorTopicPublisher,
                event -> { }, persistHandler, Fixture::getId);

        ConsumerRecord<String, String> rec = record("{\"id\":\"abc\"}");
        template.process(rec, acknowledgment);

        assertThat(callCount[0]).isEqualTo(3);
        verify(errorTopicPublisher).publish(eq(rec), anyString(), eq("TECHNICAL_FAILURE"), anyString(),
                eq(List.of()), eq(3), anyString());
        verify(acknowledgment).acknowledge();
    }

    public static class Fixture {
        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
