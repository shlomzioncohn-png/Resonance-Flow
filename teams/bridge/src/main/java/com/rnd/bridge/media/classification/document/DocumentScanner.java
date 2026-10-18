package com.rnd.bridge.media.classification.document;

import com.rnd.bridge.media.classification.MediaClassificationProperties;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Walks a document's pages, rendering one page at a time and keeping only the non-blank ones,
 * until it has documents.pages-to-send content pages or has checked documents.max-pages-to-scan
 * pages - so a 100-page PDF whose first page has content costs one render, not a hundred.
 * <p>
 * BLANK is only ever a safe conclusion when every page was checked: a scan that hit the limit
 * with nothing but blank pages reports {@link DocumentScan.Outcome#NO_CONTENT_WITHIN_LIMIT}, not
 * {@link DocumentScan.Outcome#ALL_BLANK}, since unchecked pages may well have content.
 * <p>
 * Never throws for a bad file: an unsupported format or one that fails to open/render is reported
 * as an outcome, and the item simply stays unclassified.
 */
@Component
public class DocumentScanner {

    private static final Logger log = LoggerFactory.getLogger(DocumentScanner.class);

    private final Map<String, PagedDocumentReader> readersByExtension;
    private final BlankPageDetector blankPageDetector;
    private final int maxPagesToScan;
    private final int pagesToSend;

    public DocumentScanner(List<PagedDocumentReader> readers, BlankPageDetector blankPageDetector,
                           MediaClassificationProperties properties) {
        this.readersByExtension = indexByExtension(readers);
        this.blankPageDetector = blankPageDetector;
        this.maxPagesToScan = properties.documents().maxPagesToScan();
        this.pagesToSend = properties.documents().pagesToSend();
    }

    public DocumentScan scan(byte[] content, String filename) {
        Optional<PagedDocumentReader> reader = extensionOf(filename).map(readersByExtension::get);
        if (reader.isEmpty()) {
            log.info("{}: format not supported for classification", filename);
            return DocumentScan.unsupportedFormat();
        }
        try (PagedDocument document = reader.get().open(content)) {
            return scanPages(document, filename);
        } catch (IOException | RuntimeException e) {
            log.warn("{}: could not be opened or rendered for classification - {}", filename, e.toString());
            return DocumentScan.unreadable();
        }
    }

    private DocumentScan scanPages(PagedDocument document, String filename) throws IOException {
        int pageCount = document.pageCount();
        if (pageCount <= 0) {
            log.warn("{}: document has no pages", filename);
            return DocumentScan.unreadable();
        }
        int limit = Math.min(pageCount, maxPagesToScan);
        List<DocumentScan.ContentPage> contentPages = new ArrayList<>(pagesToSend);
        int scanned = 0;
        while (scanned < limit && contentPages.size() < pagesToSend) {
            BufferedImage page = document.renderPage(scanned);
            scanned++;
            if (!blankPageDetector.isBlank(page)) {
                contentPages.add(new DocumentScan.ContentPage(scanned, page));
            }
        }

        DocumentScan.Outcome outcome = !contentPages.isEmpty() ? DocumentScan.Outcome.CONTENT_FOUND
                : scanned == pageCount ? DocumentScan.Outcome.ALL_BLANK
                : DocumentScan.Outcome.NO_CONTENT_WITHIN_LIMIT;
        log.info("{}: {} - {} of {} page(s) scanned, content on page(s) {}", filename, outcome, scanned,
                pageCount, contentPages.stream().map(DocumentScan.ContentPage::pageNumber).toList());
        return new DocumentScan(outcome, pageCount, scanned, contentPages);
    }

    private static Map<String, PagedDocumentReader> indexByExtension(List<PagedDocumentReader> readers) {
        Map<String, PagedDocumentReader> byExtension = new HashMap<>();
        for (PagedDocumentReader reader : readers) {
            for (String rawExtension : reader.extensions()) {
                String extension = rawExtension.toLowerCase(Locale.ROOT);
                PagedDocumentReader previous = byExtension.put(extension, reader);
                if (previous != null) {
                    throw new IllegalStateException("Extension '" + extension + "' is claimed by both "
                            + previous.getClass().getSimpleName() + " and " + reader.getClass().getSimpleName());
                }
            }
        }
        return Map.copyOf(byExtension);
    }

    private static Optional<String> extensionOf(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1
                ? Optional.empty()
                : Optional.of(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
