package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata object functions:
 *   $keys, $values, $lookup, $spread, $merge, $each, $sift, $type, $assert, $error
 *
 * Spec: https://docs.jsonata.org/object-functions
 */
class ObjectFunctionsTest {

    private JsonNode eval(String expr) throws Exception {
        return JsonNodeTestHelper.evaluate(expr);
    }

    private JsonNode eval(String expr, String json) throws Exception {
        return JsonNodeTestHelper.evaluate(expr, json);
    }

    // =========================================================================
    // $keys
    // =========================================================================

    @Test
    void keys_basic() throws Exception {
        String json = "{\"a\": 1, \"b\": 2, \"c\": 3}";
        JsonNode result = eval("$keys($)", json);
        assertTrue(result.isArray());
        assertEquals(3, result.size());
    }

    @Test
    void keys_empty_object() throws Exception {
        JsonNode result = eval("$keys({})");
        assertTrue(result.isNull(), "keys of empty object should be null/missing");
    }

    @Test
    void keys_missing_returns_null() throws Exception {
        assertTrue(eval("$keys($notDefined)").isNull());
    }

    @Test
    void keys_contains_expected_keys() throws Exception {
        String json = "{\"name\": \"Alice\", \"age\": 30}";
        JsonNode result = eval("$keys($)", json);
        boolean hasName = false, hasAge = false;
        for (JsonNode k : result) {
            if ("name".equals(k.textValue())) hasName = true;
            if ("age".equals(k.textValue())) hasAge = true;
        }
        assertTrue(hasName && hasAge);
    }

    // =========================================================================
    // $values
    // =========================================================================

    @Test
    void values_basic() throws Exception {
        String json = "{\"a\": 1, \"b\": 2}";
        JsonNode result = eval("$values($)", json);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
    }

    @Test
    void values_empty_object() throws Exception {
        JsonNode result = eval("$values({})");
        assertTrue(result.isNull(), "values of empty object should be null/missing");
    }

    @Test
    void values_missing_returns_null() throws Exception {
        assertTrue(eval("$values($notDefined)").isNull());
    }

    @Test
    void values_sum_matches() throws Exception {
        // sum of values should equal sum computed directly
        String json = "{\"x\": 10, \"y\": 20, \"z\": 30}";
        assertEquals(60L, eval("$sum($values($))", json).longValue());
    }

    // =========================================================================
    // $lookup
    // =========================================================================

    @Test
    void lookup_spec_example() throws Exception {
        String json = "{\"name\": \"Alice\", \"city\": \"London\"}";
        assertEquals("Alice", eval("$lookup($, \"name\")", json).textValue());
    }

    @Test
    void lookup_missing_key_returns_null() throws Exception {
        String json = "{\"name\": \"Alice\"}";
        assertTrue(eval("$lookup($, \"age\")", json).isNull());
    }

    @Test
    void lookup_array_of_objects() throws Exception {
        // When obj is an array, returns all matching values
        String json = "[{\"n\": 1}, {\"n\": 2}, {\"n\": 3}]";
        JsonNode result = eval("$lookup($, \"n\")", json);
        assertTrue(result.isArray());
        assertEquals(3, result.size());
        assertEquals(1L, result.get(0).longValue());
        assertEquals(3L, result.get(2).longValue());
    }

    @Test
    void lookup_array_some_missing_key() throws Exception {
        String json = "[{\"n\": 1}, {\"m\": 2}, {\"n\": 3}]";
        JsonNode result = eval("$lookup($, \"n\")", json);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
    }

    @Test
    void lookup_array_single_match_unwrapped() throws Exception {
        String json = "[{\"n\": 42}, {\"m\": 99}]";
        JsonNode result = eval("$lookup($, \"n\")", json);
        // single match → unwrapped scalar
        assertEquals(42L, result.longValue());
    }

    @Test
    void lookup_null_value_returned() throws Exception {
        String json = "{\"x\": null}";
        assertTrue(eval("$lookup($, \"x\")", json).isNull());
    }

    // =========================================================================
    // $spread
    // =========================================================================

