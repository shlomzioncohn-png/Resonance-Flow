package com.rnd.bridge.media.classification.document;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

/**
 * What {@link DocumentScanner} found in a document - computed once and shared by the rules (is it
 * blank?) and the model (which pages to look at), so no page is rendered twice.
 *
 * @param pageCount    total pages in the document; 0 when it couldn't be opened
 * @param pagesScanned how many pages were actually rendered and checked
 * @param contentPages the non-blank pages kept for the model, in page order; empty unless
 *                     outcome is {@link Outcome#CONTENT_FOUND}
 */
public record DocumentScan(Outcome outcome, int pageCount, int pagesScanned, List<ContentPage> contentPages) {

    public enum Outcome {
        /** The format isn't in any reader's extension list - never opened. */
        UNSUPPORTED_FORMAT,
        /** In scope, but couldn't be opened or has no pages (corrupt, password-protected). */
        UNREADABLE,
        /** Every page was checked and every page is blank - the document really is blank. */
        ALL_BLANK,
        /** The scan limit was reached with only blank pages, and more pages remain unchecked. */
        NO_CONTENT_WITHIN_LIMIT,
        /** At least one non-blank page was found. */
        CONTENT_FOUND
    }

    /** A non-blank page, numbered from 1 as a reader would count it. */
    public record ContentPage(int pageNumber, BufferedImage image) {

        public ContentPage {
            Objects.requireNonNull(image, "image");
        }
    }

    public DocumentScan {
        Objects.requireNonNull(outcome, "outcome");
        contentPages = List.copyOf(contentPages);
        if ((outcome == Outcome.CONTENT_FOUND) == contentPages.isEmpty()) {
            throw new IllegalArgumentException("contentPages must be non-empty exactly when outcome is CONTENT_FOUND");
        }
    }

    public static DocumentScan unsupportedFormat() {
        return new DocumentScan(Outcome.UNSUPPORTED_FORMAT, 0, 0, List.of());
    }

    public static DocumentScan unreadable() {
        return new DocumentScan(Outcome.UNREADABLE, 0, 0, List.of());
    }
}
