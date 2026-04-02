package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata comparison operators: =, !=, <, >, <=, >=, in.
 *
 * Spec: https://docs.jsonata.org/comparison-operators
 */
class ComparisonOperatorsTest {

    private JsonNode eval(String expr) throws Exception {
        return JsonNodeTestHelper.evaluate(expr);
    }

    private JsonNode eval(String expr, String json) throws Exception {
        return JsonNodeTestHelper.evaluate(expr, json);
    }

    // =========================================================================
    // = (equals)
    // =========================================================================

    @Test
    void eq_numbers_equal() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("1 + 1 = 2").booleanValue());
    }

    @Test
    void eq_numbers_not_equal() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("1 + 1 = 3").booleanValue());
    }

    @Test
    void eq_strings_equal() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("\"Hello\" = \"Hello\"").booleanValue());
    }

    @Test
    void eq_strings_not_equal() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("\"Hello\" = \"World\"").booleanValue());
    }

    @Test
    void eq_booleans_equal() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("true = true").booleanValue());
        assertTrue(JsonNodeTestHelper.evaluate("false = false").booleanValue());
    }

    @Test
    void eq_booleans_not_equal() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("true = false").booleanValue());
    }

    @Test
    void eq_null_null() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("null = null").booleanValue());
    }

    @Test
    void eq_null_number() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("null = 0").booleanValue());
    }

    @Test
    void eq_cross_type_number_string() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("1 = \"1\"").booleanValue());
    }

    @Test
    void eq_deep_arrays_equal() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("[1, 2, 3] = [1, 2, 3]").booleanValue());
    }

    @Test
    void eq_deep_arrays_not_equal() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("[1, 2, 3] = [1, 2, 4]").booleanValue());
    }

    @Test
    void eq_deep_objects_equal() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("{\"a\": 1} = {\"a\": 1}").booleanValue());
    }

    @Test
    void eq_deep_objects_not_equal() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("{\"a\": 1} = {\"a\": 2}").booleanValue());
    }

    @Test
    void eq_missing_returns_false() throws Exception {
        // undefined variable compared with anything → false (not an error)
        JsonNode result = JsonNodeTestHelper.evaluate("$notDefined = 1");
        assertFalse(result.booleanValue());
    }

    @Test
    void eq_both_missing_returns_false() throws Exception {
        JsonNode result = JsonNodeTestHelper.evaluate("$a = $b");
        assertFalse(result.booleanValue());
    }

    @Test
    void eq_integer_float_equal() throws Exception {
        // 1 and 1.0 are numerically equal
        assertTrue(JsonNodeTestHelper.evaluate("1 = 1.0").booleanValue());
    }

    // =========================================================================
    // != (not equals)
    // =========================================================================

    @Test
    void ne_numbers() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("1 + 1 != 3").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("1 + 1 != 2").booleanValue());
    }

    @Test
    void ne_strings() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("\"Hello\" != \"World\"").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("\"Hello\" != \"Hello\"").booleanValue());
    }

    @Test
    void ne_missing_returns_false() throws Exception {
        // MISSING != anything → false (undefined propagation)
        JsonNode result = JsonNodeTestHelper.evaluate("$notDefined != 1");
        assertFalse(result.booleanValue());
    }

    @Test
    void ne_null_null() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("null != null").booleanValue());
    }

    @Test
    void ne_cross_type() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("1 != \"1\"").booleanValue());
    }

    // =========================================================================
    // > (greater than)
    // =========================================================================

    @Test
    void gt_numbers_true() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("22 / 7 > 3").booleanValue());
    }

    @Test
    void gt_numbers_equal_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("5 > 5").booleanValue());
    }

    @Test
    void gt_numbers_less_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("3 > 5").booleanValue());
    }

    @Test
    void gt_strings() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("\"b\" > \"a\"").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("\"a\" > \"b\"").booleanValue());
    }

    @Test
    void gt_missing_returns_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$notDefined > 1").booleanValue());
    }

    @Test
    void gt_mismatched_types_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> JsonNodeTestHelper.evaluate("1 > \"a\""));
    }

    // =========================================================================
    // < (less than)
    // =========================================================================

    @Test
    void lt_numbers_true() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("22 / 7 < 3").booleanValue());
    }

    @Test
    void lt_numbers_equal_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("5 < 5").booleanValue());
    }

    @Test
    void lt_numbers_greater_true() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("3 < 5").booleanValue());
    }

    @Test
    void lt_strings() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("\"a\" < \"b\"").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("\"b\" < \"a\"").booleanValue());
    }

    @Test
    void lt_missing_returns_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$notDefined < 1").booleanValue());
    }

    @Test
    void lt_mismatched_types_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> JsonNodeTestHelper.evaluate("\"a\" < 1"));
    }

    // =========================================================================
    // >= (greater than or equals)
    // =========================================================================

    @Test
    void ge_numbers_greater() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("22 / 7 >= 3").booleanValue());
    }

    @Test
    void ge_numbers_equal() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("5 >= 5").booleanValue());
    }

    @Test
    void ge_numbers_less() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("3 >= 5").booleanValue());
    }

    @Test
    void ge_strings() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("\"b\" >= \"b\"").booleanValue());
        assertTrue(JsonNodeTestHelper.evaluate("\"b\" >= \"a\"").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("\"a\" >= \"b\"").booleanValue());
    }

    @Test
    void ge_missing_returns_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$notDefined >= 1").booleanValue());
    }

    // =========================================================================
    // <= (less than or equals)
    // =========================================================================

    @Test
    void le_numbers_less() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("22 / 7 <= 3").booleanValue());
    }

    @Test
    void le_numbers_equal() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("5 <= 5").booleanValue());
    }

    @Test
    void le_numbers_greater() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("3 <= 5").booleanValue());
    }

    @Test
    void le_strings() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("\"a\" <= \"a\"").booleanValue());
        assertTrue(JsonNodeTestHelper.evaluate("\"a\" <= \"b\"").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("\"b\" <= \"a\"").booleanValue());
    }

    @Test
    void le_missing_returns_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$notDefined <= 1").booleanValue());
    }

    // =========================================================================
    // in (inclusion)
    // =========================================================================

    @Test
    void in_value_present() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("\"world\" in [\"hello\", \"world\"]").booleanValue());
    }

    @Test
    void in_value_absent() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("\"foo\" in [\"hello\", \"world\"]").booleanValue());
    }

    @Test
    void in_number_in_array() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("2 in [1, 2, 3]").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("5 in [1, 2, 3]").booleanValue());
    }

    @Test
    void in_rhs_single_value_treated_as_singleton() throws Exception {
        // Single value on RHS treated as singleton array
        assertTrue(JsonNodeTestHelper.evaluate("42 in 42").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("42 in 43").booleanValue());
    }

    @Test
    void in_missing_returns_false() throws Exception {
        assertFalse(JsonNodeTestHelper.evaluate("$notDefined in [1, 2, 3]").booleanValue());
    }

    @Test
    void in_from_doc_field() throws Exception {
        String json = "{\"colors\": [\"red\", \"green\", \"blue\"]}";
        assertTrue(JsonNodeTestHelper.evaluate("\"red\" in colors", json).booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("\"purple\" in colors", json).booleanValue());
    }

    @Test
    void in_with_boolean() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("true in [false, true]").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("true in [false, false]").booleanValue());
    }

    @Test
    void in_with_null() throws Exception {
        assertTrue(JsonNodeTestHelper.evaluate("null in [1, null, 2]").booleanValue());
        assertFalse(JsonNodeTestHelper.evaluate("null in [1, 2]").booleanValue());
    }
}
