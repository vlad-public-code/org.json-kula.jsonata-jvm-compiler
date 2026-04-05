package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata aggregation functions:
 *   $sum, $max, $min, $average
 *
 * Spec: https://docs.jsonata.org/aggregation-functions
 */
class AggregationFunctionsTest {

    private JsonNode eval(String expr) throws Exception {
        return JsonNodeTestHelper.evaluate(expr);
    }

    private JsonNode eval(String expr, String json) throws Exception {
        return JsonNodeTestHelper.evaluate(expr, json);
    }

    // =========================================================================
    // $sum
    // =========================================================================

    @Test
    void sum_spec_example() throws Exception {
        assertEquals(20L, eval("$sum([5,1,3,7,4])").longValue());
    }

    @Test
    void sum_single_element() throws Exception {
        assertEquals(7L, eval("$sum([7])").longValue());
    }

    @Test
    void sum_single_scalar() throws Exception {
        // scalar is treated as a single-element sequence
        assertEquals(42L, eval("$sum(42)").longValue());
    }

    @Test
    void sum_empty_array_returns_zero() throws Exception {
        assertEquals(0L, eval("$sum([])").longValue());
    }

    @Test
    void sum_missing_returns_zero() throws Exception {
        // undefined argument → 0 (same as empty sequence)
        assertEquals(0L, eval("$sum($notDefined)").longValue());
    }

    @Test
    void sum_floats() throws Exception {
        assertEquals(1.5, eval("$sum([0.1, 0.4, 1.0])").doubleValue(), 1e-10);
    }

    @Test
    void sum_negative_numbers() throws Exception {
        assertEquals(-5L, eval("$sum([-3, -2])").longValue());
    }

    @Test
    void sum_mixed_positive_negative() throws Exception {
        assertEquals(0L, eval("$sum([5, -3, -2])").longValue());
    }

    @Test
    void sum_from_doc_path() throws Exception {
        String json = "{\"items\": [{\"price\": 10}, {\"price\": 25}, {\"price\": 5}]}";
        assertEquals(40L, eval("$sum(items.price)", json).longValue());
    }

    @Test
    void sum_string_element_throws() {
        // Non-numeric element must throw
        assertThrows(Exception.class, () -> eval("$sum([1, \"2\", 3])"));
    }

    @Test
    void sum_boolean_element_throws() {
        assertThrows(Exception.class, () -> eval("$sum([1, true, 3])"));
    }

    @Test
    void sum_null_element_throws() {
        assertThrows(Exception.class, () -> eval("$sum([1, null, 3])"));
    }

    // =========================================================================
    // $max
    // =========================================================================

    @Test
    void max_spec_example() throws Exception {
        assertEquals(7L, eval("$max([5,1,3,7,4])").longValue());
    }

    @Test
    void max_single_element() throws Exception {
        assertEquals(42L, eval("$max([42])").longValue());
    }

    @Test
    void max_single_scalar() throws Exception {
        assertEquals(9L, eval("$max(9)").longValue());
    }

    @Test
    void max_empty_array_returns_null() throws Exception {
        assertTrue(eval("$max([])").isMissingNode(), "max of empty array should be null");
    }

    @Test
    void max_missing_returns_null() throws Exception {
        assertTrue(eval("$max($notDefined)").isMissingNode());
    }

    @Test
    void max_negative_numbers() throws Exception {
        assertEquals(-1L, eval("$max([-5, -1, -3])").longValue());
    }

    @Test
    void max_floats() throws Exception {
        assertEquals(3.14, eval("$max([1.5, 3.14, 2.72])").doubleValue(), 1e-10);
    }

    @Test
    void max_from_doc_path() throws Exception {
        String json = "{\"items\": [{\"v\": 10}, {\"v\": 99}, {\"v\": 5}]}";
        assertEquals(99L, eval("$max(items.v)", json).longValue());
    }

    @Test
    void max_string_element_throws() {
        assertThrows(Exception.class, () -> eval("$max([1, \"high\", 3])"));
    }

    @Test
    void max_boolean_element_throws() {
        assertThrows(Exception.class, () -> eval("$max([1, true])"));
    }

    // =========================================================================
    // $min
    // =========================================================================

    @Test
    void min_spec_example() throws Exception {
        assertEquals(1L, eval("$min([5,1,3,7,4])").longValue());
    }

