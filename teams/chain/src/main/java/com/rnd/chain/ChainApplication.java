package com.rnd.chain;

import com.rnd.chain.events.ChainEventTopicsProperties;
import com.rnd.chain.events.ChainKafkaRetryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// ChainEventTopicsProperties/ChainKafkaRetryProperties are registered here (not via
// @Component on the properties classes themselves): both are records, and a
// record @ConfigurationProperties bean needs Spring Boot's constructor/value-object
// binding, which only activates for beans registered through
// @EnableConfigurationProperties (or @ConfigurationPropertiesScan) - see
// ChainEventTopicsProperties's Javadoc for what goes wrong with plain @Component.
@SpringBootApplication
@EnableConfigurationProperties({ChainEventTopicsProperties.class, ChainKafkaRetryProperties.class})
public class ChainApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChainApplication.class, args);
    }
}
