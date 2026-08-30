package com.rnd.chain.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskTest {

    @Test
    void builder_throwsIllegalArgumentException_whenStatusIsNull() {
        assertThatThrownBy(() -> Task.builder()
                .id(UUID.randomUUID())
                .ownerId("owner")
                .status(null)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }
}
