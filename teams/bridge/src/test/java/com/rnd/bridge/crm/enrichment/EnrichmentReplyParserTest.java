package com.rnd.bridge.crm.enrichment;

import org.junit.jupiter.api.Test;

import static com.rnd.bridge.crm.enrichment.EnrichmentReplyParser.RejectionReason;
import static com.rnd.bridge.crm.enrichment.EnrichmentReplyParser.Result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichmentReplyParserTest {

    private final EnrichmentReplyParser parser = new EnrichmentReplyParser();

    private static Result.Accepted accepted(Result result) {
        return assertInstanceOf(Result.Accepted.class, result);
    }

    private static Result.Rejected rejected(Result result) {
        return assertInstanceOf(Result.Rejected.class, result);
    }

    // --- structure / JSON parsing ------------------------------------------------------

    @Test
    void validReply_withAllFields_accepted() {
        String reply = """
                {"title": "Prepare updated pricing proposal",
                 "description": "Review the current offer and prepare revised pricing.",
                 "effortEstimateMinutes": 45}
                """;
        Result result = parser.parse(reply);
        Result.Accepted a = accepted(result);
        assertEquals("Prepare updated pricing proposal", a.title());
        assertEquals("Review the current offer and prepare revised pricing.", a.description());
        assertEquals(45, a.effortEstimateMinutes());
    }

    @Test
    void optionalFieldsOmitted_acceptedWithNulls() {
        String reply = """
                {"title": "Follow up with the client"}
                """;
        Result result = parser.parse(reply);
        Result.Accepted a = accepted(result);
        assertEquals("Follow up with the client", a.title());
        assertNull(a.description());
        assertNull(a.effortEstimateMinutes());
    }

    @Test
    void unknownKeys_areIgnored() {
        String reply = """
                {"title": "Follow up with the client",
                 "urgency": 5,
                 "status": "DONE"}
                """;
        Result result = parser.parse(reply);
        Result.Accepted a = accepted(result);
        assertEquals("Follow up with the client", a.title());
    }

    @Test
    void fencedReply_withJsonTag_accepted() {
        String reply = "```json\n{\"title\": \"Prepare the proposal\"}\n```";
        Result result = parser.parse(reply);
        assertEquals("Prepare the proposal", accepted(result).title());
    }

    @Test
    void fencedReply_withoutJsonTag_accepted() {
        String reply = "  \n```\n{\"title\": \"Prepare the proposal\"}\n```  \n";
        Result result = parser.parse(reply);
        assertEquals("Prepare the proposal", accepted(result).title());
    }

    @Test
    void proseWithEmbeddedJson_rejectedAsInvalidJson() {
        String reply = "Here is the JSON you asked for: {\"title\": \"Prepare the proposal\"}";
        Result result = parser.parse(reply);
        assertEquals(RejectionReason.INVALID_JSON, rejected(result).reason());
    }

    @Test
    void malformedJson_rejected() {
        String reply = "{\"title\": \"Prepare the proposal\"";
        Result result = parser.parse(reply);
        assertEquals(RejectionReason.INVALID_JSON, rejected(result).reason());
    }

    @Test
    void jsonArrayRoot_rejected() {
        Result result = parser.parse("[1,2,3]");
        assertEquals(RejectionReason.INVALID_JSON, rejected(result).reason());
    }

    // --- title ---------------------------------------------------------------------

    @Test
    void missingTitle_rejected() {
        Result result = parser.parse("{\"description\": \"no title here\"}");
        assertEquals(RejectionReason.MISSING_TITLE, rejected(result).reason());
    }

    @Test
    void blankTitle_rejected() {
        Result result = parser.parse("{\"title\": \"   \"}");
        assertEquals(RejectionReason.MISSING_TITLE, rejected(result).reason());
    }

    @Test
    void titleExactly120Chars_accepted() {
        String title = "x".repeat(120);
        Result result = parser.parse("{\"title\": \"" + title + "\"}");
        assertEquals(title, accepted(result).title());
    }

    @Test
    void titleOver120Chars_rejected() {
        String title = "x".repeat(121);
        Result result = parser.parse("{\"title\": \"" + title + "\"}");
        assertEquals(RejectionReason.TITLE_TOO_LONG, rejected(result).reason());
    }

    // --- description ---------------------------------------------------------------

    @Test
    void descriptionExactly1000Chars_accepted() {
        String description = "x".repeat(1000);
        String reply = "{\"title\": \"t\", \"description\": \"" + description + "\"}";
        Result result = parser.parse(reply);
        assertEquals(description, accepted(result).description());
    }

    @Test
    void descriptionOver1000Chars_rejected() {
        String description = "x".repeat(1001);
        String reply = "{\"title\": \"t\", \"description\": \"" + description + "\"}";
        Result result = parser.parse(reply);
        assertEquals(RejectionReason.DESCRIPTION_TOO_LONG, rejected(result).reason());
    }

    // --- effort ---------------------------------------------------------------------

    @Test
    void effortAsString_rejected() {
        String reply = "{\"title\": \"t\", \"effortEstimateMinutes\": \"45\"}";
        Result result = parser.parse(reply);
        assertEquals(RejectionReason.EFFORT_OUT_OF_RANGE, rejected(result).reason());
    }

    @Test
    void effortAsDecimal_rejected() {
        String reply = "{\"title\": \"t\", \"effortEstimateMinutes\": 45.0}";
        Result result = parser.parse(reply);
        assertEquals(RejectionReason.EFFORT_OUT_OF_RANGE, rejected(result).reason());
    }

    @Test
    void effortNegative_rejected() {
        String reply = "{\"title\": \"t\", \"effortEstimateMinutes\": -10}";
        Result result = parser.parse(reply);
        assertEquals(RejectionReason.EFFORT_OUT_OF_RANGE, rejected(result).reason());
    }

    @Test
    void effortBelowMinimum_rejected() {
        String reply = "{\"title\": \"t\", \"effortEstimateMinutes\": 4}";
        Result result = parser.parse(reply);
        assertEquals(RejectionReason.EFFORT_OUT_OF_RANGE, rejected(result).reason());
    }

    @Test
    void effortAtMinimum_accepted() {
        String reply = "{\"title\": \"t\", \"effortEstimateMinutes\": 5}";
        Result result = parser.parse(reply);
        assertEquals(5, accepted(result).effortEstimateMinutes());
    }

    @Test
    void effortAtMaximum_accepted() {
        String reply = "{\"title\": \"t\", \"effortEstimateMinutes\": 480}";
        Result result = parser.parse(reply);
        assertEquals(480, accepted(result).effortEstimateMinutes());
    }

    @Test
    void effortAboveMaximum_rejected() {
        String reply = "{\"title\": \"t\", \"effortEstimateMinutes\": 481}";
        Result result = parser.parse(reply);
        assertEquals(RejectionReason.EFFORT_OUT_OF_RANGE, rejected(result).reason());
    }

    // --- rejection reason never leaks reply content -------------------------------------

    @Test
    void rejectionReason_isFixedCategory_neverContainsReplyText() {
        String secretMarker = "ZZTOP-SECRET-MARKER-998877";
        String reply = "{\"title\": \"" + secretMarker + "\", \"effortEstimateMinutes\": 9999}";
        Result result = parser.parse(reply);
        Result.Rejected r = rejected(result);
        assertEquals(RejectionReason.EFFORT_OUT_OF_RANGE, r.reason());
        assertFalse(r.reason().toString().contains(secretMarker));
        assertTrue(r.toString().contains(RejectionReason.EFFORT_OUT_OF_RANGE.name()));
        assertFalse(r.toString().contains(secretMarker));
    }
}
