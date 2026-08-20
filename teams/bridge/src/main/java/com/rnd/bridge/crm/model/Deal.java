package com.rnd.bridge.crm.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record Deal(
        String dealId,
        DealStage stage,
        String owner,
        LocalDate closeDate,
        Double dealValue,
        Instant sourceUpdatedAt) {

    public Deal {
        Objects.requireNonNull(dealId, "dealId must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
    }
}