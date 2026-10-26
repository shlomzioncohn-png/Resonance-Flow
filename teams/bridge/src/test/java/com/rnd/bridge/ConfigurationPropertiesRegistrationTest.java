package com.rnd.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Pure classpath scan - no Spring context, no real Google credentials, no Kafka/Postgres. Guards
 * against the exact bug this test was added for: DriveImportProperties being a
 * @ConfigurationProperties class with no @Component and no entry in BridgeApplication's
 * @EnableConfigurationProperties list, so Spring never created it as a bean at all - invisible
 * until something that depends on it (RealDriveSourceReader) was actually constructed, which only
 * happens once bridge.intake.drive.mode=real.
 */
class ConfigurationPropertiesRegistrationTest {

    @Test
    void everyConfigurationPropertiesClassIsRegisteredAsABean() {
        Set<String> enabledViaAnnotation = Arrays.stream(
                        BridgeApplication.class.getAnnotation(EnableConfigurationProperties.class).value())
                .map(Class::getName)
                .collect(Collectors.toCollection(HashSet::new));

        ClassPathScanningCandidateComponentProvider scanner = configurationPropertiesScanner();

        Set<String> unregistered = scanner.findCandidateComponents("com.rnd.bridge").stream()
                .map(candidate -> candidate.getBeanClassName())
                .filter(className -> !enabledViaAnnotation.contains(className))
                .filter(className -> !isAnnotatedWithComponent(className))
                .collect(Collectors.toSet());

        assertThat(unregistered)
                .as("every @ConfigurationProperties class under com.rnd.bridge must either be listed in "
                        + "BridgeApplication's @EnableConfigurationProperties, or carry its own @Component "
                        + "(or other bean-producing) annotation - otherwise Spring never instantiates it, and "
                        + "any bean that depends on it fails to start the moment something actually needs it")
                .isEmpty();
    }

    private static ClassPathScanningCandidateComponentProvider configurationPropertiesScanner() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        return scanner;
    }

    private static boolean isAnnotatedWithComponent(String className) {
        try {
            Class<?> type = Class.forName(className);
            return type.isAnnotationPresent(Component.class);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Scanned class not found on classpath: " + className, e);
        }
    }
}
