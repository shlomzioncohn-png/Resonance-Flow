package com.rnd.bridge;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.config.ScopingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ScopingProperties.class, ChatSessionProperties.class})
public class BridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(BridgeApplication.class, args);
    }
}
