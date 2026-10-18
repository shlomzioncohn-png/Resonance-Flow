package com.rnd.bridge.media.classification;

/** What a scanned document is classified from - its raw bytes and original filename. */
public record DocumentClassificationInput(byte[] content, String filename) {
}
