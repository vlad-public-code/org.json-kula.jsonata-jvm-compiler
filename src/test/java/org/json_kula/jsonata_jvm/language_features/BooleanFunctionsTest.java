package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata boolean functions:
 *   $boolean, $not, $exists
 *
 * Spec: https://docs.jsonata.org/boolean-functions
 */
class BooleanFunctionsTest {

    private JsonNode eval(String expr) throws Exception {
        return JsonNodeTestHelper.evaluate(expr);
    }

    private JsonNode eval(String expr, String json) throws Exception {
        return JsonNodeTestHelper.evaluate(expr, json);
    }

    // =========================================================================
    // $boolean — casting rules
    // =========================================================================

    // boolean input
    @Test
    void boolean_true_unchanged() throws Exception {
        assertTrue(eval("$boolean(true)").booleanValue());
    }

    @Test
    void boolean_false_unchanged() throws Exception {
        assertFalse(eval("$boolean(false)").booleanValue());
    }

    // string input
    @Test
    void boolean_non_empty_string_is_true() throws Exception {
        assertTrue(eval("$boolean(\"hello\")").booleanValue());
    }

    @Test
    void boolean_empty_string_is_false() throws Exception {
        assertFalse(eval("$boolean(\"\")").booleanValue());
    }

    // number input
    @Test
    void boolean_nonzero_number_is_true() throws Exception {
        assertTrue(eval("$boolean(1)").booleanValue());
        assertTrue(eval("$boolean(-1)").booleanValue());
        assertTrue(eval("$boolean(0.1)").booleanValue());
    }

    @Test
    void boolean_zero_is_false() throws Exception {
        assertFalse(eval("$boolean(0)").booleanValue());
    }

    // null input
    @Test
    void boolean_null_is_false() throws Exception {
        assertFalse(eval("$boolean(null)").booleanValue());
    }

    // object input
    @Test
    void boolean_non_empty_object_is_true() throws Exception {
        assertTrue(eval("$boolean({\"a\": 1})").booleanValue());
    }

    @Test
    void boolean_empty_object_is_false() throws Exception {
        assertFalse(eval("$boolean({})").booleanValue());
    }

    // array input — spec rule: at least one truthy member → true; all falsy → false
    @Test
    void boolean_array_with_truthy_member_is_true() throws Exception {
        assertTrue(eval("$boolean([1, 2, 3])").booleanValue());
    }

    @Test
    void boolean_empty_array_is_false() throws Exception {
        assertFalse(eval("$boolean([])").booleanValue());
    }

    @Test
    void boolean_array_all_falsy_members_is_false() throws Exception {
        // [0, false, ""] — all members are falsy → false
        assertFalse(eval("$boolean([0, false, \"\"])").booleanValue());
    }

    @Test
    void boolean_array_mixed_falsy_and_truthy_is_true() throws Exception {
        // [0, 1] — one truthy member → true
        assertTrue(eval("$boolean([0, 1])").booleanValue());
    }

    @Test
    void boolean_array_single_zero_is_false() throws Exception {
        assertFalse(eval("$boolean([0])").booleanValue());
    }

    @Test
    void boolean_array_single_truthy_is_true() throws Exception {
        assertTrue(eval("$boolean([1])").booleanValue());
    }

    // function input
    @Test
    void boolean_function_is_false() throws Exception {
        // Lambda / function value → false per spec
        assertFalse(eval("$boolean(function($x){$x})").booleanValue());
    }

    // missing input
    @Test
    void boolean_missing_returns_null() throws Exception {
        assertTrue(eval("$boolean($notDefined)").isMissingNode());
    }

    // =========================================================================
    // $not — logical negation
    // =========================================================================

    @Test
    void not_true_gives_false() throws Exception {
        assertFalse(eval("$not(true)").booleanValue());
    }

    @Test
    void not_false_gives_true() throws Exception {
        assertTrue(eval("$not(false)").booleanValue());
    }

    @Test
    void not_non_empty_string_gives_false() throws Exception {
        assertFalse(eval("$not(\"hello\")").booleanValue());
    }

    @Test
    void not_empty_string_gives_true() throws Exception {
        assertTrue(eval("$not(\"\")").booleanValue());
    }

    @Test
    void not_nonzero_number_gives_false() throws Exception {
        assertFalse(eval("$not(42)").booleanValue());
    }

