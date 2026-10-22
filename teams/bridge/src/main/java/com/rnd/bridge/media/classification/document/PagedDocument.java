package com.rnd.bridge.media.classification.document;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * An opened document whose pages can be rendered one at a time, on demand - so a scan that stops
 * early never pays to render (or hold in memory) the pages it didn't need. Pages are indexed from
 * 0; nothing here writes to disk.
 */
public interface PagedDocument extends AutoCloseable {

    int pageCount() throws IOException;

    /**
     * Width x height the page would have once rendered, read from the page's own header/box
     * without decoding or rendering it - checked before {@link #renderPage}.
     */
    long pagePixels(int pageIndex) throws IOException;

    BufferedImage renderPage(int pageIndex) throws IOException;

    @Override
    void close() throws IOException;
}
