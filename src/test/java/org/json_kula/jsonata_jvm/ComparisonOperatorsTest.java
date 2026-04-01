package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata comparison operators: =, !=, <, >, <=, >=, in.
 *
 * Spec: https://docs.jsonata.org/comparison-operators
 */
class ComparisonOperatorsTest {

    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();
    private static final String EMPTY = "{}";

    // =========================================================================
    // = (equals)
    // =========================================================================

    @Test
    void eq_numbers_equal() throws Exception {
        assertTrue(FACTORY.compile("1 + 1 = 2").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_numbers_not_equal() throws Exception {
        assertFalse(FACTORY.compile("1 + 1 = 3").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_strings_equal() throws Exception {
        assertTrue(FACTORY.compile("\"Hello\" = \"Hello\"").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_strings_not_equal() throws Exception {
        assertFalse(FACTORY.compile("\"Hello\" = \"World\"").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_booleans_equal() throws Exception {
        assertTrue(FACTORY.compile("true = true").evaluate(EMPTY).booleanValue());
        assertTrue(FACTORY.compile("false = false").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_booleans_not_equal() throws Exception {
        assertFalse(FACTORY.compile("true = false").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_null_null() throws Exception {
        assertTrue(FACTORY.compile("null = null").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_null_number() throws Exception {
        assertFalse(FACTORY.compile("null = 0").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_cross_type_number_string() throws Exception {
        assertFalse(FACTORY.compile("1 = \"1\"").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_deep_arrays_equal() throws Exception {
        assertTrue(FACTORY.compile("[1, 2, 3] = [1, 2, 3]").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_deep_arrays_not_equal() throws Exception {
        assertFalse(FACTORY.compile("[1, 2, 3] = [1, 2, 4]").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_deep_objects_equal() throws Exception {
        assertTrue(FACTORY.compile("{\"a\": 1} = {\"a\": 1}").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_deep_objects_not_equal() throws Exception {
        assertFalse(FACTORY.compile("{\"a\": 1} = {\"a\": 2}").evaluate(EMPTY).booleanValue());
    }

    @Test
    void eq_missing_returns_false() throws Exception {
        // undefined variable compared with anything → false (not an error)
        JsonNode result = FACTORY.compile("$notDefined = 1").evaluate(EMPTY);
        assertFalse(result.booleanValue());
    }

    @Test
    void eq_both_missing_returns_false() throws Exception {
        JsonNode result = FACTORY.compile("$a = $b").evaluate(EMPTY);
        assertFalse(result.booleanValue());
    }

    @Test
    void eq_integer_float_equal() throws Exception {
        // 1 and 1.0 are numerically equal
        assertTrue(FACTORY.compile("1 = 1.0").evaluate(EMPTY).booleanValue());
    }

    // =========================================================================
    // != (not equals)
    // =========================================================================

    @Test
    void ne_numbers() throws Exception {
        assertTrue(FACTORY.compile("1 + 1 != 3").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("1 + 1 != 2").evaluate(EMPTY).booleanValue());
    }

    @Test
    void ne_strings() throws Exception {
        assertTrue(FACTORY.compile("\"Hello\" != \"World\"").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("\"Hello\" != \"Hello\"").evaluate(EMPTY).booleanValue());
    }

    @Test
    void ne_missing_returns_false() throws Exception {
        // MISSING != anything → false (undefined propagation)
        JsonNode result = FACTORY.compile("$notDefined != 1").evaluate(EMPTY);
        assertFalse(result.booleanValue());
    }

    @Test
    void ne_null_null() throws Exception {
        assertFalse(FACTORY.compile("null != null").evaluate(EMPTY).booleanValue());
    }

    @Test
    void ne_cross_type() throws Exception {
        assertTrue(FACTORY.compile("1 != \"1\"").evaluate(EMPTY).booleanValue());
    }

    // =========================================================================
    // > (greater than)
    // =========================================================================

    @Test
    void gt_numbers_true() throws Exception {
        assertTrue(FACTORY.compile("22 / 7 > 3").evaluate(EMPTY).booleanValue());
    }

    @Test
    void gt_numbers_equal_false() throws Exception {
        assertFalse(FACTORY.compile("5 > 5").evaluate(EMPTY).booleanValue());
    }

    @Test
    void gt_numbers_less_false() throws Exception {
        assertFalse(FACTORY.compile("3 > 5").evaluate(EMPTY).booleanValue());
    }

    @Test
    void gt_strings() throws Exception {
        assertTrue(FACTORY.compile("\"b\" > \"a\"").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("\"a\" > \"b\"").evaluate(EMPTY).booleanValue());
    }

    @Test
    void gt_missing_returns_false() throws Exception {
        assertFalse(FACTORY.compile("$notDefined > 1").evaluate(EMPTY).booleanValue());
    }

    @Test
    void gt_mismatched_types_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FACTORY.compile("1 > \"a\"").evaluate(EMPTY));
    }

    // =========================================================================
    // < (less than)
    // =========================================================================

    @Test
    void lt_numbers_true() throws Exception {
        assertFalse(FACTORY.compile("22 / 7 < 3").evaluate(EMPTY).booleanValue());
    }

    @Test
    void lt_numbers_equal_false() throws Exception {
        assertFalse(FACTORY.compile("5 < 5").evaluate(EMPTY).booleanValue());
    }

    @Test
    void lt_numbers_greater_true() throws Exception {
        assertTrue(FACTORY.compile("3 < 5").evaluate(EMPTY).booleanValue());
    }

    @Test
    void lt_strings() throws Exception {
        assertTrue(FACTORY.compile("\"a\" < \"b\"").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("\"b\" < \"a\"").evaluate(EMPTY).booleanValue());
    }

    @Test
    void lt_missing_returns_false() throws Exception {
        assertFalse(FACTORY.compile("$notDefined < 1").evaluate(EMPTY).booleanValue());
    }

    @Test
    void lt_mismatched_types_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FACTORY.compile("\"a\" < 1").evaluate(EMPTY));
    }

    // =========================================================================
    // >= (greater than or equals)
    // =========================================================================

    @Test
    void ge_numbers_greater() throws Exception {
        assertTrue(FACTORY.compile("22 / 7 >= 3").evaluate(EMPTY).booleanValue());
    }

    @Test
    void ge_numbers_equal() throws Exception {
        assertTrue(FACTORY.compile("5 >= 5").evaluate(EMPTY).booleanValue());
    }

    @Test
    void ge_numbers_less() throws Exception {
        assertFalse(FACTORY.compile("3 >= 5").evaluate(EMPTY).booleanValue());
    }

    @Test
    void ge_strings() throws Exception {
        assertTrue(FACTORY.compile("\"b\" >= \"b\"").evaluate(EMPTY).booleanValue());
        assertTrue(FACTORY.compile("\"b\" >= \"a\"").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("\"a\" >= \"b\"").evaluate(EMPTY).booleanValue());
    }

    @Test
    void ge_missing_returns_false() throws Exception {
        assertFalse(FACTORY.compile("$notDefined >= 1").evaluate(EMPTY).booleanValue());
    }

    // =========================================================================
    // <= (less than or equals)
    // =========================================================================

    @Test
    void le_numbers_less() throws Exception {
        assertFalse(FACTORY.compile("22 / 7 <= 3").evaluate(EMPTY).booleanValue());
    }

    @Test
    void le_numbers_equal() throws Exception {
        assertTrue(FACTORY.compile("5 <= 5").evaluate(EMPTY).booleanValue());
    }

    @Test
    void le_numbers_greater() throws Exception {
        assertTrue(FACTORY.compile("3 <= 5").evaluate(EMPTY).booleanValue());
    }

    @Test
    void le_strings() throws Exception {
        assertTrue(FACTORY.compile("\"a\" <= \"a\"").evaluate(EMPTY).booleanValue());
        assertTrue(FACTORY.compile("\"a\" <= \"b\"").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("\"b\" <= \"a\"").evaluate(EMPTY).booleanValue());
    }

    @Test
    void le_missing_returns_false() throws Exception {
        assertFalse(FACTORY.compile("$notDefined <= 1").evaluate(EMPTY).booleanValue());
    }

    // =========================================================================
    // in (inclusion)
    // =========================================================================

    @Test
    void in_value_present() throws Exception {
        assertTrue(FACTORY.compile("\"world\" in [\"hello\", \"world\"]").evaluate(EMPTY).booleanValue());
    }

    @Test
    void in_value_absent() throws Exception {
        assertFalse(FACTORY.compile("\"foo\" in [\"hello\", \"world\"]").evaluate(EMPTY).booleanValue());
    }

    @Test
    void in_number_in_array() throws Exception {
        assertTrue(FACTORY.compile("2 in [1, 2, 3]").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("5 in [1, 2, 3]").evaluate(EMPTY).booleanValue());
    }

    @Test
    void in_rhs_single_value_treated_as_singleton() throws Exception {
        // Single value on RHS treated as singleton array
        assertTrue(FACTORY.compile("42 in 42").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("42 in 43").evaluate(EMPTY).booleanValue());
    }

    @Test
    void in_missing_returns_false() throws Exception {
        assertFalse(FACTORY.compile("$notDefined in [1, 2, 3]").evaluate(EMPTY).booleanValue());
    }

    @Test
    void in_from_doc_field() throws Exception {
        String json = "{\"colors\": [\"red\", \"green\", \"blue\"]}";
        assertTrue(FACTORY.compile("\"red\" in colors").evaluate(json).booleanValue());
        assertFalse(FACTORY.compile("\"purple\" in colors").evaluate(json).booleanValue());
    }

    @Test
    void in_with_boolean() throws Exception {
        assertTrue(FACTORY.compile("true in [false, true]").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("true in [false, false]").evaluate(EMPTY).booleanValue());
    }

    @Test
    void in_with_null() throws Exception {
        assertTrue(FACTORY.compile("null in [1, null, 2]").evaluate(EMPTY).booleanValue());
        assertFalse(FACTORY.compile("null in [1, 2]").evaluate(EMPTY).booleanValue());
    }
}
