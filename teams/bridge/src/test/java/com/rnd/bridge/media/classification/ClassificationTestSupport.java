package com.rnd.bridge.media.classification;

import com.rnd.bridge.media.classification.document.DocumentScan;
import com.rnd.bridge.raw.model.DocumentCategory;
import com.rnd.bridge.raw.model.MeetingCategory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

/** Shared fixtures for the media.classification tests (and its document sub-package). */
public final class ClassificationTestSupport {

    private ClassificationTestSupport() {
    }

    public static MediaClassificationProperties properties(boolean enabled, String apiKey) {
        return properties(enabled, apiKey, "http://localhost", new MediaClassificationProperties.Documents(5, 2));
    }

    public static MediaClassificationProperties properties(MediaClassificationProperties.Documents documents) {
        return properties(true, "", "http://localhost", documents);
    }

    public static MediaClassificationProperties properties(boolean enabled, String apiKey, String baseUrl,
                                                    MediaClassificationProperties.Documents documents) {
        return new MediaClassificationProperties(
                enabled, 0.6, Duration.ofSeconds(60), 4000,
                Set.of(MeetingCategory.TEST, MeetingCategory.SOCIAL),
                Set.of(DocumentCategory.BLANK, DocumentCategory.ADVERTISEMENT),
                documents,
                new MediaClassificationProperties.Model(baseUrl, "test-model", apiKey, "v1", Duration.ofSeconds(5)));
    }

    /** A scan that found content on the given pages of a document with pageCount pages. */
    public static DocumentScan contentScan(int pageCount, int... contentPageNumbers) {
        List<DocumentScan.ContentPage> pages = Arrays.stream(contentPageNumbers)
                .mapToObj(number -> new DocumentScan.ContentPage(number, pageImage(true)))
                .toList();
        return new DocumentScan(DocumentScan.Outcome.CONTENT_FOUND, pageCount, contentPageNumbers[contentPageNumbers.length - 1], pages);
    }

    /** A PDF with one page per argument: true = a page with (line-shaped) content, false = blank. */
    public static byte[] pdf(boolean... pagesWithContent) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (boolean withContent : pagesWithContent) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (withContent) {
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        for (int line = 0; line < 30; line++) {
                            content.addRect(60, 700 - line * 20, 450, 6);
                        }
                        content.fill();
                    }
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] blankPagePng() {
        return png(pageImage(false));
    }

    public static byte[] textPagePng() {
        return png(pageImage(true));
    }

    /** A multi-page TIFF whose first page is blank - a scan with an empty cover page. */
    public static byte[] tiffWithBlankCoverPage() {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(pageImage(false), null, null), null);
            writer.writeToSequence(new IIOImage(pageImage(true), null, null), null);
            writer.endWriteSequence();
            stream.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            writer.dispose();
        }
    }

    public static BufferedImage pageImage(boolean withText) {
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
