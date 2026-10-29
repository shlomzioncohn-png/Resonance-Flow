package com.rnd.gateway.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The Gateway reads the BFF's TaskDTO JSON into its own TaskDTO and writes it
 * back out - constraints and openConstraintCount must survive both steps.
 */
class TaskDTOConstraintsJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** The shape com.rnd.bff.dto.TaskDTO serializes to. */
    private static final String BFF_JSON = """
            {"id":"task_002","title":"Update customer proposal","openConstraintCount":1,
             "constraints":[
               {"constraintId":"c1","type":"APPROVAL_NEEDED","status":"PENDING",
                "reason":{"note":"Legal sign-off","attempt":2},"createdAt":"2026-10-20T09:15:30"},
               {"constraintId":"c2","type":"DATE_WINDOW","status":"WAIVED","reason":{},
                "createdAt":"2026-10-21T10:00:00"}
             ]}
            """;

    @Test
    void bffJsonRoundTripsWithoutLosingConstraintFields() throws Exception {
        TaskDTO task = mapper.readValue(BFF_JSON, TaskDTO.class);

        assertThat(task.getOpenConstraintCount()).isEqualTo(1);
        assertThat(task.getConstraints()).hasSize(2);
        ConstraintDTO first = task.getConstraints().get(0);
        assertThat(first.getConstraintId()).isEqualTo("c1");
        assertThat(first.getType()).isEqualTo("APPROVAL_NEEDED");
        assertThat(first.getStatus()).isEqualTo("PENDING");
        assertThat(first.getReason()).containsEntry("note", "Legal sign-off").containsEntry("attempt", 2);
        assertThat(first.getCreatedAt()).isEqualTo("2026-10-20T09:15:30");

        JsonNode expected = mapper.readTree(BFF_JSON);
        JsonNode actual = mapper.readTree(mapper.writeValueAsString(task));
        assertThat(actual.get("constraints")).isEqualTo(expected.get("constraints"));
        assertThat(actual.get("openConstraintCount")).isEqualTo(expected.get("openConstraintCount"));
    }

    @Test
    void zeroOpenConstraintsIsKeptNotDropped() throws Exception {
        TaskDTO task = mapper.readValue("{\"id\":\"t\",\"openConstraintCount\":0,\"constraints\":[]}", TaskDTO.class);

        JsonNode out = mapper.readTree(mapper.writeValueAsString(task));
        assertThat(out.get("openConstraintCount").asInt()).isZero();
        assertThat(out.get("constraints").isArray()).isTrue();
        assertThat(out.get("constraints")).isEmpty();
    }
}
