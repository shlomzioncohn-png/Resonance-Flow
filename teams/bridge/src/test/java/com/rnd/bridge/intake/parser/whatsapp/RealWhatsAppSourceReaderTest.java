package com.rnd.bridge.intake.parser.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rnd.bridge.intake.config.BridgeTenantId;
import com.rnd.bridge.intake.config.WhatsAppImportProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Confirms RealWhatsAppSourceReader parses a real exported .txt file (+ its attachment) the
 * same way FixtureWhatsAppSourceReader parses the classpath fixtures - only the file source
 * differs, both go through the shared WhatsAppExportLineParser.
 */
class RealWhatsAppSourceReaderTest {

    @TempDir
    Path importDir;

    @Test
    void readsAnExportedChatAndItsAttachment() throws IOException {
        Files.writeString(importDir.resolve("family-chat.txt"), String.join("\n",
                "01/03/2026, 09:00 - אמא: מי מביא עוגה?",
                "01/03/2026, 09:02 - מלכי: אני אביא",
                "01/03/2026, 09:05 - מלכי: <attached: cake-photo.jpg>",
                ""));
        Files.write(importDir.resolve("cake-photo.jpg"), new byte[] {1, 2, 3});

        RealWhatsAppSourceReader reader = new RealWhatsAppSourceReader(
                new WhatsAppImportProperties(importDir.toString(), "Asia/Jerusalem"), new BridgeTenantId("kamatech"));
        List<ChatMessage> messages = reader.read();

        assertThat(messages).hasSize(3);
        assertThat(messages).allMatch(m -> m.chatId().equals("family-chat"));
        assertThat(messages).allMatch(m -> m.tenantId().equals("kamatech"));

        Instant expectedFirstTimestamp = ZonedDateTime.of(2026, 3, 1, 9, 0, 0, 0, ZoneId.of("Asia/Jerusalem"))
                .toInstant();
        assertThat(messages.get(0).timestamp()).isEqualTo(expectedFirstTimestamp);

        ChatMessage attachmentMessage = messages.stream()
                .filter(m -> !m.attachments().isEmpty())
                .findFirst().orElseThrow();
        ChatMessage.Attachment attachment = attachmentMessage.attachments().get(0);
        assertThat(attachment.filename()).isEqualTo("cake-photo.jpg");
        assertThat(attachment.mimeType()).isEqualTo("image/jpeg");
        assertThat(attachment.content()).containsExactly(1, 2, 3);
    }

    @Test
    void goneAttachmentIsSkippedButTheMessageAndOtherMessagesStillRead() throws IOException {
        Files.writeString(importDir.resolve("family-chat.txt"), String.join("\n",
                "01/03/2026, 09:00 - אמא: מי מביא עוגה?",
                "01/03/2026, 09:05 - מלכי: <attached: gone-photo.jpg>",
                "01/03/2026, 09:06 - מלכי: אני אביא",
                ""));
        // gone-photo.jpg is deliberately never created.

        RealWhatsAppSourceReader reader = new RealWhatsAppSourceReader(
                new WhatsAppImportProperties(importDir.toString(), "Asia/Jerusalem"), new BridgeTenantId("kamatech"));
        List<ChatMessage> messages = reader.read();

        assertThat(messages).hasSize(3);
        ChatMessage attachmentLineMessage = messages.get(1);
        assertThat(attachmentLineMessage.attachments()).isEmpty();
    }

    @Test
    void genericAttachmentReadErrorStillPropagates() throws IOException {
        Files.writeString(importDir.resolve("family-chat.txt"), String.join("\n",
                "01/03/2026, 09:05 - מלכי: <attached: not-a-file.jpg>",
                ""));
        // A directory, not a file, at that path - readAllBytes fails with IOException, not
        // NoSuchFileException, so this must still fail the read rather than being skipped.
        Files.createDirectory(importDir.resolve("not-a-file.jpg"));

        RealWhatsAppSourceReader reader = new RealWhatsAppSourceReader(
                new WhatsAppImportProperties(importDir.toString(), "Asia/Jerusalem"), new BridgeTenantId("kamatech"));

        assertThatThrownBy(reader::read).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void failsFastWhenImportDirIsNotConfigured() {
        RealWhatsAppSourceReader reader = new RealWhatsAppSourceReader(
                new WhatsAppImportProperties(null, "Asia/Jerusalem"), new BridgeTenantId("kamatech"));

        assertThatThrownBy(reader::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("import-dir");
    }

    @Test
    void failsFastWhenImportDirDoesNotExist() {
        RealWhatsAppSourceReader reader = new RealWhatsAppSourceReader(new WhatsAppImportProperties(
                importDir.resolve("missing").toString(), "Asia/Jerusalem"), new BridgeTenantId("kamatech"));

        assertThatThrownBy(reader::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a directory");
    }
}
