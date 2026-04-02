package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata numeric operators: +, -, *, /, %, .., unary minus.
 *
 * Spec: https://docs.jsonata.org/numeric-operators
 */
class NumericOperatorsTest {

    private JsonNode eval(String expr) throws Exception {
        return JsonNodeTestHelper.evaluate(expr);
    }

    private JsonNode eval(String expr, String json) throws Exception {
        return JsonNodeTestHelper.evaluate(expr, json);
    }

    private static final String DOC =
            "{\"x\": 10, \"y\": 3, \"items\": [1, 2, 3, 4, 5]}";

    // =========================================================================
    // Basic arithmetic
    // =========================================================================

    @Test
    void add_integers() throws Exception {
        assertEquals(7L, JsonNodeTestHelper.evaluate("3 + 4").longValue());
    }

    @Test
    void subtract_integers() throws Exception {
        assertEquals(5L, JsonNodeTestHelper.evaluate("9 - 4").longValue());
    }

    @Test
    void multiply_integers() throws Exception {
        assertEquals(12L, JsonNodeTestHelper.evaluate("3 * 4").longValue());
    }

    @Test
    void divide_integers() throws Exception {
        // 10 / 4 = 2.5
        assertEquals(2.5, JsonNodeTestHelper.evaluate("10 / 4").doubleValue(), 1e-10);
    }

    @Test
    void divide_wholeResult() throws Exception {
        // 12 / 4 = 3 (integer result)
        assertEquals(3L, JsonNodeTestHelper.evaluate("12 / 4").longValue());
    }

    @Test
    void modulo_positive() throws Exception {
        assertEquals(1L, JsonNodeTestHelper.evaluate("10 % 3").longValue());
    }

    @Test
    void modulo_zero_remainder() throws Exception {
        assertEquals(0L, JsonNodeTestHelper.evaluate("9 % 3").longValue());
    }

    @Test
    void unary_minus() throws Exception {
        assertEquals(-5L, JsonNodeTestHelper.evaluate("-5").longValue());
    }

    @Test
    void double_unary_minus() throws Exception {
        assertEquals(5L, JsonNodeTestHelper.evaluate("- -5").longValue());
    }

    @Test
    void operator_precedence() throws Exception {
        // 2 + 3 * 4 = 14 (multiplication before addition)
        assertEquals(14L, JsonNodeTestHelper.evaluate("2 + 3 * 4").longValue());
    }

    @Test
    void parenthesised_expression() throws Exception {
        // (2 + 3) * 4 = 20
        assertEquals(20L, JsonNodeTestHelper.evaluate("(2 + 3) * 4").longValue());
    }

    @Test
    void floating_point_arithmetic() throws Exception {
        assertEquals(0.3, JsonNodeTestHelper.evaluate("0.1 + 0.2").doubleValue(), 1e-10);
    }

    @Test
    void arithmetic_from_doc_fields() throws Exception {
        assertEquals(13L, JsonNodeTestHelper.evaluate("x + y", DOC).longValue());
        assertEquals(7L, JsonNodeTestHelper.evaluate("x - y", DOC).longValue());
        assertEquals(30L, JsonNodeTestHelper.evaluate("x * y", DOC).longValue());
        assertEquals(1L, JsonNodeTestHelper.evaluate("x % y", DOC).longValue());
    }

    // =========================================================================
    // Error conditions — non-number operands
    // =========================================================================

