package com.rnd.bridge.media.classification.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class BlankPageDetectorTest {

    // A letter-size page rendered at 110 DPI, as PdfDocumentReader renders it.
    private static final int WIDTH = 909;
    private static final int HEIGHT = 1286;

    private final BlankPageDetector detector = new BlankPageDetector();

    @Test
    void emptyPageIsBlank() {
        assertThat(detector.isBlank(page())).isTrue();
    }

    @Test
    void shortLetterIsNotBlank() {
        // Regression: a three-line termination letter covers only ~0.2% of the page in ink and
        // was wrongly called BLANK (and EXCLUDED) under the old 0.2% threshold.
        BufferedImage page = page();
        Graphics2D g = page.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.drawString("Termination letter", 380, 120);
        g.drawString("Please notify the employee of the termination", 250, 160);
        g.drawString("due to a staff surplus", 360, 190);
        g.dispose();

        assertThat(detector.isBlank(page)).isFalse();
    }

    @Test
    void singleShortWordIsNotBlank() {
        BufferedImage page = page();
        Graphics2D g = page.createGraphics();
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.drawString("Approved", 400, 600);
        g.dispose();

        assertThat(detector.isBlank(page)).isFalse();
    }

    @Test
    void afewScannerSpecksStayBlank() {
        BufferedImage page = page();
        for (int i = 0; i < 40; i++) {
            page.setRGB(20 + i * 20, 30 + i * 25, Color.BLACK.getRGB());
        }

        assertThat(detector.isBlank(page)).isTrue();
    }

    private static BufferedImage page() {
        BufferedImage page = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.dispose();
        return page;
    }
}
