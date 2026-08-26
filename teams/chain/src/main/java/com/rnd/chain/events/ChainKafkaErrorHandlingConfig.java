package com.rnd.chain.events;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka error handling for Chain's consumers.
 *
 * TODO: this uses Spring Kafka's default DLT naming convention
 * ("<original-topic>.DLT"). Confirm with the team whether the project
 * already has a different DLT naming/topic convention and adjust the
 * recoverer below if so.
 *
 * Retry policy: 3 attempts total, 2 seconds apart. After the last
 * attempt still fails, the message is published to the DLT instead of
 * blocking the consumer.
 */
@Configuration
public class ChainKafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler chainKafkaErrorHandler(
            @Autowired KafkaTemplate<Object, Object> kafkaTemplate) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".DLT", record.partition()));

        FixedBackOff backOff = new FixedBackOff(2000L, 2L);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}