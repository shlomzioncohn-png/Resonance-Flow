package com.rnd.bridge.crm.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record Deal(
        String dealId,
        String sourceRefPrefix,
        String title,
        String description,
        DealStage stage,
        String ownerEmail,
        String ownerName,
        LocalDate closeDate,
        BigDecimal dealValue,
        String currency,
        String crmAccountSlug,
        String sourceUrl,
        Instant createdAt,
        Instant stageChangedAt) {

    public Deal {
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(stageChangedAt, "stageChangedAt must not be null");

        if (dealId.isBlank()) {
            throw new IllegalArgumentException("dealId must not be blank");
        }
        // JSON never sets this explicitly - "crm:" is its default prefix (HubSpot/Salesforce set their own).
        if (sourceRefPrefix == null) {
            sourceRefPrefix = "crm:";
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder with sensible defaults, so adding a new {@link Deal}
     * component doesn't break every existing construction site.
     */
    public static final class Builder {
        private String dealId = "D-00000";
        private String sourceRefPrefix = "crm:";
        private String title = "Untitled deal";
        private String description;
        private DealStage stage = DealStage.PROSPECT;
        private String ownerEmail;
        private String ownerName;
        private LocalDate closeDate;
        private BigDecimal dealValue;
        private String currency;
        private String crmAccountSlug;
        private String sourceUrl;
        private Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        private Instant stageChangedAt = Instant.parse("2026-01-01T00:00:00Z");

        private Builder() {
        }

        public Builder dealId(String dealId) {
            this.dealId = dealId;
            return this;
        }

        public Builder sourceRefPrefix(String sourceRefPrefix) {
            this.sourceRefPrefix = sourceRefPrefix;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder stage(DealStage stage) {
            this.stage = stage;
            return this;
        }

        public Builder ownerEmail(String ownerEmail) {
            this.ownerEmail = ownerEmail;
            return this;
        }

        public Builder ownerName(String ownerName) {
            this.ownerName = ownerName;
            return this;
        }

        public Builder closeDate(LocalDate closeDate) {
            this.closeDate = closeDate;
            return this;
        }

        public Builder dealValue(BigDecimal dealValue) {
            this.dealValue = dealValue;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder crmAccountSlug(String crmAccountSlug) {
            this.crmAccountSlug = crmAccountSlug;
            return this;
        }

        public Builder sourceUrl(String sourceUrl) {
            this.sourceUrl = sourceUrl;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder stageChangedAt(Instant stageChangedAt) {
            this.stageChangedAt = stageChangedAt;
            return this;
        }

        public Deal build() {
            return new Deal(dealId, sourceRefPrefix, title, description, stage, ownerEmail, ownerName,
                    closeDate, dealValue, currency, crmAccountSlug, sourceUrl, createdAt, stageChangedAt);
        }
    }
}
