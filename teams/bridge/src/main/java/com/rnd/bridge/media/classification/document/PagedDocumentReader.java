package com.rnd.bridge.media.classification.document;

import java.io.IOException;
import java.util.Set;

/**
 * Opens one family of file formats as a {@link PagedDocument}. Every implementation registered as
 * a bean is picked up by {@link DocumentScanner}, and the union of their {@link #extensions()} is
 * exactly the set of formats classification looks at - anything else is never rendered or sent
 * to a model. Supporting a new format means adding one implementation, nothing else.
 */
public interface PagedDocumentReader {

    /** Lower-case file extensions without the dot, e.g. "pdf". */
    Set<String> extensions();

    PagedDocument open(byte[] content) throws IOException;
}
