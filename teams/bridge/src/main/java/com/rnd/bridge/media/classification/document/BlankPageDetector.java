package com.rnd.bridge.media.classification.document;

import java.awt.image.BufferedImage;
import org.springframework.stereotype.Component;

/**
 * Decides whether a rendered page is blank by sampling its pixels: blank means under 0.2% of
 * samples are darker than near-white. Local, deterministic and around a millisecond per page.
 * <p>
 * Its mistakes lean the safe way: scanner noise or a grey shadow makes a blank page look
 * non-blank (it then just goes to the model). The one unsafe case - very faint content (light
 * pencil) read as blank - is why the threshold is kept this low.
 */
@Component
public class BlankPageDetector {

    private static final int DARK_LUMINANCE_THRESHOLD = 200;
    private static final double MAX_DARK_PIXEL_RATIO = 0.002;
    // Caps the work on a huge scan: sample on a grid instead of reading every pixel.
    private static final int MAX_PIXEL_SAMPLES = 250_000;

    public boolean isBlank(BufferedImage page) {
        long pixels = (long) page.getWidth() * page.getHeight();
        int step = (int) Math.max(1, Math.ceil(Math.sqrt((double) pixels / MAX_PIXEL_SAMPLES)));
        long sampled = 0;
        long dark = 0;
        for (int y = 0; y < page.getHeight(); y += step) {
            for (int x = 0; x < page.getWidth(); x += step) {
                int rgb = page.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (0.299 * r + 0.587 * g + 0.114 * b < DARK_LUMINANCE_THRESHOLD) {
                    dark++;
                }
                sampled++;
            }
        }
        return sampled > 0 && (double) dark / sampled < MAX_DARK_PIXEL_RATIO;
    }
}