    @Test
    void min_single_element() throws Exception {
        assertEquals(42L, eval("$min([42])").longValue());
    }

    @Test
    void min_single_scalar() throws Exception {
        assertEquals(3L, eval("$min(3)").longValue());
    }

    @Test
    void min_empty_array_returns_null() throws Exception {
        assertTrue(eval("$min([])").isMissingNode(), "min of empty array should be null");
    }

    @Test
    void min_missing_returns_null() throws Exception {
        assertTrue(eval("$min($notDefined)").isMissingNode());
    }

    @Test
    void min_negative_numbers() throws Exception {
        assertEquals(-5L, eval("$min([-5, -1, -3])").longValue());
    }

    @Test
    void min_floats() throws Exception {
        assertEquals(1.5, eval("$min([1.5, 3.14, 2.72])").doubleValue(), 1e-10);
    }

    @Test
    void min_from_doc_path() throws Exception {
        String json = "{\"items\": [{\"v\": 10}, {\"v\": 99}, {\"v\": 5}]}";
        assertEquals(5L, eval("$min(items.v)", json).longValue());
    }

    @Test
    void min_string_element_throws() {
        assertThrows(Exception.class, () -> eval("$min([\"low\", 1, 3])"));
    }

    @Test
    void min_null_element_throws() {
        assertThrows(Exception.class, () -> eval("$min([1, null])"));
    }

    // =========================================================================
    // $average
    // =========================================================================

    @Test
    void average_spec_example() throws Exception {
        assertEquals(4.0, eval("$average([5,1,3,7,4])").doubleValue(), 1e-10);
    }

    @Test
    void average_single_element() throws Exception {
        assertEquals(10.0, eval("$average([10])").doubleValue(), 1e-10);
    }

    @Test
    void average_single_scalar() throws Exception {
        assertEquals(7.0, eval("$average(7)").doubleValue(), 1e-10);
    }

    @Test
    void average_empty_array_returns_null() throws Exception {
        assertTrue(eval("$average([])").isMissingNode(), "average of empty array should be null");
    }

    @Test
    void average_missing_returns_null() throws Exception {
        assertTrue(eval("$average($notDefined)").isMissingNode());
    }

    @Test
    void average_two_elements() throws Exception {
        assertEquals(5.0, eval("$average([3, 7])").doubleValue(), 1e-10);
    }

    @Test
    void average_floats() throws Exception {
        assertEquals(2.0, eval("$average([1.0, 2.0, 3.0])").doubleValue(), 1e-10);
    }

    @Test
    void average_from_doc_path() throws Exception {
        String json = "{\"scores\": [{\"v\": 80}, {\"v\": 90}, {\"v\": 100}]}";
        assertEquals(90.0, eval("$average(scores.v)", json).doubleValue(), 1e-10);
    }

    @Test
    void average_string_element_throws() {
        assertThrows(Exception.class, () -> eval("$average([1, \"two\", 3])"));
    }

    @Test
    void average_boolean_element_throws() {
        assertThrows(Exception.class, () -> eval("$average([1, false, 3])"));
    }

    // =========================================================================
    // Cross-function: all four on same sequence
    // =========================================================================

    @Test
    void all_four_on_same_data() throws Exception {
        String json = "{\"nums\": [5, 1, 3, 7, 4]}";
        assertEquals(20L, eval("$sum(nums)",     json).longValue());
        assertEquals(7L,  eval("$max(nums)",     json).longValue());
        assertEquals(1L,  eval("$min(nums)",     json).longValue());
        assertEquals(4.0, eval("$average(nums)", json).doubleValue(), 1e-10);
    }

    @Test
    void aggregation_over_path_expression() throws Exception {
        // Sequence mapping over nested path
        String json = "{"
                + "\"Account\": {\"Order\": ["
                + "  {\"Price\": 10, \"Qty\": 3},"
                + "  {\"Price\": 25, \"Qty\": 1}"
                + "]}}";
        // Sum of all Order prices
        assertEquals(35L, eval("$sum(Account.Order.Price)", json).longValue());
        assertEquals(25L, eval("$max(Account.Order.Price)", json).longValue());
        assertEquals(10L, eval("$min(Account.Order.Price)", json).longValue());
        assertEquals(17.5, eval("$average(Account.Order.Price)", json).doubleValue(), 1e-10);
    }
}
