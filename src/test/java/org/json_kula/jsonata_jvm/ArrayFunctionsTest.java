package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata array functions:
 *   $count, $append, $sort, $reverse, $shuffle, $distinct, $zip
 *
 * Spec: https://docs.jsonata.org/array-functions
 */
class ArrayFunctionsTest {

    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();
    private static final String EMPTY = "{}";

    private JsonNode eval(String expr) throws Exception {
        return FACTORY.compile(expr).evaluate(EMPTY);
    }

    private JsonNode eval(String expr, String json) throws Exception {
        return FACTORY.compile(expr).evaluate(json);
    }

    // =========================================================================
    // $count
    // =========================================================================

    @Test
    void count_spec_example() throws Exception {
        assertEquals(4L, eval("$count([1,2,3,1])").longValue());
    }

    @Test
    void count_empty_array() throws Exception {
        assertEquals(0L, eval("$count([])").longValue());
    }

    @Test
    void count_single_element() throws Exception {
        assertEquals(1L, eval("$count([1])").longValue());
    }

    @Test
    void count_string_array() throws Exception {
        assertEquals(2L, eval("$count([\"a\", \"b\"])").longValue());
    }

    @Test
    void count_scalar_is_one() throws Exception {
        // scalar treated as single-element sequence
        assertEquals(1L, eval("$count(42)").longValue());
    }

    @Test
    void count_missing_returns_zero() throws Exception {
        assertEquals(0L, eval("$count($notDefined)").longValue());
    }

    @Test
    void count_from_doc_path() throws Exception {
        String json = "{\"items\": [1, 2, 3]}";
        assertEquals(3L, eval("$count(items)", json).longValue());
    }

    // =========================================================================
    // $append
    // =========================================================================

    @Test
    void append_spec_example() throws Exception {
        JsonNode result = eval("$append([1,2,3], [4,5,6])");
        assertTrue(result.isArray());
        assertEquals(6, result.size());
        assertEquals(1L, result.get(0).longValue());
        assertEquals(6L, result.get(5).longValue());
    }

    @Test
    void append_array_and_scalar() throws Exception {
        JsonNode result = eval("$append([1,2], 3)");
        assertTrue(result.isArray());
        assertEquals(3, result.size());
        assertEquals(3L, result.get(2).longValue());
    }

    @Test
    void append_scalar_and_array() throws Exception {
        JsonNode result = eval("$append(1, [2,3])");
        assertTrue(result.isArray());
        assertEquals(3, result.size());
        assertEquals(1L, result.get(0).longValue());
    }

