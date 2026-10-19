package com.rnd.bridge.media.classification.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rnd.bridge.media.classification.ClassificationTestSupport;
import com.rnd.bridge.media.classification.MediaClassificationProperties;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the real readers (ImageIO, PDFBox) and the real blank-page check over generated files. */
class DocumentScannerTest {

    private final DocumentScanner scanner = scanner(5, 2);

    @Test
    void stopsAtTheFirstTwoContentPagesSkippingABlankCover() {
        DocumentScan scan = scanner.scan(ClassificationTestSupport.pdf(false, true, true, true, true, true), "doc.pdf");

        assertThat(scan.outcome()).isEqualTo(DocumentScan.Outcome.CONTENT_FOUND);
        assertThat(scan.pageCount()).isEqualTo(6);
        assertThat(scan.pagesScanned()).isEqualTo(3);
        assertThat(scan.contentPages()).extracting(DocumentScan.ContentPage::pageNumber).containsExactly(2, 3);
    }

    @Test
    void singleBlankPageIsAllBlank() {
        DocumentScan scan = scanner.scan(ClassificationTestSupport.blankPagePng(), "scan.png");

        assertThat(scan.outcome()).isEqualTo(DocumentScan.Outcome.ALL_BLANK);
        assertThat(scan.pagesScanned()).isEqualTo(1);
    }

    @Test
    void shortDocumentWithEveryPageBlankIsAllBlank() {
        DocumentScan scan = scanner.scan(ClassificationTestSupport.pdf(false, false, false), "doc.pdf");

        assertThat(scan.outcome()).isEqualTo(DocumentScan.Outcome.ALL_BLANK);
        assertThat(scan.pagesScanned()).isEqualTo(3);
    }

    @Test
    void blankPagesUpToTheLimitWithMoreRemainingIsNotAllBlank() {
        DocumentScan scan = scanner.scan(
                ClassificationTestSupport.pdf(false, false, false, false, false, true), "doc.pdf");

        assertThat(scan.outcome()).isEqualTo(DocumentScan.Outcome.NO_CONTENT_WITHIN_LIMIT);
        assertThat(scan.pagesScanned()).isEqualTo(5);
        assertThat(scan.pageCount()).isEqualTo(6);
    }

    @Test
    void singleContentPageDocumentSendsThatOnePage() {
        DocumentScan scan = scanner.scan(ClassificationTestSupport.textPagePng(), "scan.PNG");

        assertThat(scan.outcome()).isEqualTo(DocumentScan.Outcome.CONTENT_FOUND);
        assertThat(scan.contentPages()).extracting(DocumentScan.ContentPage::pageNumber).containsExactly(1);
    }

    @Test
    void multiPageTiffIsScannedPageByPage() {
        DocumentScan scan = scanner.scan(ClassificationTestSupport.tiffWithBlankCoverPage(), "scan.tif");

        assertThat(scan.outcome()).isEqualTo(DocumentScan.Outcome.CONTENT_FOUND);
        assertThat(scan.contentPages()).extracting(DocumentScan.ContentPage::pageNumber).containsExactly(2);
    }

    @Test
    void rendersOnlyThePagesItNeeds() {
        CountingReader reader = new CountingReader(100);
        DocumentScanner countingScanner = new DocumentScanner(List.of(reader), new BlankPageDetector(), properties(5, 2));

        DocumentScan scan = countingScanner.scan(new byte[] {1}, "doc.fake");

        assertThat(scan.outcome()).isEqualTo(DocumentScan.Outcome.CONTENT_FOUND);
        assertThat(reader.renderedPages).containsExactly(0, 1);
        assertThat(reader.closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.docx", "sheet.xlsx", "photo.webp", "image.bmp", "anim.gif", "no-extension", "trailing-dot."})
    void formatOutsideTheReadersIsUnsupportedAndNeverOpened(String filename) {
        assertThat(scanner.scan(new byte[] {1, 2, 3}, filename).outcome())
                .isEqualTo(DocumentScan.Outcome.UNSUPPORTED_FORMAT);
    }

    @Test
    void corruptFileInASupportedFormatIsUnreadable() {
        assertThat(scanner.scan("%PDF-1.7 truncated".getBytes(), "doc.pdf").outcome())
                .isEqualTo(DocumentScan.Outcome.UNREADABLE);
        assertThat(scanner.scan("not an image".getBytes(), "scan.png").outcome())
                .isEqualTo(DocumentScan.Outcome.UNREADABLE);
    }

    @Test
    void twoReadersClaimingTheSameExtensionFailAtStartup() {
        assertThatThrownBy(() -> new DocumentScanner(
                List.of(new ImageIoDocumentReader(), new CountingReader(1) {
                    @Override
                    public Set<String> extensions() {
                        return Set.of("PNG");
                    }
                }),
                new BlankPageDetector(), properties(5, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("png");
    }

    @Test
    void pagesToSendAboveTheScanLimitIsRejected() {
        assertThatThrownBy(() -> new MediaClassificationProperties.Documents(1, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DocumentScanner scanner(int maxPagesToScan, int pagesToSend) {
        return new DocumentScanner(List.of(new ImageIoDocumentReader(), new PdfDocumentReader()),
                new BlankPageDetector(), properties(maxPagesToScan, pagesToSend));
    }

    private static MediaClassificationProperties properties(int maxPagesToScan, int pagesToSend) {
        return ClassificationTestSupport.properties(
                new MediaClassificationProperties.Documents(maxPagesToScan, pagesToSend));
    }

    /** A fake format whose every page has content, recording which pages were rendered. */
    private static class CountingReader implements PagedDocumentReader {
        final List<Integer> renderedPages = new ArrayList<>();
        final int pageCount;
        boolean closed;

        CountingReader(int pageCount) {
            this.pageCount = pageCount;
        }

        @Override
        public Set<String> extensions() {
            return Set.of("fake");
        }

        @Override
        public PagedDocument open(byte[] content) {
            return new PagedDocument() {
                @Override
                public int pageCount() {
                    return pageCount;
                }

                @Override
                public BufferedImage renderPage(int pageIndex) {
                    renderedPages.add(pageIndex);
                    return ClassificationTestSupport.pageImage(true);
                }

                @Override
                public void close() throws IOException {
                    closed = true;
                }
            };
        }
    }
}
