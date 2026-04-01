package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata "other operators":
 *   &amp;  (string concatenation)
 *   ? : (conditional / ternary)
 *   ?:  (Elvis / default)
 *   ??  (coalescing)
 *   :=  (variable binding)
 *   ~&gt; (chain)
 *   ~&gt; | … | … |  (transform)
 *
 * Spec: https://docs.jsonata.org/other-operators
 */
class OtherOperatorsTest {

    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();
    private static final String EMPTY = "{}";

    private JsonNode eval(String expr, String json) throws Exception {
        return FACTORY.compile(expr).evaluate(json);
    }

    // =========================================================================
    // & — string concatenation
    // =========================================================================

    @Test
    void concat_two_strings() throws Exception {
        assertEquals("HelloWorld", eval("\"Hello\" & \"World\"", EMPTY).textValue());
    }

    @Test
    void concat_with_number() throws Exception {
        // Non-string operands are cast via $string rules
        assertEquals("Price: 42", eval("\"Price: \" & 42", EMPTY).textValue());
    }

    @Test
    void concat_boolean_cast() throws Exception {
        assertEquals("istrue", eval("\"is\" & true", EMPTY).textValue());
    }

    @Test
    void concat_missing_treated_as_empty() throws Exception {
        // One MISSING operand → treated as empty string
        assertEquals("hello", eval("\"hello\" & $notDefined", EMPTY).textValue());
    }

    @Test
    void concat_from_doc_fields() throws Exception {
        String json = "{\"first\": \"John\", \"last\": \"Doe\"}";
        assertEquals("John Doe", eval("first & \" \" & last", json).textValue());
    }

    // =========================================================================
    // ? : — conditional (ternary)
    // =========================================================================

    @Test
    void conditional_price_cheap() throws Exception {
        String json = "{\"Price\": 30}";
        assertEquals("Cheap", eval("Price < 50 ? \"Cheap\" : \"Expensive\"", json).textValue());
    }

    @Test
    void conditional_price_expensive() throws Exception {
        String json = "{\"Price\": 70}";
        assertEquals("Expensive", eval("Price < 50 ? \"Cheap\" : \"Expensive\"", json).textValue());
    }

    @Test
    void conditional_non_boolean_coercion() throws Exception {
        // Non-zero number is truthy → true branch
        assertEquals("yes", eval("1 ? \"yes\" : \"no\"", EMPTY).textValue());
        // Zero is falsy → false branch
        assertEquals("no", eval("0 ? \"yes\" : \"no\"", EMPTY).textValue());
    }

    @Test
    void conditional_missing_condition_is_falsy() throws Exception {
        // MISSING is falsy → false branch
        assertEquals("no", eval("$notDefined ? \"yes\" : \"no\"", EMPTY).textValue());
    }

    @Test
    void conditional_no_else_returns_null_when_false() throws Exception {
        // When condition is false and no else branch, result is null (MISSING→null)
        JsonNode result = eval("false ? \"yes\"", EMPTY);
        assertTrue(result.isNull(), "Expected null when condition false and no else: " + result);
    }

    @Test
    void conditional_chained_ternary() throws Exception {
        // $x > 0 ? "pos" : $x < 0 ? "neg" : "zero"
        String json = "{\"x\": -5}";
        assertEquals("neg", eval("x > 0 ? \"pos\" : x < 0 ? \"neg\" : \"zero\"", json).textValue());
    }

    // =========================================================================
    // ?: — Elvis / default operator
    // =========================================================================

    @Test
    void elvis_missing_field_returns_default() throws Exception {
        // MISSING field → use default
        String json = "{\"Address\": {\"City\": \"Winchester\"}}";
        assertEquals("Unknown", eval("Address.Country ?: \"Unknown\"", json).textValue());
    }

    @Test
    void elvis_present_field_returns_value() throws Exception {
        String json = "{\"Address\": {\"City\": \"Winchester\"}}";
        assertEquals("Winchester", eval("Address.City ?: \"Unknown\"", json).textValue());
    }

