package com.rnd.chain.events;

import com.rnd.chain.events.testsupport.TestPayload;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises ChainEventConsumer against a real embedded broker and the real
 * ChainKafkaErrorHandlingConfig, so the retry-then-DLT and
 * no-retry-then-DLT behaviors are actually verified end to end - a unit
 * test that only calls onMessage()/dispatch() directly (see
 * ChainEventConsumerTest) can't see DefaultErrorHandler's retry/recovery
 * wiring run at all, since that only happens inside a real listener
 * container.
 *
 * chain.kafka.consumed-topics is overridden here specifically to activate
 * ChainEventConsumer's @ConditionalOnProperty bean for this test only -
 * it stays unset in application.yml itself (see that file's comment).
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
                ChainEventConsumerIntegrationTest.TEST_TOPIC,
                ChainEventConsumerIntegrationTest.TEST_TOPIC + ".DLT"
        })
@TestPropertySource(properties = {
        "chain.kafka.consumed-topics=" + ChainEventConsumerIntegrationTest.TEST_TOPIC,
        "chain.kafka.consumer-group-id=chain-event-consumer-it"
})
// classMode is deliberately AFTER_EACH_TEST_METHOD, not the default
// AFTER_CLASS: a bare @DirtiesContext on the class only tears the context
// down once, after all three @Test methods run, so without this they would
// share one embedded broker, one consumer group, and one running listener
// container (and its DefaultErrorHandler state) across the whole class.
// Each test here publishes to the same TEST_TOPIC and reads from the same
// DLT topic, so that sharing lets one test's retry/recovery activity leak
// into the next test's assertions - exactly the kind of cross-test
// contamination that makes "expected 0, got 1"-style failures show up.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ChainEventConsumerIntegrationTest {

    static final String TEST_TOPIC = "chain.events.it.test-topic";
    private static final String EVENT_TYPE = "it.test.event";

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private RecordingEventHandler recordingEventHandler;

    private Consumer<String, String> dltConsumer;

    @TestConfiguration
    static class TestHandlerConfig {
        @Bean
        RecordingEventHandler recordingEventHandler() {
            return new RecordingEventHandler();
        }
    }

    /**
     * Test-only EventHandler for EVENT_TYPE. Counts invocations and can be
     * configured, per test, to fail a fixed number of times before
     * succeeding, or to always fail - simulating a transient failure that
     * eventually recovers vs. one that never does.
     */
    static class RecordingEventHandler implements EventHandler<TestPayload> {
        private final AtomicInteger invocations = new AtomicInteger(0);
        private volatile int failuresBeforeSuccess = 0;
        private volatile RuntimeException failureToThrow = null;

        @Override
        public String getSupportedEventType() {
            return EVENT_TYPE;
        }

        @Override
        public Class<TestPayload> getPayloadType() {
            return TestPayload.class;
        }

        @Override
        public void handle(EventEnvelope<TestPayload> event) {
            int attempt = invocations.incrementAndGet();
            if (failureToThrow != null) {
                throw failureToThrow;
            }
            if (attempt <= failuresBeforeSuccess) {
                throw new RuntimeException("simulated transient failure, attempt " + attempt);
            }
        }

        void reset() {
            invocations.set(0);
            failuresBeforeSuccess = 0;
            failureToThrow = null;
        }

        int getInvocationCount() {
            return invocations.get();
        }

        void failTimesThenSucceed(int times) {
            this.failuresBeforeSuccess = times;
        }

        void alwaysFailWith(RuntimeException exception) {
            this.failureToThrow = exception;
        }
    }

    @BeforeEach
    void setUp() {
        recordingEventHandler.reset();
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "chain-event-consumer-it-dlt-reader", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        dltConsumer = new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, TEST_TOPIC + ".DLT");
    }

    @AfterEach
    void tearDown() {
        if (dltConsumer != null) {
            dltConsumer.close();
        }
    }

    @Test
    void transientFailure_retriesThenSucceeds_neverReachesDlt() {
        // Fails on the first 2 attempts, succeeds on the 3rd - within the
        // configured 3-attempt FixedBackOff, so it should succeed without
        // ever reaching the DLT.
        recordingEventHandler.failTimesThenSucceed(2);

        publishTestEvent(UUID.randomUUID(), EVENT_TYPE, validPayloadJson());

        await().atMost(Duration.ofMillis(15_000))
                .alias("expected exactly 3 handler invocations (2 failures + 1 success)")
                .until(() -> recordingEventHandler.getInvocationCount() == 3);

        ConsumerRecords<String, String> dltRecords = KafkaTestUtils.getRecords(dltConsumer, Duration.ofMillis(2_000L));
        assertEquals(0, dltRecords.count(), "a transient failure that eventually succeeds must not reach the DLT");
    }

    @Test
    void transientFailure_exhaustsRetries_reachesDlt() {
        // Always throws a plain RuntimeException - retryable, but never
        // succeeds, so it should exhaust all 3 attempts and land on the DLT.
        recordingEventHandler.alwaysFailWith(new RuntimeException("permanently down for this test"));

        publishTestEvent(UUID.randomUUID(), EVENT_TYPE, validPayloadJson());

        ConsumerRecords<String, String> dltRecords = KafkaTestUtils.getRecords(dltConsumer, Duration.ofMillis(15_000L));
        assertEquals(1, dltRecords.count());
        assertEquals(3, recordingEventHandler.getInvocationCount(),
                "a retryable failure that never succeeds should be attempted exactly 3 times before the DLT");
    }

    @Test
    void permanentFailure_missingEventType_skipsRetryAndReachesDltImmediately() {
        // Missing eventType entirely - NonRetryableEventException, thrown
        // before any handler is even looked up.
        String malformed = String.format(
                "{\"eventId\":\"%s\",\"occurredAt\":\"%s\",\"payload\":{}}",
                UUID.randomUUID(), Instant.now());
        kafkaTemplate.send(TEST_TOPIC, "key", malformed);

        ConsumerRecords<String, String> dltRecords = KafkaTestUtils.getRecords(dltConsumer, Duration.ofMillis(15_000L));
        assertEquals(1, dltRecords.count());
        assertEquals(0, recordingEventHandler.getInvocationCount(),
                "a malformed event never reaches any handler, retryable or not");
    }

    private void publishTestEvent(UUID eventId, String eventType, String payloadJson) {
        String message = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"occurredAt\":\"%s\",\"payload\":%s}",
                eventId, eventType, Instant.now(), payloadJson);
        kafkaTemplate.send(TEST_TOPIC, eventId.toString(), message);
    }

    private String validPayloadJson() {
        return "{\"value\":\"hello\"}";
    }
}
