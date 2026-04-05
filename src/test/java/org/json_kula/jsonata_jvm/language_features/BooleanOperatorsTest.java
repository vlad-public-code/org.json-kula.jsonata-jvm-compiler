package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata boolean operators (and, or) and related functions ($boolean, $not).
 *
 * Spec: https://docs.jsonata.org/boolean-operators
 *
 * Key rules:
 *  - Non-boolean operands are coerced via $boolean rules before evaluation.
 *  - Falsy values: false, null, MISSING, 0, "", [], {}
 *  - Truthy values: true, non-zero numbers, non-empty strings/arrays/objects
 *  - Boolean NOT is a function ($not), not an operator.
 */
class BooleanOperatorsTest {

    private JsonNode eval(String expr) throws Exception {
        return JsonNodeTestHelper.evaluate(expr);
    }

    private JsonNode eval(String expr, String json) throws Exception {
        return JsonNodeTestHelper.evaluate(expr, json);
    }

    // =========================================================================
    // and operator
    // =========================================================================

    @Test
    void and_both_true() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("true and true").booleanValue());
    }

    @Test
    void and_left_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("false and true").booleanValue());
    }

    @Test
    void and_right_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("true and false").booleanValue());
    }

    @Test
    void and_both_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("false and false").booleanValue());
    }

    /** Non-boolean operands are coerced: non-empty string is truthy. */
    @Test
    void and_coercion_strings() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("\"hello\" and \"world\"").booleanValue());
    }

    /** Non-boolean operands: empty string is falsy. */
    @Test
    void and_coercion_empty_string_falsy() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("\"hello\" and \"\"").booleanValue());
    }

    /** Non-boolean operands: non-zero number is truthy. */
    @Test
    void and_coercion_number() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("1 and 2").booleanValue());
    }

    /** Zero is falsy. */
    @Test
    void and_coercion_zero_falsy() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("0 and true").booleanValue());
    }

    /** null is falsy. */
    @Test
    void and_coercion_null_falsy() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("null and true").booleanValue());
    }

    /** MISSING (undefined variable) is falsy — yields false, not an error. */
    @Test
    void and_missing_is_falsy() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$notDefined and true").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("true and $notDefined").booleanValue());
    }

    /** Empty array is falsy. */
    @Test
    void and_coercion_empty_array_falsy() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("[] and true").booleanValue());
    }

    /** Non-empty array is truthy. */
    @Test
    void and_coercion_nonempty_array_truthy() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("[1, 2] and true").booleanValue());
    }

    /** Empty object is falsy. */
    @Test
    void and_coercion_empty_object_falsy() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("{} and true").booleanValue());
    }

    /** Spec example: filter with and on a path expression. */
    @Test
    void and_spec_example_filter() throws Exception {
        String json = "{\"library\": {\"books\": ["
                + "{\"title\": \"Dragon\", \"authors\": [\"Aho\", \"Hopcroft\"], \"price\": 40},"
                + "{\"title\": \"TAOCP\",  \"authors\": [\"Knuth\"],             \"price\": 60},"
                + "{\"title\": \"Cheap\",  \"authors\": [\"Aho\"],               \"price\": 10}"
                + "]}}";
        JsonNode result = JsonNodeTestHelper.printJavaAndEvaluate(
                "library.books[\"Aho\" in authors and price < 50].title", json);
        // Dragon (Aho, price=40 <50) and Cheap (Aho, price=10 <50) qualify
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("Dragon", result.get(0).textValue());
        assertEquals("Cheap",  result.get(1).textValue());
    }

    // =========================================================================
    // or operator
    // =========================================================================

    @Test
    void or_both_true() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("true or true").booleanValue());
    }

    @Test
    void or_left_true() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("true or false").booleanValue());
    }

    @Test
    void or_right_true() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("false or true").booleanValue());
    }

    @Test
    void or_both_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("false or false").booleanValue());
    }

    /** Non-boolean coercion: non-empty string is truthy. */
    @Test
    void or_coercion_string_truthy() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("\"\" or \"hello\"").booleanValue());
    }

    /** Zero or zero → false. */
    @Test
    void or_coercion_zero_both_falsy() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("0 or 0").booleanValue());
    }

    /** MISSING is falsy for or — yields false when both sides are missing/false. */
    @Test
    void or_missing_with_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$notDefined or false").booleanValue());
    }

    /** MISSING is falsy but other side is truthy → true. */
    @Test
    void or_missing_with_true() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("$notDefined or true").booleanValue());
    }

    /** Spec example: filter with or. */
    @Test
    void or_spec_example_filter() throws Exception {
        String json = "{\"library\": {\"books\": ["
                + "{\"title\": \"Cheap\",   \"price\": 8,  \"section\": \"cs\"},"
                + "{\"title\": \"DIY\",     \"price\": 20, \"section\": \"diy\"},"
                + "{\"title\": \"Expensive\",\"price\": 99, \"section\": \"cs\"}"
                + "]}}";
        JsonNode result = JsonNodeTestHelper.evaluate(
                "library.books[price < 10 or section = \"diy\"].title", json);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("Cheap", result.get(0).textValue());
        assertEquals("DIY",   result.get(1).textValue());
    }

    // =========================================================================
    // $boolean() function
    // =========================================================================

    @Test
    void boolean_fn_true_passthrough() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("$boolean(true)").booleanValue());
    }

    @Test
    void boolean_fn_false_passthrough() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$boolean(false)").booleanValue());
    }

    @Test
    void boolean_fn_nonempty_string() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("$boolean(\"hello\")").booleanValue());
    }

    @Test
    void boolean_fn_empty_string() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$boolean(\"\")").booleanValue());
    }

    @Test
    void boolean_fn_nonzero_number() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("$boolean(1)").booleanValue());
        assertTrue(JsonNodeTestHelper.evaluate("$boolean(-1)").booleanValue());
    }

    @Test
    void boolean_fn_zero() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$boolean(0)").booleanValue());
    }

    @Test
    void boolean_fn_null() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$boolean(null)").booleanValue());
    }

    @Test
    void boolean_fn_nonempty_array() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("$boolean([1, 2])").booleanValue());
    }

    @Test
    void boolean_fn_empty_array() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$boolean([])").booleanValue());
    }

    @Test
    void boolean_fn_nonempty_object() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("$boolean({\"a\": 1})").booleanValue());
    }

    @Test
    void boolean_fn_empty_object() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$boolean({})").booleanValue());
    }

    @Test
    void boolean_fn_missing_propagates() throws Exception {
        // $boolean of undefined → MISSING → null at evaluate() boundary
        JsonNode result = JsonNodeTestHelper.evaluate("$boolean($notDefined)");
        assertTrue(result.isMissingNode(), "Expected null (MISSING) but got: " + result);
    }

    // =========================================================================
    // $not() function (NOT is a function, not an operator)
    // =========================================================================

    @Test
    void not_fn_true() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$not(true)").booleanValue());
    }

    @Test
    void not_fn_false() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("$not(false)").booleanValue());
    }

    @Test
    void not_fn_nonempty_string() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$not(\"hello\")").booleanValue());
    }

    @Test
    void not_fn_empty_string() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("$not(\"\")").booleanValue());
    }

    @Test
    void not_fn_zero() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("$not(0)").booleanValue());
    }

    @Test
    void not_fn_nonzero() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$not(42)").booleanValue());
    }

    @Test
    void not_fn_null() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("$not(null)").booleanValue());
    }

    @Test
    void not_fn_missing_propagates() throws Exception {
        // $not of undefined → MISSING → null at evaluate() boundary
        JsonNode result = JsonNodeTestHelper.evaluate("$not($notDefined)");
        assertTrue(result.isMissingNode(), "Expected null (MISSING) but got: " + result);
    }

    // =========================================================================
    // Operator precedence: and binds tighter than or
    // =========================================================================

    @Test
    void precedence_and_before_or() throws Exception {
        // false or true and true  →  false or (true and true)  →  false or true  →  true
        assertTrue(JsonNodeTestHelper.evaluate("false or true and true").booleanValue());
        // true and false or true  →  (true and false) or true  →  false or true  →  true
        assertTrue(JsonNodeTestHelper.evaluate("true and false or true").booleanValue());
        // true and false or false →  (true and false) or false →  false or false →  false
        assertFalse(JsonNodeTestHelper.evaluate("true and false or false").booleanValue());
    }
}