    @Test
    void append_two_scalars() throws Exception {
        JsonNode result = eval("$append(1, 2)");
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).longValue());
        assertEquals(2L, result.get(1).longValue());
    }

    @Test
    void append_empty_arrays() throws Exception {
        JsonNode result = eval("$append([], [])");
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void append_to_empty() throws Exception {
        JsonNode result = eval("$append([], [1,2])");
        assertTrue(result.isArray());
        assertEquals(2, result.size());
    }

    // =========================================================================
    // $sort
    // =========================================================================

    @Test
    void sort_spec_example_numbers() throws Exception {
        JsonNode result = eval("$sort([5,1,3,7,4])");
        assertTrue(result.isArray());
        assertEquals(5, result.size());
        assertEquals(1L, result.get(0).longValue());
        assertEquals(7L, result.get(4).longValue());
    }

    @Test
    void sort_strings() throws Exception {
        JsonNode result = eval("$sort([\"banana\", \"apple\", \"cherry\"])");
        assertEquals("apple",  result.get(0).textValue());
        assertEquals("banana", result.get(1).textValue());
        assertEquals("cherry", result.get(2).textValue());
    }

    @Test
    void sort_already_sorted() throws Exception {
        JsonNode result = eval("$sort([1,2,3])");
        assertEquals(1L, result.get(0).longValue());
        assertEquals(3L, result.get(2).longValue());
    }

    @Test
    void sort_single_element() throws Exception {
        JsonNode result = eval("$sort([42])");
        assertEquals(42L, result.get(0).longValue());
    }

    @Test
    void sort_empty_returns_empty() throws Exception {
        JsonNode result = eval("$sort([])");
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void sort_missing_returns_null() throws Exception {
        assertTrue(eval("$sort($notDefined)").isNull());
    }

    @Test
    void sort_with_key_function() throws Exception {
        String json = "{\"items\": [{\"name\": \"b\"}, {\"name\": \"a\"}, {\"name\": \"c\"}]}";
        JsonNode result = eval("$sort(items, function($v){$v.name})", json);
        assertTrue(result.isArray());
        assertEquals("a", result.get(0).get("name").textValue());
        assertEquals("c", result.get(2).get("name").textValue());
    }

    @Test
    void sort_with_numeric_key_function() throws Exception {
        String json = "{\"items\": [{\"v\": 3}, {\"v\": 1}, {\"v\": 2}]}";
        JsonNode result = eval("$sort(items, function($x){$x.v})", json);
        assertEquals(1L, result.get(0).get("v").longValue());
        assertEquals(3L, result.get(2).get("v").longValue());
    }

    // =========================================================================
    // $reverse
    // =========================================================================

    @Test
    void reverse_spec_example() throws Exception {
        JsonNode result = eval("$reverse([1,2,3,4])");
        assertTrue(result.isArray());
        assertEquals(4L, result.get(0).longValue());
        assertEquals(1L, result.get(3).longValue());
    }

    @Test
    void reverse_single_element() throws Exception {
        JsonNode result = eval("$reverse([42])");
        assertEquals(42L, result.get(0).longValue());
    }

    @Test
    void reverse_empty() throws Exception {
        JsonNode result = eval("$reverse([])");
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void reverse_missing_returns_null() throws Exception {
        assertTrue(eval("$reverse($notDefined)").isNull());
    }

    @Test
    void reverse_strings() throws Exception {
        JsonNode result = eval("$reverse([\"a\",\"b\",\"c\"])");
        assertEquals("c", result.get(0).textValue());
        assertEquals("a", result.get(2).textValue());
    }

    // =========================================================================
    // $shuffle
    // =========================================================================

    @Test
    void shuffle_preserves_length() throws Exception {
        JsonNode result = eval("$shuffle([1,2,3,4,5])");
        assertTrue(result.isArray());
        assertEquals(5, result.size());
    }

    @Test
    void shuffle_preserves_elements() throws Exception {
        JsonNode result = eval("$shuffle([1,2,3,4,5])");
        Set<Long> values = new HashSet<>();
        for (JsonNode n : result) values.add(n.longValue());
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L), values);
    }

    @Test
    void shuffle_empty_returns_empty() throws Exception {
        JsonNode result = eval("$shuffle([])");
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void shuffle_single_element() throws Exception {
        JsonNode result = eval("$shuffle([42])");
        assertTrue(result.isArray());
        assertEquals(1, result.size());
        assertEquals(42L, result.get(0).longValue());
    }

    @Test
    void shuffle_missing_returns_null() throws Exception {
        assertTrue(eval("$shuffle($notDefined)").isNull());
    }

    // =========================================================================
    // $distinct
    // =========================================================================

    @Test
    void distinct_spec_example() throws Exception {
        JsonNode result = eval("$distinct([1,2,3,3,4,3,5])");
        assertTrue(result.isArray());
        assertEquals(5, result.size());
    }

    @Test
    void distinct_no_duplicates() throws Exception {
        JsonNode result = eval("$distinct([1,2,3])");
        assertEquals(3, result.size());
    }

    @Test
    void distinct_all_same() throws Exception {
        JsonNode result = eval("$distinct([5,5,5])");
        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).longValue());
    }

    @Test
    void distinct_strings() throws Exception {
        JsonNode result = eval("$distinct([\"a\",\"b\",\"a\"])");
        assertEquals(2, result.size());
    }

    @Test
    void distinct_empty() throws Exception {
        JsonNode result = eval("$distinct([])");
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void distinct_scalar_returns_scalar() throws Exception {
        JsonNode result = eval("$distinct(42)");
        assertEquals(42L, result.longValue());
    }

    @Test
    void distinct_missing_returns_null() throws Exception {
        assertTrue(eval("$distinct($notDefined)").isNull());
    }

    @Test
    void distinct_preserves_order() throws Exception {
        JsonNode result = eval("$distinct([3,1,2,1,3])");
        assertEquals(3L, result.get(0).longValue());
        assertEquals(1L, result.get(1).longValue());
        assertEquals(2L, result.get(2).longValue());
    }

    // =========================================================================
    // $zip
    // =========================================================================

    @Test
    void zip_spec_example() throws Exception {
        JsonNode result = eval("$zip([1,2,3],[4,5,6])");
        assertTrue(result.isArray());
        assertEquals(3, result.size());
        assertEquals(1L, result.get(0).get(0).longValue());
        assertEquals(4L, result.get(0).get(1).longValue());
        assertEquals(3L, result.get(2).get(0).longValue());
        assertEquals(6L, result.get(2).get(1).longValue());
    }

    @Test
    void zip_unequal_lengths_uses_min() throws Exception {
        JsonNode result = eval("$zip([1,2,3],[4,5])");
        assertTrue(result.isArray());
        assertEquals(2, result.size());
    }

    @Test
    void zip_three_arrays() throws Exception {
        JsonNode result = eval("$zip([1,2],[3,4],[5,6])");
        assertEquals(2, result.size());
        assertEquals(3, result.get(0).size());
        assertEquals(1L, result.get(0).get(0).longValue());
        assertEquals(3L, result.get(0).get(1).longValue());
        assertEquals(5L, result.get(0).get(2).longValue());
    }

    @Test
    void zip_one_empty_gives_empty() throws Exception {
        JsonNode result = eval("$zip([1,2,3],[])");
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void zip_single_array() throws Exception {
        JsonNode result = eval("$zip([1,2,3])");
        assertEquals(3, result.size());
        assertEquals(1, result.get(0).size());
        assertEquals(1L, result.get(0).get(0).longValue());
    }

    @Test
    void zip_from_doc_path() throws Exception {
        String json = "{\"a\": [1,2,3], \"b\": [4,5,6]}";
        JsonNode result = eval("$zip(a, b)", json);
        assertEquals(3, result.size());
        assertEquals(1L, result.get(0).get(0).longValue());
        assertEquals(4L, result.get(0).get(1).longValue());
    }
}