    @Test
    void elvis_null_is_falsy_returns_default() throws Exception {
        // null is falsy → default
        assertEquals("default", eval("null ?: \"default\"", EMPTY).textValue());
    }

    @Test
    void elvis_zero_is_falsy() throws Exception {
        assertEquals("default", eval("0 ?: \"default\"", EMPTY).textValue());
    }

    @Test
    void elvis_empty_string_is_falsy() throws Exception {
        assertEquals("default", eval("\"\" ?: \"default\"", EMPTY).textValue());
    }

    @Test
    void elvis_truthy_value_returned_directly() throws Exception {
        assertEquals("hello", eval("\"hello\" ?: \"default\"", EMPTY).textValue());
        assertEquals(42L, eval("42 ?: 0", EMPTY).longValue());
    }

    // =========================================================================
    // ?? — coalescing operator (defined-check only)
    // =========================================================================

    @Test
    void coalesce_missing_returns_fallback() throws Exception {
        // undefined → fallback
        assertEquals(42L, eval("$notDefined ?? 42", EMPTY).longValue());
    }

    @Test
    void coalesce_defined_value_returned_even_if_falsy() throws Exception {
        // 0 is DEFINED (not MISSING) → returned as-is
        assertEquals(0L, eval("0 ?? 1", EMPTY).longValue());
    }

    @Test
    void coalesce_empty_string_returned() throws Exception {
        // "" is defined → returned
        assertEquals("", eval("\"\" ?? \"fallback\"", EMPTY).textValue());
    }

    @Test
    void coalesce_null_is_defined_returned() throws Exception {
        // null is defined (not MISSING) → returned
        assertTrue(eval("null ?? 42", EMPTY).isNull());
    }

    @Test
    void coalesce_false_is_defined_returned() throws Exception {
        assertFalse(eval("false ?? true", EMPTY).booleanValue());
    }

    @Test
    void coalesce_from_doc_missing_field() throws Exception {
        String json = "{\"foo\": {\"bar\": 7}}";
        assertEquals("default", eval("foo.baz ?? \"default\"", json).textValue());
    }

    @Test
    void coalesce_from_doc_present_field() throws Exception {
        String json = "{\"foo\": {\"bar\": 7}}";
        assertEquals(7L, eval("foo.bar ?? 0", json).longValue());
    }

    // =========================================================================
    // := — variable binding
    // =========================================================================

    @Test
    void binding_simple_value() throws Exception {
        assertEquals(5L, eval("($five := 5; $five)", EMPTY).longValue());
    }

    @Test
    void binding_arithmetic_expression() throws Exception {
        assertEquals(25L, eval("($x := 5; $x * $x)", EMPTY).longValue());
    }

    @Test
    void binding_lambda() throws Exception {
        assertEquals(9L, eval("($square := function($n) { $n * $n }; $square(3))", EMPTY).longValue());
    }

    @Test
    void binding_multiple_in_block() throws Exception {
        // Multiple bindings in one block, each separate
        assertEquals(15L, eval("($x := 5; $y := $x * 3; $y)", EMPTY).longValue());
    }

    // =========================================================================
    // ~> — chain operator
    // =========================================================================

    @Test
    void chain_two_functions() throws Exception {
        // "  hello  " ~> $trim() ~> $uppercase() = "HELLO"
        assertEquals("HELLO", eval("\"  hello  \" ~> $trim() ~> $uppercase()", EMPTY).textValue());
    }

    @Test
    void chain_function_composition() throws Exception {
        // Spec example from docs page
        assertEquals("HELLO WORLD",
                eval("($normalize := $uppercase ~> $trim; $normalize(\"   Hello    World   \"))", EMPTY).textValue());
    }

    @Test
    void chain_with_partial_application() throws Exception {
        // ($first5 := $substring(?, 0, 5); "Winchester" ~> $first5) = "Winch"
        assertEquals("Winch",
                eval("($first5 := $substring(?, 0, 5); \"Winchester\" ~> $first5)", EMPTY).textValue());
    }

