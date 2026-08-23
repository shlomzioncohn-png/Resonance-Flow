package com.rnd.bridge.crm.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record Deal(
        String dealId,
        DealStage stage,
        String owner,
        LocalDate closeDate,
        BigDecimal dealValue,
        Instant sourceUpdatedAt) {

    public Deal {
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(stage, "stage must not be null");

        if (dealId.isBlank()) {
            throw new IllegalArgumentException("dealId must not be blank");
        }
    }
}