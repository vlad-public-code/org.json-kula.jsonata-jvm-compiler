package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
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

    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();
    private static final String EMPTY = "{}";

    // =========================================================================
    // and operator
    // =========================================================================

    @Test
    void and_both_true() throws Exception {
        assertTrue(FACTORY.compile("true and true").evaluate(EMPTY).booleanValue());
    }

    @Test
    void and_left_false() throws Exception {
        assertFalse(FACTORY.compile("false and true").evaluate(EMPTY).booleanValue());
    }

    @Test
    void and_right_false() throws Exception {
        assertFalse(FACTORY.compile("true and false").evaluate(EMPTY).booleanValue());
    }

    @Test
    void and_both_false() throws Exception {
        assertFalse(FACTORY.compile("false and false").evaluate(EMPTY).booleanValue());
    }

    /** Non-boolean operands are coerced: non-empty string is truthy. */
    @Test
    void and_coercion_strings() throws Exception {
        assertTrue(FACTORY.compile("\"hello\" and \"world\"").evaluate(EMPTY).booleanValue());
    }

    /** Non-boolean operands: empty string is falsy. */
    @Test
    void and_coercion_empty_string_falsy() throws Exception {
        assertFalse(FACTORY.compile("\"hello\" and \"\"").evaluate(EMPTY).booleanValue());
    }

    /** Non-boolean operands: non-zero number is truthy. */
    @Test
    void and_coercion_number() throws Exception {
        assertTrue(FACTORY.compile("1 and 2").evaluate(EMPTY).booleanValue());
    }

    /** Zero is falsy. */
    @Test
    void and_coercion_zero_falsy() throws Exception {
        assertFalse(FACTORY.compile("0 and true").evaluate(EMPTY).booleanValue());
    }

    /** null is falsy. */
    @Test
    void and_coercion_null_falsy() throws Exception {
        assertFalse(FACTORY.compile("null and true").evaluate(EMPTY).booleanValue());
    }

    /** MISSING (undefined variable) is falsy — yields false, not an error. */
    @Test
    void and_missing_is_falsy() throws Exception {
        assertFalse(FACTORY.compile("$notDefined and true").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("true and $notDefined").evaluate(EMPTY).booleanValue());
    }

    /** Empty array is falsy. */
    @Test
    void and_coercion_empty_array_falsy() throws Exception {
        assertFalse(FACTORY.compile("[] and true").evaluate(EMPTY).booleanValue());
    }

    /** Non-empty array is truthy. */
    @Test
    void and_coercion_nonempty_array_truthy() throws Exception {
        assertTrue(FACTORY.compile("[1, 2] and true").evaluate(EMPTY).booleanValue());
    }

    /** Empty object is falsy. */
    @Test
    void and_coercion_empty_object_falsy() throws Exception {
        assertFalse(FACTORY.compile("{} and true").evaluate(EMPTY).booleanValue());
    }

    /** Spec example: filter with and on a path expression. */
    @Test
    void and_spec_example_filter() throws Exception {
        String json = "{\"library\": {\"books\": ["
                + "{\"title\": \"Dragon\", \"authors\": [\"Aho\", \"Hopcroft\"], \"price\": 40},"
                + "{\"title\": \"TAOCP\",  \"authors\": [\"Knuth\"],             \"price\": 60},"
                + "{\"title\": \"Cheap\",  \"authors\": [\"Aho\"],               \"price\": 10}"
                + "]}}";
        JsonNode result = FACTORY.compile(
                "library.books[\"Aho\" in authors and price < 50].title").evaluate(json);
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
        assertTrue(FACTORY.compile("true or true").evaluate(EMPTY).booleanValue());
    }

    @Test
    void or_left_true() throws Exception {
        assertTrue(FACTORY.compile("true or false").evaluate(EMPTY).booleanValue());
    }

    @Test
    void or_right_true() throws Exception {
        assertTrue(FACTORY.compile("false or true").evaluate(EMPTY).booleanValue());
    }

    @Test
    void or_both_false() throws Exception {
        assertFalse(FACTORY.compile("false or false").evaluate(EMPTY).booleanValue());
    }

    /** Non-boolean coercion: non-empty string is truthy. */
    @Test
    void or_coercion_string_truthy() throws Exception {
        assertTrue(FACTORY.compile("\"\" or \"hello\"").evaluate(EMPTY).booleanValue());
    }

    /** Zero or zero → false. */
    @Test
    void or_coercion_zero_both_falsy() throws Exception {
        assertFalse(FACTORY.compile("0 or 0").evaluate(EMPTY).booleanValue());
    }

    /** MISSING is falsy for or — yields false when both sides are missing/false. */
    @Test
    void or_missing_with_false() throws Exception {
        assertFalse(FACTORY.compile("$notDefined or false").evaluate(EMPTY).booleanValue());
    }

    /** MISSING is falsy but other side is truthy → true. */
    @Test
    void or_missing_with_true() throws Exception {
        assertTrue(FACTORY.compile("$notDefined or true").evaluate(EMPTY).booleanValue());
    }

    /** Spec example: filter with or. */
    @Test
    void or_spec_example_filter() throws Exception {
        String json = "{\"library\": {\"books\": ["
                + "{\"title\": \"Cheap\",   \"price\": 8,  \"section\": \"cs\"},"
                + "{\"title\": \"DIY\",     \"price\": 20, \"section\": \"diy\"},"
                + "{\"title\": \"Expensive\",\"price\": 99, \"section\": \"cs\"}"
                + "]}}";
        JsonNode result = FACTORY.compile(
                "library.books[price < 10 or section = \"diy\"].title").evaluate(json);
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
        assertTrue(FACTORY.compile("$boolean(true)").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_false_passthrough() throws Exception {
        assertFalse(FACTORY.compile("$boolean(false)").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_nonempty_string() throws Exception {
        assertTrue(FACTORY.compile("$boolean(\"hello\")").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_empty_string() throws Exception {
        assertFalse(FACTORY.compile("$boolean(\"\")").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_nonzero_number() throws Exception {
        assertTrue(FACTORY.compile("$boolean(1)").evaluate(EMPTY).booleanValue());
        assertTrue(FACTORY.compile("$boolean(-1)").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_zero() throws Exception {
        assertFalse(FACTORY.compile("$boolean(0)").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_null() throws Exception {
        assertFalse(FACTORY.compile("$boolean(null)").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_nonempty_array() throws Exception {
        assertTrue(FACTORY.compile("$boolean([1, 2])").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_empty_array() throws Exception {
        assertFalse(FACTORY.compile("$boolean([])").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_nonempty_object() throws Exception {
        assertTrue(FACTORY.compile("$boolean({\"a\": 1})").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_empty_object() throws Exception {
        assertFalse(FACTORY.compile("$boolean({})").evaluate(EMPTY).booleanValue());
    }

    @Test
    void boolean_fn_missing_propagates() throws Exception {
        // $boolean of undefined → MISSING → null at evaluate() boundary
        JsonNode result = FACTORY.compile("$boolean($notDefined)").evaluate(EMPTY);
        assertTrue(result.isNull(), "Expected null (MISSING) but got: " + result);
    }

    // =========================================================================
    // $not() function (NOT is a function, not an operator)
    // =========================================================================

    @Test
    void not_fn_true() throws Exception {
        assertFalse(FACTORY.compile("$not(true)").evaluate(EMPTY).booleanValue());
    }

    @Test
    void not_fn_false() throws Exception {
        assertTrue(FACTORY.compile("$not(false)").evaluate(EMPTY).booleanValue());
    }

    @Test
    void not_fn_nonempty_string() throws Exception {
        assertFalse(FACTORY.compile("$not(\"hello\")").evaluate(EMPTY).booleanValue());
    }

    @Test
    void not_fn_empty_string() throws Exception {
        assertTrue(FACTORY.compile("$not(\"\")").evaluate(EMPTY).booleanValue());
    }

    @Test
    void not_fn_zero() throws Exception {
        assertTrue(FACTORY.compile("$not(0)").evaluate(EMPTY).booleanValue());
    }

    @Test
    void not_fn_nonzero() throws Exception {
        assertFalse(FACTORY.compile("$not(42)").evaluate(EMPTY).booleanValue());
    }

    @Test
    void not_fn_null() throws Exception {
        assertTrue(FACTORY.compile("$not(null)").evaluate(EMPTY).booleanValue());
    }

    @Test
    void not_fn_missing_propagates() throws Exception {
        // $not of undefined → MISSING → null at evaluate() boundary
        JsonNode result = FACTORY.compile("$not($notDefined)").evaluate(EMPTY);
        assertTrue(result.isNull(), "Expected null (MISSING) but got: " + result);
    }

    // =========================================================================
    // Operator precedence: and binds tighter than or
    // =========================================================================

    @Test
    void precedence_and_before_or() throws Exception {
        // false or true and true  →  false or (true and true)  →  false or true  →  true
        assertTrue(FACTORY.compile("false or true and true").evaluate(EMPTY).booleanValue());
        // true and false or true  →  (true and false) or true  →  false or true  →  true
        assertTrue(FACTORY.compile("true and false or true").evaluate(EMPTY).booleanValue());
        // true and false or false →  (true and false) or false →  false or false →  false
        assertFalse(FACTORY.compile("true and false or false").evaluate(EMPTY).booleanValue());
    }
}
