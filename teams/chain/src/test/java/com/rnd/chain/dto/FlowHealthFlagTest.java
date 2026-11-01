package com.rnd.chain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.chain.controller.FlowHealthFlagResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowHealthFlagTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant RUN_AT = Instant.parse("2026-10-28T00:00:00Z");

    private static FlowHealthFlag flag(double score, List<String> entityIds) {
        JsonNode evidence = MAPPER.createObjectNode().put("blockedCount", 3);
        return new FlowHealthFlag("CHRONIC_BLOCKER:4230f2a0b8", FlowHealthPattern.CHRONIC_BLOCKER,
                FlowHealthEntityType.TASK, entityIds, FlowHealthSeverity.LOW, score, 1, "why", evidence, RUN_AT);
    }

    @Test
    void scoreOutsideZeroToOne_isRejected() {
        assertThatThrownBy(() -> flag(1.01, List.of("t1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flag(-0.01, List.of("t1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scoreBoundaries_areAccepted() {
        assertThat(flag(0, List.of("t1")).score()).isZero();
        assertThat(flag(1, List.of("t1")).score()).isEqualTo(1);
    }

    @Test
    void entityIds_areDefensivelyCopiedAndKeepTheirOrder() {
        List<String> ids = new ArrayList<>(List.of("b", "a", "c"));
        FlowHealthFlag flag = flag(0.5, ids);
        ids.clear();

        assertThat(flag.entityIds()).containsExactly("b", "a", "c");
    }

    @Test
    void requiredFields_areNotNullable() {
        assertThatThrownBy(() -> new FlowHealthFlag(null, FlowHealthPattern.SLOW_CHAIN, FlowHealthEntityType.CHAIN,
                List.of(), FlowHealthSeverity.HIGH, 0.5, 1, "e", null, RUN_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void severityAndEntityType_useLowerCaseOnTheWire() throws Exception {
        assertThat(MAPPER.writeValueAsString(FlowHealthSeverity.MEDIUM)).isEqualTo("\"medium\"");
        assertThat(MAPPER.writeValueAsString(FlowHealthEntityType.EDGE_PAIR)).isEqualTo("\"edge_pair\"");
        assertThat(MAPPER.readValue("\"high\"", FlowHealthSeverity.class)).isEqualTo(FlowHealthSeverity.HIGH);
        assertThat(FlowHealthEntityType.fromValue("edge_pair")).isEqualTo(FlowHealthEntityType.EDGE_PAIR);
    }

    @Test
    void response_copiesAllFieldsFromTheFlag() {
        FlowHealthFlag flag = flag(0.24, List.of("t1"));

        FlowHealthFlagResponse response = FlowHealthFlagResponse.from(flag);

        assertThat(response.flagId()).isEqualTo(flag.flagId());
        assertThat(response.pattern()).isEqualTo(flag.pattern());
        assertThat(response.entityType()).isEqualTo(flag.entityType());
        assertThat(response.entityIds()).isEqualTo(flag.entityIds());
        assertThat(response.severity()).isEqualTo(flag.severity());
        assertThat(response.score()).isEqualTo(flag.score());
        assertThat(response.rank()).isEqualTo(flag.rank());
        assertThat(response.explanation()).isEqualTo(flag.explanation());
        assertThat(response.evidence()).isEqualTo(flag.evidence());
        assertThat(response.runAt()).isEqualTo(flag.runAt());
    }
}
