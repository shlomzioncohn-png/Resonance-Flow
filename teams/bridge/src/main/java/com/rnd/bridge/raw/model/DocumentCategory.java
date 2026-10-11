package com.rnd.bridge.raw.model;

/**
 * {@link ItemCategory} values for {@link RawSourceType#SCANNED_DOC}. The first 16 are the
 * RVL-CDIP dataset's classes, in its own label order, so a classifier's output can be scored
 * directly against that dataset's ground truth. {@link #BLANK} and {@link #OTHER} are ours -
 * RVL-CDIP has neither.
 */
public enum DocumentCategory implements ItemCategory {
    LETTER,
    FORM,
    EMAIL,
    HANDWRITTEN,
    ADVERTISEMENT,
    SCIENTIFIC_REPORT,
    SCIENTIFIC_PUBLICATION,
    SPECIFICATION,
    FILE_FOLDER,
    NEWS_ARTICLE,
    BUDGET,
    INVOICE,
    PRESENTATION,
    QUESTIONNAIRE,
    RESUME,
    MEMO,
    /** An empty page - typically decided by RULE (pixel check), not the model. */
    BLANK,
    OTHER;

    @Override
    public RawSourceType sourceType() {
        return RawSourceType.SCANNED_DOC;
    }
}