    @Test
    void add_boolean_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> JsonNodeTestHelper.evaluate("true + 1"));
    }

    @Test
    void subtract_null_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> JsonNodeTestHelper.evaluate("null - 1"));
    }

    @Test
    void multiply_string_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> JsonNodeTestHelper.evaluate("\"abc\" * 2"));
    }

    @Test
    void divide_by_zero_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> JsonNodeTestHelper.evaluate("1 / 0"));
    }

    @Test
    void modulo_by_zero_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> JsonNodeTestHelper.evaluate("10 % 0"));
    }

    @Test
    void unary_minus_boolean_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> JsonNodeTestHelper.evaluate("-true"));
    }

    // =========================================================================
    // MISSING propagation (undefined variables silently yield MISSING)
    // =========================================================================

    @Test
    void add_missing_yields_missing() throws Exception {
        // $notDefined is MISSING → MISSING propagates → evaluate() returns null
        JsonNode result = JsonNodeTestHelper.evaluate("$notDefined + 1");
        assertTrue(result.isNull(), "Expected null (from MISSING) but got: " + result);
    }

    @Test
    void subtract_missing_yields_missing() throws Exception {
        JsonNode result = JsonNodeTestHelper.evaluate("$notDefined - 1");
        assertTrue(result.isNull());
    }

    // =========================================================================
    // Range operator (..)
    // =========================================================================

    @Test
    void range_basic() throws Exception {
        JsonNode result = JsonNodeTestHelper.evaluate("[1..5]");
        assertTrue(result.isArray());
        assertEquals(5, result.size());
        for (int i = 0; i < 5; i++) assertEquals(i + 1, result.get(i).intValue());
    }

    @Test
    void range_single_element() throws Exception {
        JsonNode result = JsonNodeTestHelper.evaluate("[3..3]");
        // Single-element array — JSONata unwraps singletons, but array constructor keeps it
        assertFalse(result.isNull());
    }

    @Test
    void range_reversed_empty() throws Exception {
        // from > to → empty array
        JsonNode result = JsonNodeTestHelper.evaluate("[5..1]");
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void range_multi_range_in_array() throws Exception {
        // [1..3, 7..9] = [1, 2, 3, 7, 8, 9]
        JsonNode result = JsonNodeTestHelper.evaluate("[1..3, 7..9]");
        assertTrue(result.isArray());
        assertEquals(6, result.size());
        assertEquals(1, result.get(0).intValue());
        assertEquals(2, result.get(1).intValue());
        assertEquals(3, result.get(2).intValue());
        assertEquals(7, result.get(3).intValue());
        assertEquals(8, result.get(4).intValue());
        assertEquals(9, result.get(5).intValue());
    }

    @Test
    void range_map_expression() throws Exception {
        // [1..5].($*$) = [1, 4, 9, 16, 25]
        JsonNode result = JsonNodeTestHelper.evaluate("[1..5].($*$)");
        assertTrue(result.isArray());
        assertEquals(5, result.size());
        assertEquals(1, result.get(0).intValue());
        assertEquals(4, result.get(1).intValue());
        assertEquals(9, result.get(2).intValue());
        assertEquals(16, result.get(3).intValue());
        assertEquals(25, result.get(4).intValue());
    }

    @Test
    void range_float_operand_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> JsonNodeTestHelper.evaluate("[1.5..5]"));
    }

    @Test
    void range_non_number_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> JsonNodeTestHelper.evaluate("[\"a\"..5]"));
    }

    @Test
    void range_missing_propagates() throws Exception {
        // $notDefined..5 → MISSING
        JsonNode result = JsonNodeTestHelper.evaluate("[$notDefined..5]");
        // MISSING from range → array constructor with no elements → empty or null
        assertTrue(result.isNull() || (result.isArray() && result.size() == 0));
    }

    @Test
    void range_with_count() throws Exception {
        // [1..$count(items)].("Item " & $) where items=[1,2,3,4,5]
        // Should produce ["Item 1","Item 2","Item 3","Item 4","Item 5"]
        String json = "{\"items\": [10, 20, 30]}";
        JsonNode result = JsonNodeTestHelper.evaluate("[1..$count(items)].(\"Item \" & $)", json);
        assertTrue(result.isArray());
        assertEquals(3, result.size());
        assertEquals("Item 1", result.get(0).textValue());
        assertEquals("Item 2", result.get(1).textValue());
        assertEquals("Item 3", result.get(2).textValue());
    }
}