    @Test
    void spread_spec_example() throws Exception {
        String json = "{\"a\": 1, \"b\": 2}";
        JsonNode result = eval("$spread($)", json);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        // each element is a single-key object
        for (JsonNode elem : result) {
            assertTrue(elem.isObject());
            assertEquals(1, elem.size());
        }
    }

    @Test
    void spread_preserves_values() throws Exception {
        String json = "{\"name\": \"Alice\", \"age\": 30}";
        JsonNode result = eval("$spread($)", json);
        boolean foundName = false, foundAge = false;
        for (JsonNode obj : result) {
            if (obj.has("name") && "Alice".equals(obj.get("name").textValue())) foundName = true;
            if (obj.has("age") && obj.get("age").longValue() == 30L) foundAge = true;
        }
        assertTrue(foundName && foundAge);
    }

    @Test
    void spread_single_key_object() throws Exception {
        JsonNode result = eval("$spread({\"x\": 42})");
        assertTrue(result.isArray());
        assertEquals(1, result.size());
        assertEquals(42L, result.get(0).get("x").longValue());
    }

    @Test
    void spread_missing_returns_null() throws Exception {
        assertTrue(eval("$spread($notDefined)").isNull());
    }

    // =========================================================================
    // $merge
    // =========================================================================

    @Test
    void merge_spec_example() throws Exception {
        JsonNode result = eval("$merge([{\"a\":1},{\"b\":2}])");
        assertTrue(result.isObject());
        assertEquals(1L, result.get("a").longValue());
        assertEquals(2L, result.get("b").longValue());
    }

    @Test
    void merge_later_wins_on_conflict() throws Exception {
        JsonNode result = eval("$merge([{\"a\":1},{\"a\":2}])");
        assertEquals(2L, result.get("a").longValue());
    }

    @Test
    void merge_three_objects() throws Exception {
        JsonNode result = eval("$merge([{\"a\":1},{\"b\":2},{\"c\":3}])");
        assertEquals(3, result.size());
    }

    @Test
    void merge_empty_array_returns_empty_object() throws Exception {
        JsonNode result = eval("$merge([])");
        assertTrue(result.isObject());
        assertEquals(0, result.size());
    }

    @Test
    void merge_missing_returns_null() throws Exception {
        assertTrue(eval("$merge($notDefined)").isNull());
    }

    // =========================================================================
    // $each
    // =========================================================================

    @Test
    void each_spec_example() throws Exception {
        String json = "{\"a\": 1, \"b\": 2, \"c\": 3}";
        // $each returns array of results
        JsonNode result = eval("$each($, function($v, $k){$k & \": \" & $string($v)})", json);
        assertTrue(result.isArray());
        assertEquals(3, result.size());
    }

    @Test
    void each_value_only_function() throws Exception {
        String json = "{\"x\": 10, \"y\": 20}";
        JsonNode result = eval("$each($, function($v){$v * 2})", json);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
    }

    @Test
    void each_missing_returns_null() throws Exception {
        assertTrue(eval("$each($notDefined, function($v){$v})").isNull());
    }

    // =========================================================================
    // $sift
    // =========================================================================

    @Test
    void sift_keeps_matching_entries() throws Exception {
        String json = "{\"a\": 1, \"b\": 2, \"c\": 3}";
        JsonNode result = eval("$sift($, function($v){$v > 1})", json);
        assertTrue(result.isObject());
        assertFalse(result.has("a"));
        assertTrue(result.has("b"));
        assertTrue(result.has("c"));
    }

    @Test
    void sift_with_key_param() throws Exception {
        String json = "{\"foo\": 1, \"bar\": 2, \"baz\": 3}";
        JsonNode result = eval("$sift($, function($v, $k){$substring($k, 0, 1) = \"b\"})", json);
        assertTrue(result.isObject());
        assertFalse(result.has("foo"));
        assertTrue(result.has("bar"));
        assertTrue(result.has("baz"));
    }

