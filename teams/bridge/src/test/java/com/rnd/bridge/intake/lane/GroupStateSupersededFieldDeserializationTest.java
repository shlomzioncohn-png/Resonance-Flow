package com.rnd.bridge.intake.lane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.raw.model.RawSourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Pins docs/plans/slack-superseded-fix.md's safety claim: deleting GroupState.superseded is
 * safe for rows already persisted in Postgres (control_plane_state.value) with
 * "superseded":true still in their JSON, because the real, Spring-auto-configured ObjectMapper
 * GroupStateStore is wired with (see its constructor) leaves
 * DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES at Spring Boot's own default (disabled) -
 * unlike vanilla Jackson's default (enabled). @JsonTest is used, not a hand-built ObjectMapper,
 * for exactly the reason HubSpotDealRecordObjectMapperNamingStrategyTest already documents: a
 * locally-built mapper would prove nothing about how the shared bean actually behaves, and - as
 * a plain new ObjectMapper() first used in this test proved directly - would behave differently
 * from it here, since vanilla Jackson's default would make this old JSON fail to deserialize at
 * all. A tripwire: if anything ever re-enables that feature globally, this test fails loudly
 * instead of silently breaking every old row in Postgres.
 */
@JsonTest
class GroupStateSupersededFieldDeserializationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private static final String OLD_JSON_WITH_SUPERSEDED_FIELD = """
            {
              "groupId": "g1",
              "sourceType": "SLACK",
              "tenantId": "kamatech",
              "chatId": "chat-a",
              "threadId": null,
              "correlationId": "c1",
              "messages": [],
              "published": true,
              "revision": 0,
              "claimedRevision": null,
              "superseded": true
            }
            """;

    @Test
    void oldPersistedJsonWithASupersededFieldStillDeserializesCleanly() throws Exception {
        GroupState parsed = objectMapper.readValue(OLD_JSON_WITH_SUPERSEDED_FIELD, GroupState.class);

        assertEquals("g1", parsed.groupId());
        assertEquals(RawSourceType.SLACK, parsed.sourceType());
        assertEquals("kamatech", parsed.tenantId());
        assertEquals("chat-a", parsed.chatId());
        assertNull(parsed.threadId());
        assertEquals("c1", parsed.correlationId());
        assertTrue(parsed.messages().isEmpty());
        assertTrue(parsed.published());
        assertEquals(0, parsed.revision());
        assertNull(parsed.claimedRevision());
    }
}
