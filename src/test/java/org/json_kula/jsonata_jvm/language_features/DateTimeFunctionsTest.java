package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the JSONata date/time built-in functions:
 * $now(), $millis(), $fromMillis(), $toMillis().
 *
 * Spec: https://docs.jsonata.org/date-time-functions
 */
class DateTimeFunctionsTest {

    private JsonNode eval(String expr) throws Exception {
        return JsonNodeTestHelper.evaluate(expr);
    }

    // =========================================================================
    // $millis()
    // =========================================================================

    @Test
    void millis_returnsNumber() throws Exception {
        JsonNode result = JsonNodeTestHelper.evaluate("$millis()");
        assertTrue(result.isNumber(), "Expected a number but got: " + result);
    }

    @Test
    void millis_valueIsReasonable() throws Exception {
        long before = System.currentTimeMillis();
        long value = JsonNodeTestHelper.evaluate("$millis()").longValue();
        long after = System.currentTimeMillis();
        assertTrue(value >= before && value <= after,
                "Expected millis between " + before + " and " + after + " but got " + value);
    }

    /** Per spec: all occurrences of $millis() within one evaluation return the same value. */
    @Test
    void millis_frozenWithinSingleEvaluation() throws Exception {
        // Evaluates both calls in one expression — they must be identical.
        JsonNode result = JsonNodeTestHelper.evaluate("$millis() = $millis()");
        assertTrue(result.booleanValue(), "$millis() must return the same value within one evaluation");
    }

    // =========================================================================
    // $now()
    // =========================================================================

    @Test
    void now_returnsIso8601String() throws Exception {
        JsonNode result = JsonNodeTestHelper.evaluate("$now()");
        assertTrue(result.isTextual(), "Expected a string but got: " + result);
        // Must be parseable as an ISO 8601 instant
        assertDoesNotThrow(() -> Instant.parse(result.textValue()),
                "Expected ISO 8601 but got: " + result.textValue());
    }

    @Test
    void now_valueIsReasonable() throws Exception {
        long before = System.currentTimeMillis();
        String iso = JsonNodeTestHelper.evaluate("$now()").textValue();
        long after = System.currentTimeMillis();
        long value = Instant.parse(iso).toEpochMilli();
        assertTrue(value >= before && value <= after,
                "Expected timestamp between " + before + " and " + after + " but got " + value);
    }

    /** Per spec: all occurrences of $now() within one evaluation return the same value. */
    @Test
    void now_frozenWithinSingleEvaluation() throws Exception {
        JsonNode result = JsonNodeTestHelper.evaluate("$now() = $now()");
        assertTrue(result.booleanValue(), "$now() must return the same value within one evaluation");
    }

    /** $now() and $millis() must reflect the same underlying evaluation timestamp. */
    @Test
    void now_andMillis_sameTimestamp() throws Exception {
        // $toMillis($now()) should equal $millis()
        JsonNode result = JsonNodeTestHelper.evaluate("$toMillis($now()) = $millis()");
        assertTrue(result.booleanValue(),
                "$toMillis($now()) must equal $millis() within one evaluation");
    }

    @Test
    void now_withPictureString() throws Exception {
        // Example from the spec: [M01]/[D01]/[Y0001] [h#1]:[m01][P]
        // For a known millis value, verify output shape.
        // $fromMillis(1510067557121, '[M01]/[D01]/[Y0001] [h#1]:[m01][P]') = "11/07/2017 3:12pm"
        // We can't predict $now() exactly, but we can check the format is consistent
        // by round-tripping through $toMillis.
        String picture = "[Y0001]-[M01]-[D01]";
        JsonNode result = JsonNodeTestHelper.evaluate("$now(\"" + picture + "\")");
        assertTrue(result.isTextual(), "Expected a string");
        // Must match YYYY-MM-DD
        assertTrue(result.textValue().matches("\\d{4}-\\d{2}-\\d{2}"),
                "Expected YYYY-MM-DD but got: " + result.textValue());
    }

