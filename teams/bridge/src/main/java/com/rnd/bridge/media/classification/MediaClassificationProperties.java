package com.rnd.bridge.media.classification;

import com.rnd.bridge.raw.model.DocumentCategory;
import com.rnd.bridge.raw.model.MeetingCategory;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * bridge.media.classification.* - see application.yml for what each setting does.
 * <p>
 * enabled=false (the default) turns the whole stage off: every item goes out INCLUDED with no
 * classification, exactly as before this stage existed. enabled=true with no model API key runs
 * the deterministic rules only.
 * <p>
 * Which model is called, and with which key, is not set here: that's bridge.ai.* (the shared AI
 * infrastructure), under the usages media-meeting and media-document.
 *
 * @param promptVersion bump on every prompt change in ModelMediaClassifier, so results stay comparable
 */
@ConfigurationProperties(prefix = "bridge.media.classification")
public record MediaClassificationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("0.6") double minConfidenceToExclude,
        @DefaultValue("60s") Duration minMeetingDuration,
        @DefaultValue("4000") int transcriptExcerptChars,
        @DefaultValue({"TEST", "SOCIAL"}) Set<MeetingCategory> excludedMeetingCategories,
        @DefaultValue({"BLANK", "ADVERTISEMENT"}) Set<DocumentCategory> excludedDocumentCategories,
        @DefaultValue Documents documents,
        @DefaultValue("v4") String promptVersion) {

    public MediaClassificationProperties {
        if (!(minConfidenceToExclude >= 0.0 && minConfidenceToExclude <= 1.0)) {
            throw new IllegalArgumentException(
                    "min-confidence-to-exclude must be in [0, 1], was " + minConfidenceToExclude);
        }
    }

    /**
     * How a multi-page scanned document is examined - see media.classification.document.DocumentScanner.
     *
     * @param maxPagesToScan the most pages rendered and checked for content, however long the document
     * @param pagesToSend    how many non-blank pages are sent to the model (the scan stops once found)
     * @param maxFileSizeBytes a larger file is never opened - bounds what a parser has to chew on
     * @param maxPagePixels  a page whose rendered/decoded size (width x height) would exceed this is
     *                       never rendered - a huge page or a decompression bomb would otherwise
     *                       allocate gigabytes and take the whole service down
     */
    public record Documents(
            @DefaultValue("5") int maxPagesToScan,
            @DefaultValue("2") int pagesToSend,
            @DefaultValue("26214400") long maxFileSizeBytes,
            @DefaultValue("25000000") long maxPagePixels) {

        public Documents {
            if (maxFileSizeBytes < 1 || maxPagePixels < 1) {
                throw new IllegalArgumentException("documents.max-file-size-bytes and documents.max-page-pixels must be positive");
            }
            if (pagesToSend < 1) {
                throw new IllegalArgumentException("documents.pages-to-send must be at least 1, was " + pagesToSend);
            }
            if (maxPagesToScan < pagesToSend) {
                throw new IllegalArgumentException("documents.max-pages-to-scan (" + maxPagesToScan
                        + ") must be at least documents.pages-to-send (" + pagesToSend + ")");
            }
        }
    }
}
