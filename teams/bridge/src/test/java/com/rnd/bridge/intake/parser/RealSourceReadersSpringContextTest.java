package com.rnd.bridge.intake.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.parser.drive.RealDriveSourceReader;
import com.rnd.bridge.intake.parser.email.RealEmailSourceReader;
import com.rnd.bridge.intake.parser.slack.RealSlackSourceReader;
import com.rnd.bridge.intake.parser.whatsapp.RealWhatsAppSourceReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots a real Spring context with every unstructured-intake lane's mode flipped to "real", so
 * each Real*SourceReader actually gets constructed by Spring (constructor autowiring), not by a
 * test calling `new` directly. Guards against the exact bug this test was added for:
 * RealSlackSourceReader gained a second, test-only constructor (the mocked-MethodsClient seam)
 * without @Autowired on the intended one, so Spring saw two candidate constructors and refused
 * to pick either ("No default constructor found") - invisible to every existing unit test, which
 * only ever called `new RealSlackSourceReader(...)` directly.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "bridge.crm.organization-id=org-test-0000",
        "bridge.postgres.url=jdbc:postgresql://localhost:5432/bridge_test",
        "bridge.postgres.user=test",
        "bridge.postgres.password=test",
        "spring.sql.init.mode=never",
        "bridge.intake.drive.mode=real",
        "bridge.intake.email.mode=real",
        "bridge.intake.whatsapp.mode=real",
        "bridge.intake.whatsapp.import-dir=.",
        "bridge.intake.slack.mode=real",
        "bridge.intake.slack.bot-token=xoxb-test",
        "bridge.intake.slack.channels=C123"
})
class RealSourceReadersSpringContextTest {

    @Autowired
    private RealDriveSourceReader driveReader;
    @Autowired
    private RealEmailSourceReader emailReader;
    @Autowired
    private RealSlackSourceReader slackReader;
    @Autowired
    private RealWhatsAppSourceReader whatsAppReader;

    @Test
    void everyRealSourceReaderIsConstructedByTheContextWithoutAmbiguousConstructorFailure() {
        assertThat(driveReader).isNotNull();
        assertThat(emailReader).isNotNull();
        assertThat(slackReader).isNotNull();
        assertThat(whatsAppReader).isNotNull();
    }
}