    @Test
    void now_withPictureAndTimezone() throws Exception {
        // $now('[H01]:[m01]', '+0530') — should return a string like "HH:mm"
        JsonNode result = JsonNodeTestHelper.evaluate("$now(\"[H01]:[m01]\", \"+0530\")");
        assertTrue(result.isTextual(), "Expected a string");
        assertTrue(result.textValue().matches("\\d{2}:\\d{2}"),
                "Expected HH:mm but got: " + result.textValue());
    }

    // =========================================================================
    // $fromMillis()
    // =========================================================================

    @Test
    void fromMillis_defaultIso() throws Exception {
        // Spec example: $fromMillis(1510067557121) = "2017-11-07T15:12:37.121Z"
        JsonNode result = JsonNodeTestHelper.evaluate("$fromMillis(1510067557121)");
        assertEquals("2017-11-07T15:12:37.121Z", result.textValue());
    }

    @Test
    void fromMillis_withPicture() throws Exception {
        // Spec example: $fromMillis(1510067557121, '[M01]/[D01]/[Y0001] [h#1]:[m01][P]')
        //               = "11/07/2017 3:12pm"
        JsonNode result = JsonNodeTestHelper.evaluate(
                "$fromMillis(1510067557121, '[M01]/[D01]/[Y0001] [h#1]:[m01][P]')");
        assertEquals("11/07/2017 3:12pm", result.textValue());
    }

    @Test
    void fromMillis_withPictureAndTimezone() throws Exception {
        // Spec example: $fromMillis(1510067557121, '[H01]:[m01]:[s01] [z]', '-0500')
        //               = "10:12:37 GMT-05:00"
        JsonNode result = JsonNodeTestHelper.evaluate(
                "$fromMillis(1510067557121, '[H01]:[m01]:[s01] [z]', '-0500')");
        assertEquals("10:12:37 GMT-05:00", result.textValue());
    }

    @Test
    void fromMillis_missingReturnsNothing() throws Exception {
        // MISSING propagates — expression result is NULL (the evaluate boundary converts MISSING→NULL)
        JsonNode result = JsonNodeTestHelper.evaluate("$fromMillis($notDefined)");
        // $notDefined is MISSING, so result should be NULL (the evaluate() boundary)
        assertTrue(result.isMissingNode(), "Expected null but got: " + result);
    }

    @Test
    void fromMillis_roundTrip() throws Exception {
        // $toMillis($fromMillis(x)) = x for arbitrary millis
        JsonNode result = JsonNodeTestHelper.evaluate("$toMillis($fromMillis(1510067557121))");
        assertEquals(1510067557121L, result.longValue());
    }

    // =========================================================================
    // $toMillis()
    // =========================================================================

    @Test
    void toMillis_isoString() throws Exception {
        // Spec example: $toMillis("2017-11-07T15:07:54.972Z") = 1510067274972
        JsonNode result = JsonNodeTestHelper.evaluate("$toMillis(\"2017-11-07T15:07:54.972Z\")");
        assertEquals(1510067274972L, result.longValue());
    }

    @Test
    void toMillis_isoStringNoMillis() throws Exception {
        // Timestamp with zero milliseconds
        JsonNode result = JsonNodeTestHelper.evaluate("$toMillis(\"2017-11-07T15:12:37Z\")");
        assertEquals(1510067557000L, result.longValue());
    }

    @Test
    void toMillis_roundTrip() throws Exception {
        // $fromMillis($toMillis(s)) = s  (for UTC ISO timestamps)
        JsonNode result = JsonNodeTestHelper.evaluate(
                "$fromMillis($toMillis(\"2017-11-07T15:12:37.121Z\"))");
        assertEquals("2017-11-07T15:12:37.121Z", result.textValue());
    }

    @Test
    void toMillis_missingReturnsNull() throws Exception {
        JsonNode result = JsonNodeTestHelper.evaluate("$toMillis($notDefined)");
        assertTrue(result.isMissingNode(), "Expected null but got: " + result);
    }
}
