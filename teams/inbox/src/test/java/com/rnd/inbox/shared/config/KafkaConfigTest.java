package com.rnd.inbox.shared.config;
import com.rnd.inbox.shared.messaging.kafka.ErrorTopicPublisher;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaConfigTest {

    private static final String TOPIC = "task.scheduling.updated";

    private ErrorTopicPublisher errorTopicPublisher;
    private DefaultErrorHandler handler;
    private Consumer<?, ?> consumer;
    private MessageListenerContainer container;
    private ConsumerRecord<String, Object> record;

    @BeforeEach
    void setUp() {
        errorTopicPublisher = mock(ErrorTopicPublisher.class);
        KafkaRetryProperties retryProperties = new KafkaRetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setInitialBackoffMs(1);
        retryProperties.setMaxBackoffMs(1);
        handler = new KafkaConfig().protobufListenerErrorHandler(errorTopicPublisher, retryProperties);
        consumer = mock(Consumer.class);
        container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        record = new ConsumerRecord<>(TOPIC, 0, 2L, "key", null);
    }

    @Test
    void protobufKafkaProducerFactory_always_usesLatestRegisteredSchemaWithoutAutoRegistering() {
        Map<String, Object> props = new KafkaConfig()
                .protobufKafkaProducerFactory(new KafkaProperties(), "mock://not-dialed")
                .getConfigurationProperties();

        assertThat(props)
                .containsEntry(KafkaProtobufSerializerConfig.AUTO_REGISTER_SCHEMAS, false)
                .containsEntry(KafkaProtobufSerializerConfig.USE_LATEST_VERSION, true);
    }

    @Test
    void protobufListenerErrorHandler_whenRegistryIsUnreachable_retriesInsteadOfPublishingToErrorTopic() {
        Exception failure = deserializationFailure(
                new SerializationException("Error deserializing Protobuf message",
                        new ConnectException("Connection refused")));

        // Seeking back to the failed offset and throwing is how DefaultErrorHandler schedules a retry.
        assertThatThrownBy(() -> handleRemaining(failure)).isInstanceOf(KafkaException.class);

        verify(consumer).seek(any(), eq(2L));
        verifyNothingPublished();
    }

    @Test
    void protobufListenerErrorHandler_whenRegistryReturns5xx_retriesInsteadOfPublishingToErrorTopic() {
        Exception failure = deserializationFailure(
                new SerializationException("Error retrieving Protobuf schema",
                        new RestClientException("Service Unavailable", 503, 50301)));

        // Seeking back to the failed offset and throwing is how DefaultErrorHandler schedules a retry.
        assertThatThrownBy(() -> handleRemaining(failure)).isInstanceOf(KafkaException.class);

        verify(consumer).seek(any(), eq(2L));
        verifyNothingPublished();
    }

    @Test
    void protobufListenerErrorHandler_whenRegistryStaysUnreachableForAllAttempts_publishesTechnicalFailure() {
        Exception failure = deserializationFailure(
                new SerializationException("Error deserializing Protobuf message",
                        new ConnectException("Connection refused")));

        assertThatThrownBy(() -> handleRemaining(failure)).isInstanceOf(KafkaException.class);
        assertThatThrownBy(() -> handleRemaining(failure)).isInstanceOf(KafkaException.class);
        verifyNothingPublished();

        assertThatCode(() -> handleRemaining(failure)).doesNotThrowAnyException();

        verify(errorTopicPublisher).publish(eq(record), isNull(), eq("TECHNICAL_FAILURE"), anyString(),
                anyList(), eq(3), isNull());
    }

    @Test
    void protobufListenerErrorHandler_whenSchemaIdIsUnknown_publishesValidationFailureWithoutRetry() {
        Exception failure = deserializationFailure(
                new SerializationException("Error retrieving Protobuf schema for id 999",
                        new RestClientException("Schema 999 not found", 404, 40403)));

        assertThatCode(() -> handleRemaining(failure)).doesNotThrowAnyException();

        verify(errorTopicPublisher).publish(eq(record), isNull(), eq("VALIDATION_FAILURE"), anyString(),
                anyList(), eq(0), isNull());
        verify(consumer, never()).seek(any(), eq(2L));
    }

    @Test
    void protobufListenerErrorHandler_whenMagicByteIsUnknown_publishesValidationFailureWithoutRetry() {
        Exception failure = deserializationFailure(new SerializationException("Unknown magic byte!"));

        assertThatCode(() -> handleRemaining(failure)).doesNotThrowAnyException();

        verify(errorTopicPublisher).publish(eq(record), isNull(), eq("VALIDATION_FAILURE"), anyString(),
                anyList(), eq(0), isNull());
        verify(consumer, never()).seek(any(), eq(2L));
    }

    private void handleRemaining(Exception failure) {
        handler.handleRemaining(failure, List.of(record), consumer, container);
    }

    private Exception deserializationFailure(Throwable cause) {
        return new ListenerExecutionFailedException("Listener failed",
                new DeserializationException("failed to deserialize", new byte[0], false, cause));
    }

    private void verifyNothingPublished() {
        verify(errorTopicPublisher, never()).publish(any(), any(), anyString(), anyString(), anyList(), anyInt(), any());
    }
}
