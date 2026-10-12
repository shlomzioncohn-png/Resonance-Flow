package com.rnd.bridge.media.classification.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/**
 * Raster image formats the JDK's ImageIO decodes without extra libraries. TIFF can hold several
 * pages (a multi-page scan); every other format here is a single page. WEBP is deliberately
 * absent - the JDK has no decoder for it.
 * <p>
 * The extension only decides whether a file is in scope; the decoder itself is chosen from the
 * file's actual bytes, so a mislabelled file still decodes as what it really is.
 */
@Component
public class ImageIoDocumentReader implements PagedDocumentReader {

    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif");

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public PagedDocument open(byte[] content) throws IOException {
        ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(content));
        if (stream == null) {
            throw new IOException("No ImageIO input stream available");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) {
            stream.close();
            throw new IOException("Not a decodable image");
        }
        ImageReader reader = readers.next();
        reader.setInput(stream);
        return new ImageIoDocument(reader, stream);
    }

    private record ImageIoDocument(ImageReader reader, ImageInputStream stream) implements PagedDocument {

        @Override
        public int pageCount() throws IOException {
            return reader.getNumImages(true);
        }

        @Override
        public BufferedImage renderPage(int pageIndex) throws IOException {
            return reader.read(pageIndex);
        }

        @Override
        public void close() throws IOException {
            reader.dispose();
            stream.close();
        }
    }
}
