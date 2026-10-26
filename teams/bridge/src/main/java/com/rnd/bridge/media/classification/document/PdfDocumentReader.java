package com.rnd.bridge.media.classification.document;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * PDFs, rendered page by page with Apache PDFBox (pure Java, no external tools). A password-
 * protected or corrupt PDF fails to open with an IOException, which {@link DocumentScanner}
 * treats as unreadable rather than as an error.
 * <p>
 * Parsing is held to main memory with a cap ({@link #MAX_STREAM_CACHE_BYTES}): a PDF whose
 * streams would need more fails to open (IOException) instead of exhausting the heap.
 */
@Component
public class PdfDocumentReader implements PagedDocumentReader {

    // Enough to judge a page's layout and read its headings; higher only costs time and memory.
    private static final float RENDER_DPI = 110f;
    private static final float POINTS_PER_INCH = 72f;
    static final long MAX_STREAM_CACHE_BYTES = 100L * 1024 * 1024;

    @Override
    public Set<String> extensions() {
        return Set.of("pdf");
    }

    @Override
    public PagedDocument open(byte[] content) throws IOException {
        return new PdfDocument(Loader.loadPDF(content, "", null, null,
                MemoryUsageSetting.setupMainMemoryOnly(MAX_STREAM_CACHE_BYTES).streamCache));
    }

    private record PdfDocument(PDDocument document) implements PagedDocument {

        @Override
        public int pageCount() {
            return document.getNumberOfPages();
        }

        /** From the crop box - what PDFRenderer actually draws - at RENDER_DPI. */
        @Override
        public long pagePixels(int pageIndex) {
            PDRectangle box = document.getPage(pageIndex).getCropBox();
            double scale = RENDER_DPI / POINTS_PER_INCH;
            return (long) Math.ceil(box.getWidth() * scale) * (long) Math.ceil(box.getHeight() * scale);
        }

        @Override
        public BufferedImage renderPage(int pageIndex) throws IOException {
            return new PDFRenderer(document).renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.RGB);
        }

        @Override
        public void close() throws IOException {
            document.close();
        }
    }
}
