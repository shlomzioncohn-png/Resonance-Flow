package com.rnd.relay.notification_delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NotificationDeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationDeliveryApplication.class, args);
    }
}
