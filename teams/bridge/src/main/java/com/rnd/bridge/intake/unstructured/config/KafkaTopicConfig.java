package com.rnd.bridge.intake.unstructured.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic rawLandedTopic(@Value("${bridge.intake.raw-landed-topic}") String topicName) {
        return TopicBuilder.name(topicName).build();
    }
}