    @Test
    void not_zero_gives_true() throws Exception {
        assertTrue(eval("$not(0)").booleanValue());
    }

    @Test
    void not_null_gives_true() throws Exception {
        assertTrue(eval("$not(null)").booleanValue());
    }

    @Test
    void not_non_empty_object_gives_false() throws Exception {
        assertFalse(eval("$not({\"a\":1})").booleanValue());
    }

    @Test
    void not_empty_object_gives_true() throws Exception {
        assertTrue(eval("$not({})").booleanValue());
    }

    @Test
    void not_array_all_falsy_gives_true() throws Exception {
        assertTrue(eval("$not([0, false, \"\"])").booleanValue());
    }

    @Test
    void not_array_with_truthy_member_gives_false() throws Exception {
        assertFalse(eval("$not([0, 1])").booleanValue());
    }

    @Test
    void not_missing_returns_null() throws Exception {
        assertTrue(eval("$not($notDefined)").isMissingNode());
    }

    // =========================================================================
    // $exists — checks whether an expression has a value
    // =========================================================================

    @Test
    void exists_defined_field_is_true() throws Exception {
        String json = "{\"name\": \"Alice\"}";
        assertTrue(eval("$exists(name)", json).booleanValue());
    }

    @Test
    void exists_undefined_field_is_false() throws Exception {
        String json = "{\"name\": \"Alice\"}";
        assertFalse(eval("$exists(age)", json).booleanValue());
    }

    @Test
    void exists_null_value_is_true() throws Exception {
        // null is a defined value — $exists should return true
        String json = "{\"x\": null}";
        assertTrue(eval("$exists(x)", json).booleanValue());
    }

    @Test
    void exists_false_value_is_true() throws Exception {
        // false is a defined value
        String json = "{\"flag\": false}";
        assertTrue(eval("$exists(flag)", json).booleanValue());
    }

    @Test
    void exists_zero_value_is_true() throws Exception {
        String json = "{\"count\": 0}";
        assertTrue(eval("$exists(count)", json).booleanValue());
    }

    @Test
    void exists_empty_string_is_true() throws Exception {
        String json = "{\"s\": \"\"}";
        assertTrue(eval("$exists(s)", json).booleanValue());
    }

    @Test
    void exists_empty_array_is_true() throws Exception {
        String json = "{\"arr\": []}";
        assertTrue(eval("$exists(arr)", json).booleanValue());
    }

    @Test
    void exists_nested_field_present() throws Exception {
        String json = "{\"a\": {\"b\": 1}}";
        assertTrue(eval("$exists(a.b)", json).booleanValue());
    }

    @Test
    void exists_nested_field_absent() throws Exception {
        String json = "{\"a\": {\"b\": 1}}";
        assertFalse(eval("$exists(a.c)", json).booleanValue());
    }

    @Test
    void exists_literal_value_is_true() throws Exception {
        // Literal expressions always produce a value
        assertTrue(eval("$exists(42)").booleanValue());
        assertTrue(eval("$exists(\"hello\")").booleanValue());
        assertTrue(eval("$exists(true)").booleanValue());
        assertTrue(eval("$exists(null)").booleanValue());
    }

    @Test
    void exists_bound_variable_is_true() throws Exception {
        assertTrue(eval("($x := 5; $exists($x))").booleanValue());
    }

    @Test
    void exists_unbound_variable_is_false() throws Exception {
        assertFalse(eval("$exists($notDefined)").booleanValue());
    }

    // =========================================================================
    // Integration: boolean coercion in conditional / and / or
    // =========================================================================

    @Test
    void all_falsy_array_in_conditional_takes_else_branch() throws Exception {
        // [0, false, ""] is falsy → else branch
        assertEquals("no", eval("[0, false, \"\"] ? \"yes\" : \"no\"").textValue());
    }

    @Test
    void mixed_array_in_conditional_takes_then_branch() throws Exception {
        // [0, 1] has a truthy member → then branch
        assertEquals("yes", eval("[0, 1] ? \"yes\" : \"no\"").textValue());
    }

    @Test
    void all_falsy_array_and_true_is_false() throws Exception {
        assertFalse(eval("[0, false, \"\"] and true").booleanValue());
    }

    @Test
    void mixed_array_or_false_is_true() throws Exception {
        assertTrue(eval("[0, 1] or false").booleanValue());
    }
}
