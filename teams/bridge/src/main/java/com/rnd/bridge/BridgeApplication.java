package com.rnd.bridge;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.config.ScopingProperties;
import com.rnd.bridge.intake.config.WhatsAppImportProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.rnd.bridge.media.classification.MediaClassificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ScopingProperties.class, ChatSessionProperties.class, WhatsAppImportProperties.class,
        MediaClassificationProperties.class})
public class BridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(BridgeApplication.class, args);
    }
}
