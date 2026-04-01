package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata numeric operators: +, -, *, /, %, .., unary minus.
 *
 * Spec: https://docs.jsonata.org/numeric-operators
 */
class NumericOperatorsTest {

    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();
    private static final String EMPTY = "{}";
    private static final String DOC =
            "{\"x\": 10, \"y\": 3, \"items\": [1, 2, 3, 4, 5]}";

    // =========================================================================
    // Basic arithmetic
    // =========================================================================

    @Test
    void add_integers() throws Exception {
        assertEquals(7L, FACTORY.compile("3 + 4").evaluate(EMPTY).longValue());
    }

    @Test
    void subtract_integers() throws Exception {
        assertEquals(5L, FACTORY.compile("9 - 4").evaluate(EMPTY).longValue());
    }

    @Test
    void multiply_integers() throws Exception {
        assertEquals(12L, FACTORY.compile("3 * 4").evaluate(EMPTY).longValue());
    }

    @Test
    void divide_integers() throws Exception {
        // 10 / 4 = 2.5
        assertEquals(2.5, FACTORY.compile("10 / 4").evaluate(EMPTY).doubleValue(), 1e-10);
    }

    @Test
    void divide_wholeResult() throws Exception {
        // 12 / 4 = 3 (integer result)
        assertEquals(3L, FACTORY.compile("12 / 4").evaluate(EMPTY).longValue());
    }

    @Test
    void modulo_positive() throws Exception {
        assertEquals(1L, FACTORY.compile("10 % 3").evaluate(EMPTY).longValue());
    }

    @Test
    void modulo_zero_remainder() throws Exception {
        assertEquals(0L, FACTORY.compile("9 % 3").evaluate(EMPTY).longValue());
    }

    @Test
    void unary_minus() throws Exception {
        assertEquals(-5L, FACTORY.compile("-5").evaluate(EMPTY).longValue());
    }

    @Test
    void double_unary_minus() throws Exception {
        assertEquals(5L, FACTORY.compile("- -5").evaluate(EMPTY).longValue());
    }

    @Test
    void operator_precedence() throws Exception {
        // 2 + 3 * 4 = 14 (multiplication before addition)
        assertEquals(14L, FACTORY.compile("2 + 3 * 4").evaluate(EMPTY).longValue());
    }

    @Test
    void parenthesised_expression() throws Exception {
        // (2 + 3) * 4 = 20
        assertEquals(20L, FACTORY.compile("(2 + 3) * 4").evaluate(EMPTY).longValue());
    }

    @Test
    void floating_point_arithmetic() throws Exception {
        assertEquals(0.3, FACTORY.compile("0.1 + 0.2").evaluate(EMPTY).doubleValue(), 1e-10);
    }

    @Test
    void arithmetic_from_doc_fields() throws Exception {
        assertEquals(13L, FACTORY.compile("x + y").evaluate(DOC).longValue());
        assertEquals(7L, FACTORY.compile("x - y").evaluate(DOC).longValue());
        assertEquals(30L, FACTORY.compile("x * y").evaluate(DOC).longValue());
        assertEquals(1L, FACTORY.compile("x % y").evaluate(DOC).longValue());
    }

    // =========================================================================
    // Error conditions — non-number operands
    // =========================================================================

    @Test
    void add_boolean_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FACTORY.compile("true + 1").evaluate(EMPTY));
    }

    @Test
    void subtract_null_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FACTORY.compile("null - 1").evaluate(EMPTY));
    }

    @Test
    void multiply_string_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FACTORY.compile("\"abc\" * 2").evaluate(EMPTY));
    }

    @Test
    void divide_by_zero_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FACTORY.compile("1 / 0").evaluate(EMPTY));
    }

    @Test
    void modulo_by_zero_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FACTORY.compile("10 % 0").evaluate(EMPTY));
    }

    @Test
    void unary_minus_boolean_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FACTORY.compile("-true").evaluate(EMPTY));
    }

    // =========================================================================
    // MISSING propagation (undefined variables silently yield MISSING)
    // =========================================================================

    @Test
    void add_missing_yields_missing() throws Exception {
        // $notDefined is MISSING → MISSING propagates → evaluate() returns null
        JsonNode result = FACTORY.compile("$notDefined + 1").evaluate(EMPTY);
        assertTrue(result.isNull(), "Expected null (from MISSING) but got: " + result);
    }

    @Test
    void subtract_missing_yields_missing() throws Exception {
        JsonNode result = FACTORY.compile("$notDefined - 1").evaluate(EMPTY);
        assertTrue(result.isNull());
    }

    // =========================================================================
    // Range operator (..)
    // =========================================================================

    @Test
    void range_basic() throws Exception {
        JsonNode result = FACTORY.compile("[1..5]").evaluate(EMPTY);
        assertTrue(result.isArray());
        assertEquals(5, result.size());
        for (int i = 0; i < 5; i++) assertEquals(i + 1, result.get(i).intValue());
    }

    @Test
    void range_single_element() throws Exception {
        JsonNode result = FACTORY.compile("[3..3]").evaluate(EMPTY);
        // Single-element array — JSONata unwraps singletons, but array constructor keeps it
        assertFalse(result.isNull());
    }

    @Test
    void range_reversed_empty() throws Exception {
        // from > to → empty array
        JsonNode result = FACTORY.compile("[5..1]").evaluate(EMPTY);
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void range_multi_range_in_array() throws Exception {
        // [1..3, 7..9] = [1, 2, 3, 7, 8, 9]
        JsonNode result = FACTORY.compile("[1..3, 7..9]").evaluate(EMPTY);
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
        JsonNode result = FACTORY.compile("[1..5].($*$)").evaluate(EMPTY);
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
                () -> FACTORY.compile("[1.5..5]").evaluate(EMPTY));
    }

    @Test
    void range_non_number_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FACTORY.compile("[\"a\"..5]").evaluate(EMPTY));
    }

    @Test
    void range_missing_propagates() throws Exception {
        // $notDefined..5 → MISSING
        JsonNode result = FACTORY.compile("[$notDefined..5]").evaluate(EMPTY);
        // MISSING from range → array constructor with no elements → empty or null
        assertTrue(result.isNull() || (result.isArray() && result.size() == 0));
    }

    @Test
    void range_with_count() throws Exception {
        // [1..$count(items)].("Item " & $) where items=[1,2,3,4,5]
        // Should produce ["Item 1","Item 2","Item 3","Item 4","Item 5"]
        String json = "{\"items\": [10, 20, 30]}";
        JsonNode result = FACTORY.compile("[1..$count(items)].(\"Item \" & $)").evaluate(json);
        assertTrue(result.isArray());
        assertEquals(3, result.size());
        assertEquals("Item 1", result.get(0).textValue());
        assertEquals("Item 2", result.get(1).textValue());
        assertEquals("Item 3", result.get(2).textValue());
    }
}
