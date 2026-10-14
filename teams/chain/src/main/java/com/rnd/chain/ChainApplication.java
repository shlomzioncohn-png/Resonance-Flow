package com.rnd.chain;

import com.rnd.chain.events.ChainEventTopicsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// ChainEventTopicsProperties is registered here (not via @Component on the properties class
// itself): it's a record, and a record @ConfigurationProperties bean needs Spring Boot's
// constructor/value-object binding, which only activates for beans registered through
// @EnableConfigurationProperties (or @ConfigurationPropertiesScan) - see
// ChainEventTopicsProperties's Javadoc for what goes wrong with plain @Component.
//
// ChainKafkaRetryProperties (the old blocking-retry policy for the now-retired generic
// ChainEventConsumer) was removed along with ChainKafkaErrorHandlingConfig - #271's handlers use
// @RetryableTopic's own non-blocking retry instead, per the team lead's explicit direction.
@SpringBootApplication
@EnableConfigurationProperties(ChainEventTopicsProperties.class)
public class ChainApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChainApplication.class, args);
    }
}
