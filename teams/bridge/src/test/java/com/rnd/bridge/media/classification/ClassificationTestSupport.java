package com.rnd.bridge.media.classification;

import com.rnd.bridge.raw.model.DocumentCategory;
import com.rnd.bridge.raw.model.MeetingCategory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import javax.imageio.ImageIO;

/** Shared fixtures for the media.classification tests. */
final class ClassificationTestSupport {

    private ClassificationTestSupport() {
    }

    static MediaClassificationProperties properties(boolean enabled, String apiKey) {
        return new MediaClassificationProperties(
                enabled, 0.6, Duration.ofSeconds(60), 4000,
                Set.of(MeetingCategory.TEST, MeetingCategory.SOCIAL),
                Set.of(DocumentCategory.BLANK, DocumentCategory.ADVERTISEMENT),
                new MediaClassificationProperties.Model(
                        "http://localhost", "test-model", apiKey, "v1", Duration.ofSeconds(5)));
    }

    static byte[] blankPagePng() {
        return png(page(false));
    }

    static byte[] textPagePng() {
        return png(page(true));
    }

    private static BufferedImage page(boolean withText) {
        BufferedImage image = new BufferedImage(850, 1100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        if (withText) {
            g.setColor(Color.BLACK);
            for (int line = 0; line < 30; line++) {
                g.fillRect(80, 100 + line * 30, 600, 8);
            }
        }
        g.dispose();
        return image;
    }

    private static byte[] png(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