    @Test
    void chain_three_functions() throws Exception {
        String json = "{\"email\": \"user@example.com\"}";
        // email ~> $substringAfter("@") ~> $substringBefore(".") ~> $uppercase()
        // → "EXAMPLE"
        assertEquals("EXAMPLE",
                eval("email ~> $substringAfter(\"@\") ~> $substringBefore(\".\") ~> $uppercase()", json).textValue());
    }

    // =========================================================================
    // ~> | … | … | — transform operator
    // =========================================================================

    private static final String ORDER_DOC = "{"
            + "\"Account\": {"
            + "  \"Order\": {"
            + "    \"Product\": ["
            + "      {\"Name\": \"Widget\", \"Price\": 10, \"Quantity\": 3},"
            + "      {\"Name\": \"Gadget\", \"Price\": 25, \"Quantity\": 1}"
            + "    ]"
            + "  }"
            + "}"
            + "}";

    @Test
    void transform_update_field_via_chain() throws Exception {
        // $ ~> |Account.Order.Product|{'Price': Price * 1.2}|
        // Prices should be multiplied by 1.2
        JsonNode result = eval("$ ~> |Account.Order.Product|{'Price': Price * 1.2}|", ORDER_DOC);
        assertTrue(result.isObject(), "Expected transformed document");
        JsonNode products = result.at("/Account/Order/Product");
        assertTrue(products.isArray());
        assertEquals(12.0, products.get(0).get("Price").doubleValue(), 1e-9);
        assertEquals(30.0, products.get(1).get("Price").doubleValue(), 1e-9);
        // Other fields unchanged
        assertEquals("Widget", products.get(0).get("Name").textValue());
    }

    @Test
    void transform_does_not_mutate_source() throws Exception {
        // The transform should return a modified COPY — the original doc is unchanged
        String expr = "$ ~> |Account.Order.Product|{'Price': Price * 2}|";
        JsonNode original = FACTORY.compile("Account.Order.Product[0].Price").evaluate(ORDER_DOC);
        FACTORY.compile(expr).evaluate(ORDER_DOC);
        // Re-evaluate original — must still be 10
        assertEquals(10L, original.longValue());
    }

    @Test
    void transform_add_new_field() throws Exception {
        // Add Total = Price * Quantity
        JsonNode result = eval(
                "$ ~> |Account.Order.Product|{'Total': Price * Quantity}|", ORDER_DOC);
        JsonNode products = result.at("/Account/Order/Product");
        assertEquals(30L, products.get(0).get("Total").longValue()); // 10 * 3
        assertEquals(25L, products.get(1).get("Total").longValue()); // 25 * 1
        // Original fields still present
        assertTrue(products.get(0).has("Price"));
    }

    @Test
    void transform_with_delete_fields() throws Exception {
        // Remove Price and Quantity, add Total
        JsonNode result = eval(
                "$ ~> |Account.Order.Product|{'Total': Price * Quantity}, ['Price', 'Quantity']|",
                ORDER_DOC);
        JsonNode products = result.at("/Account/Order/Product");
        // Total added
        assertEquals(30L, products.get(0).get("Total").longValue());
        // Price and Quantity deleted
        assertFalse(products.get(0).has("Price"), "Price should be deleted");
        assertFalse(products.get(0).has("Quantity"), "Quantity should be deleted");
        // Name still present
        assertEquals("Widget", products.get(0).get("Name").textValue());
    }

    @Test
    void transform_standalone_then_chain() throws Exception {
        // The transform as standalone primary used with ~>
        JsonNode result = eval(
                "$ ~> |Account.Order.Product|{'Price': Price * 1.5}|", ORDER_DOC);
        JsonNode products = result.at("/Account/Order/Product");
        assertEquals(15.0, products.get(0).get("Price").doubleValue(), 1e-9);
        assertEquals(37.5, products.get(1).get("Price").doubleValue(), 1e-9);
    }

    @Test
    void transform_missing_source_returns_null() throws Exception {
        JsonNode result = eval("$notDefined ~> |x|{'a': 1}|", EMPTY);
        assertTrue(result.isNull(), "Transform of MISSING source should be null");
    }
}
