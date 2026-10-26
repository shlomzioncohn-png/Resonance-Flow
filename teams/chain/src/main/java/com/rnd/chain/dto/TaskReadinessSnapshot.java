package com.rnd.chain.dto;

import java.util.UUID;

public record TaskReadinessSnapshot(
        UUID taskId,
        UUID tenantId,
        int blockingCount,
        int openConstraintCount
) {
    public boolean isReady() {
        return blockingCount == 0 && openConstraintCount == 0;
    }
}