    @Test
    void sift_all_removed_returns_empty_object() throws Exception {
        String json = "{\"a\": 1, \"b\": 2}";
        JsonNode result = eval("$sift($, function($v){$v > 100})", json);
        assertTrue(result.isObject());
        assertEquals(0, result.size());
    }

    // =========================================================================
    // $type
    // =========================================================================

    @Test
    void type_null() throws Exception {
        assertEquals("null", eval("$type(null)").textValue());
    }

    @Test
    void type_number() throws Exception {
        assertEquals("number", eval("$type(42)").textValue());
        assertEquals("number", eval("$type(3.14)").textValue());
    }

    @Test
    void type_string() throws Exception {
        assertEquals("string", eval("$type(\"hello\")").textValue());
    }

    @Test
    void type_boolean() throws Exception {
        assertEquals("boolean", eval("$type(true)").textValue());
        assertEquals("boolean", eval("$type(false)").textValue());
    }

    @Test
    void type_array() throws Exception {
        assertEquals("array", eval("$type([1,2,3])").textValue());
    }

    @Test
    void type_object() throws Exception {
        assertEquals("object", eval("$type({\"a\":1})").textValue());
    }

    @Test
    void type_function() throws Exception {
        assertEquals("function", eval("$type(function($x){$x})").textValue());
    }

    @Test
    void type_undefined_returns_null() throws Exception {
        assertTrue(eval("$type($notDefined)").isNull());
    }

    // =========================================================================
    // $assert
    // =========================================================================

    @Test
    void assert_true_condition_returns_undefined() throws Exception {
        // $assert(true) returns undefined (null at evaluate() boundary)
        assertTrue(eval("$assert(true, \"msg\")").isNull());
    }

    @Test
    void assert_false_throws() {
        assertThrows(Exception.class, () -> eval("$assert(false, \"expected failure\")"));
    }

    @Test
    void assert_custom_message_in_exception() {
        Exception ex = assertThrows(Exception.class,
                () -> eval("$assert(1 = 2, \"one does not equal two\")"));
        assertTrue(ex.getMessage().contains("one does not equal two"));
    }

    @Test
    void assert_truthy_value_passes() throws Exception {
        assertTrue(eval("$assert(1, \"msg\")").isNull());
        assertTrue(eval("$assert(\"non-empty\", \"msg\")").isNull());
    }

    @Test
    void assert_falsy_value_throws() {
        assertThrows(Exception.class, () -> eval("$assert(0, \"zero is falsy\")"));
        assertThrows(Exception.class, () -> eval("$assert(\"\", \"empty is falsy\")"));
        assertThrows(Exception.class, () -> eval("$assert(null, \"null is falsy\")"));
    }

    @Test
    void assert_no_message_throws_default() {
        Exception ex = assertThrows(Exception.class, () -> eval("$assert(false)"));
        assertNotNull(ex.getMessage());
        assertFalse(ex.getMessage().isEmpty());
    }

    // =========================================================================
    // $error
    // =========================================================================

    @Test
    void error_throws_with_message() {
        Exception ex = assertThrows(Exception.class, () -> eval("$error(\"something went wrong\")"));
        assertTrue(ex.getMessage().contains("something went wrong"));
    }

    @Test
    void error_no_arg_throws_default() {
        Exception ex = assertThrows(Exception.class, () -> eval("$error()"));
        assertNotNull(ex.getMessage());
    }

    // =========================================================================
    // Integration
    // =========================================================================

    @Test
    void lookup_in_each() throws Exception {
        // Use $each to build a flipped map
        String json = "{\"a\": 1, \"b\": 2}";
        // Each entry: value -> key
        JsonNode result = eval("$each($, function($v, $k){$k})", json);
        assertTrue(result.isArray());
        assertEquals(2, result.size());
    }

    @Test
    void spread_then_merge_roundtrip() throws Exception {
        String json = "{\"a\": 1, \"b\": 2, \"c\": 3}";
        // spread into array of single-key objects, then merge back
        JsonNode result = eval("$merge($spread($))", json);
        assertTrue(result.isObject());
        assertEquals(3, result.size());
        assertEquals(1L, result.get("a").longValue());
    }
}
