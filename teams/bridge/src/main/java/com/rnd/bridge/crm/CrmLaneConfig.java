package com.rnd.bridge.crm;

import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.publish.LoggingPayloadPublisher;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.model.Task;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean wiring for {@link CrmLane}'s collaborators that intentionally carry
 * no Spring annotations of their own - {@link DealToTaskMapper} and
 * {@link TaskValidator} are plain, framework-free classes shared or shareable
 * across lanes - plus the logging stand-in that publishes in place of Kafka.
 */
@Configuration
class CrmLaneConfig {

    @Bean
    DealToTaskMapper dealToTaskMapper() {
        return new DealToTaskMapper();
    }

    @Bean
    TaskValidator taskValidator() {
        return new TaskValidator();
    }

    @Bean
    LoggingPayloadPublisher<Task> taskPublisher() {
        return new LoggingPayloadPublisher<>();
    }
}
