package com.rnd.bridge.media.classification.document;

import java.awt.image.BufferedImage;
import org.springframework.stereotype.Component;

/**
 * Decides whether a rendered page is blank by counting its dark pixels: blank means under 0.01%
 * of the page is darker than near-white. Every pixel is checked - a sampling grid can step right
 * over thin text strokes - which still costs only a few milliseconds per page.
 * <p>
 * The threshold is deliberately tiny, because the two possible mistakes are not equal: calling a
 * page with content blank gets a real document EXCLUDED, while calling a noisy blank scan
 * non-blank only costs one model call. A short letter - three lines of text on a whole page -
 * covers about 0.2% of it, twenty times above this threshold.
 */
@Component
public class BlankPageDetector {

    private static final int DARK_LUMINANCE_THRESHOLD = 200;
    private static final double MAX_DARK_PIXEL_RATIO = 0.0001;

    public boolean isBlank(BufferedImage page) {
        long pixels = (long) page.getWidth() * page.getHeight();
        if (pixels == 0) {
            return true;
        }
        long maxDarkPixels = (long) Math.floor(pixels * MAX_DARK_PIXEL_RATIO);
        long dark = 0;
        for (int y = 0; y < page.getHeight(); y++) {
            for (int x = 0; x < page.getWidth(); x++) {
                int rgb = page.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (0.299 * r + 0.587 * g + 0.114 * b < DARK_LUMINANCE_THRESHOLD && ++dark > maxDarkPixels) {
                    return false;
                }
            }
        }
        return true;
    }
}
