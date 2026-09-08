package com.rnd.chain;

import com.rnd.chain.events.ChainEventTopicsProperties;
import com.rnd.chain.events.ChainKafkaRetryProperties;
import com.rnd.chain.events.ChainOutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// ChainEventTopicsProperties/ChainKafkaRetryProperties/ChainOutboxProperties are
// registered here (not via @Component on the properties classes themselves):
// all are records, and a record @ConfigurationProperties bean needs Spring
// Boot's constructor/value-object binding, which only activates for beans
// registered through @EnableConfigurationProperties (or
// @ConfigurationPropertiesScan) - see ChainEventTopicsProperties's Javadoc
// for what goes wrong with plain @Component.
//
// @EnableScheduling powers ChainOutboxWorker/ChainOutboxCleanupJob's
// @Scheduled methods - harmless when the outbox subsystem itself is dormant
// (see application.yml's spring.autoconfigure.exclude comment), since with
// no DataSource there is no ChainOutboxEventRepository bean for those
// classes to be constructed with in the first place.
@SpringBootApplication
@EnableConfigurationProperties({ChainEventTopicsProperties.class, ChainKafkaRetryProperties.class,
        ChainOutboxProperties.class})
@EnableScheduling
public class ChainApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChainApplication.class, args);
    }
}
