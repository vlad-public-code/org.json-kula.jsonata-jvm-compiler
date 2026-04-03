package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FunctionSignature}: parsing and argument coercion.
 */
class FunctionSignatureTest {

    private static final JsonNode MISSING = MissingNode.getInstance();
    private static final JsonNode NULL    = NullNode.getInstance();

    // =========================================================================
    // Null / malformed signatures — pass through unchanged
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {"", "n", "invalid", "<no-close", "no-open>"})
    void malformedSignature_argsPassedThrough(String sig) throws Exception {
        List<JsonNode> args = List.of(IntNode.valueOf(1), IntNode.valueOf(2));
        assertEquals(args, FunctionSignature.coerce(sig, args));
    }

    @Test
    void nullSignature_argsPassedThrough() throws Exception {
        List<JsonNode> args = List.of(IntNode.valueOf(42));
        assertEquals(args, FunctionSignature.coerce(null, args));
    }

    // =========================================================================
    // Parsing — parseParams smoke tests
    // =========================================================================

    @Test
    void parseParams_simpleTypes() {
        var p = FunctionSignature.parseParams("<nn:n>");
        assertNotNull(p);
        assertEquals(2, p.size());
        assertEquals("n", p.get(0).type());
        assertEquals("n", p.get(1).type());
        assertFalse(p.get(0).optional());
        assertFalse(p.get(0).variadic());
        assertFalse(p.get(0).focus());
    }

    @Test
    void parseParams_optionalModifier() {
        var p = FunctionSignature.parseParams("<s?:s>");
        assertNotNull(p);
        assertEquals(1, p.size());
        assertTrue(p.get(0).optional());
    }

    @Test
    void parseParams_variadicModifier() {
        var p = FunctionSignature.parseParams("<a+:a>");
        assertNotNull(p);
        assertEquals(1, p.size());
        assertTrue(p.get(0).variadic());
    }

    @Test
    void parseParams_focusModifier() {
        var p = FunctionSignature.parseParams("<s-:n>");
        assertNotNull(p);
        assertEquals(1, p.size());
        assertTrue(p.get(0).focus());
    }

    @Test
    void parseParams_unionType() {
        var p = FunctionSignature.parseParams("<(sao):s>");
        assertNotNull(p);
        assertEquals(1, p.size());
        assertEquals("(sao)", p.get(0).type());
    }

    @Test
    void parseParams_parametrisedArray() {
        var p = FunctionSignature.parseParams("<a<s>s?:s>");
        assertNotNull(p);
        assertEquals(2, p.size());
        assertEquals("a<s>", p.get(0).type());
        assertEquals("s",    p.get(1).type());
        assertTrue(p.get(1).optional());
    }

    @Test
    void parseParams_noReturnType() {
        var p = FunctionSignature.parseParams("<nn>");
        assertNotNull(p);
        assertEquals(2, p.size());
    }

    @Test
    void parseParams_emptyParams() {
        var p = FunctionSignature.parseParams("<:n>");
        assertNotNull(p);
        assertTrue(p.isEmpty());
    }

    // =========================================================================
    // Coercion — type n (number)
    // =========================================================================

    @Test
    void coerce_n_numberPassthrough() throws Exception {
        var result = FunctionSignature.coerce("<n:n>", List.of(DoubleNode.valueOf(3.14)));
        assertEquals(3.14, result.get(0).doubleValue(), 1e-9);
    }

    @Test
    void coerce_n_stringCoercedToNumber() throws Exception {
        var result = FunctionSignature.coerce("<n:n>", List.of(TextNode.valueOf("42")));
        assertEquals(42.0, result.get(0).doubleValue(), 1e-9);
    }

    @Test
    void coerce_n_boolTrueBecomesOne() throws Exception {
        var result = FunctionSignature.coerce("<n:n>", List.of(BooleanNode.TRUE));
        assertEquals(1.0, result.get(0).doubleValue(), 1e-9);
    }

    @Test
    void coerce_n_boolFalseBecomesZero() throws Exception {
        var result = FunctionSignature.coerce("<n:n>", List.of(BooleanNode.FALSE));
        assertEquals(0.0, result.get(0).doubleValue(), 1e-9);
    }

    @Test
    void coerce_n_nonNumericStringThrows() {
        assertThrows(JsonataEvaluationException.class,
                () -> FunctionSignature.coerce("<n:n>", List.of(TextNode.valueOf("hello"))));
    }

    // =========================================================================
    // Coercion — type s (string)
    // =========================================================================

    @Test
    void coerce_s_stringPassthrough() throws Exception {
        var result = FunctionSignature.coerce("<s:s>", List.of(TextNode.valueOf("hi")));
        assertEquals("hi", result.get(0).textValue());
    }

    @Test
    void coerce_s_numberCoercedToString() throws Exception {
        var result = FunctionSignature.coerce("<s:s>", List.of(LongNode.valueOf(7)));
        assertEquals("7", result.get(0).textValue());
    }

    @Test
    void coerce_s_boolCoercedToString() throws Exception {
        var result = FunctionSignature.coerce("<s:s>", List.of(BooleanNode.TRUE));
        assertEquals("true", result.get(0).textValue());
    }

    // =========================================================================
    // Coercion — type b (boolean)
    // =========================================================================

    @Test
    void coerce_b_truthyValueBecomesTrue() throws Exception {
        var result = FunctionSignature.coerce("<b:b>", List.of(LongNode.valueOf(1)));
        assertTrue(result.get(0).booleanValue());
    }

    @Test
    void coerce_b_emptyStringBecomesFalse() throws Exception {
        var result = FunctionSignature.coerce("<b:b>", List.of(TextNode.valueOf("")));
        assertFalse(result.get(0).booleanValue());
    }

    // =========================================================================
    // Coercion — type l (null)
    // =========================================================================

    @Test
    void coerce_l_nullPassthrough() throws Exception {
        var result = FunctionSignature.coerce("<l:l>", List.of(NULL));
        assertTrue(result.get(0).isNull());
    }

    @Test
    void coerce_l_nonNullThrows() {
        assertThrows(JsonataEvaluationException.class,
                () -> FunctionSignature.coerce("<l:l>", List.of(TextNode.valueOf("x"))));
    }

    // =========================================================================
    // Coercion — type a (array)
    // =========================================================================

    @Test
    void coerce_a_arrayPassthrough() throws Exception {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode().add(1).add(2);
        var result = FunctionSignature.coerce("<a:a>", List.of(arr));
        assertTrue(result.get(0).isArray());
        assertEquals(2, result.get(0).size());
    }

    @Test
    void coerce_a_scalarWrappedInArray() throws Exception {
        var result = FunctionSignature.coerce("<a:a>", List.of(LongNode.valueOf(5)));
        assertTrue(result.get(0).isArray());
        assertEquals(1, result.get(0).size());
        assertEquals(5, result.get(0).get(0).intValue());
    }

    // =========================================================================
    // Coercion — type o (object)
    // =========================================================================

    @Test
    void coerce_o_objectPassthrough() throws Exception {
        ObjectNode obj = JsonNodeFactory.instance.objectNode().put("k", "v");
        var result = FunctionSignature.coerce("<o:o>", List.of(obj));
        assertTrue(result.get(0).isObject());
    }

    @Test
    void coerce_o_nonObjectThrows() {
        assertThrows(JsonataEvaluationException.class,
                () -> FunctionSignature.coerce("<o:o>", List.of(TextNode.valueOf("x"))));
    }

    // =========================================================================
    // Coercion — types j and u (any / primitive union)
    // =========================================================================

    @Test
    void coerce_j_acceptsAnyWithoutCoercion() throws Exception {
        JsonNode arr = JsonNodeFactory.instance.arrayNode().add(1);
        var result = FunctionSignature.coerce("<j:j>", List.of(arr));
        assertSame(arr, result.get(0));
    }

    @Test
    void coerce_u_acceptsPrimitiveWithoutCoercion() throws Exception {
        var result = FunctionSignature.coerce("<u:u>", List.of(TextNode.valueOf("hi")));
        assertEquals("hi", result.get(0).textValue());
    }

    // =========================================================================
    // Arity — required, optional, focus, variadic
    // =========================================================================

    @Test
    void coerce_missingRequiredArg_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FunctionSignature.coerce("<n:n>", List.of()));
    }

    @Test
    void coerce_optionalArgMissing_passesMissing() throws Exception {
        var result = FunctionSignature.coerce("<s?:s>", List.of());
        assertEquals(1, result.size());
        assertTrue(result.get(0).isMissingNode());
    }

    @Test
    void coerce_focusArgMissing_passesMissing() throws Exception {
        var result = FunctionSignature.coerce("<s-:n>", List.of());
        assertEquals(1, result.size());
        assertTrue(result.get(0).isMissingNode());
    }

    @Test
    void coerce_variadicOneArg_accepted() throws Exception {
        var result = FunctionSignature.coerce("<a+:a>",
                List.of(JsonNodeFactory.instance.arrayNode()));
        assertEquals(1, result.size());
        assertTrue(result.get(0).isArray());
    }

    @Test
    void coerce_variadicThreeArgs_allCoerced() throws Exception {
        var arr = JsonNodeFactory.instance.arrayNode();
        var result = FunctionSignature.coerce("<a+:a>", List.of(arr, arr, arr));
        assertEquals(3, result.size());
        result.forEach(n -> assertTrue(n.isArray()));
    }

    @Test
    void coerce_variadicNoArgs_throws() {
        assertThrows(JsonataEvaluationException.class,
                () -> FunctionSignature.coerce("<a+:a>", List.of()));
    }

    @Test
    void coerce_emptyParamSignature_allArgsIgnored() throws Exception {
        // <:n> — no params, the supplied args are not validated
        var result = FunctionSignature.coerce("<:n>", List.of(IntNode.valueOf(1)));
        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // Integration — coercion end-to-end through callBoundFunction
    // =========================================================================

    @Test
    void integration_boundFunction_argsCoercedBeforeApply() throws Exception {
        // Signature <n:n> — supply a numeric string "7"; expect it to arrive as 7.0
        org.json_kula.jsonata_jvm.JsonataExpressionFactory factory =
                new org.json_kula.jsonata_jvm.JsonataExpressionFactory();
        org.json_kula.jsonata_jvm.JsonataExpression expr = factory.compile("$double(val)");

        org.json_kula.jsonata_jvm.JsonataBindings b =
                new org.json_kula.jsonata_jvm.JsonataBindings()
                .bindFunction("double", new org.json_kula.jsonata_jvm.JsonataBoundFunction() {
                    @Override public String getFunctionSignature() { return "<n:n>"; }
                    @Override public JsonNode apply(
                            org.json_kula.jsonata_jvm.JsonataFunctionArguments args) {
                        // The argument was declared as a string in the JSON ("7") but the
                        // signature coerces it to a number before apply() is called.
                        assertTrue(args.get(0).isNumber(),
                                "Expected number after coercion, got " + args.get(0).getNodeType());
                        return DoubleNode.valueOf(args.get(0).doubleValue() * 2);
                    }
                });

        JsonNode result = expr.evaluate(JsonNodeTestHelper.parseJson("{\"val\": \"7\"}"), b);
        assertEquals(14.0, result.doubleValue(), 1e-9);
    }

    @Test
    void integration_boundFunction_missingRequiredArg_throwsEvaluationException() throws Exception {
        org.json_kula.jsonata_jvm.JsonataExpressionFactory factory =
                new org.json_kula.jsonata_jvm.JsonataExpressionFactory();
        // Expression supplies no args to $fn
        org.json_kula.jsonata_jvm.JsonataExpression expr = factory.compile("$fn()");
        org.json_kula.jsonata_jvm.JsonataBindings b =
                new org.json_kula.jsonata_jvm.JsonataBindings()
                .bindFunction("fn", new org.json_kula.jsonata_jvm.JsonataBoundFunction() {
                    @Override public String getFunctionSignature() { return "<n:n>"; }
                    @Override public JsonNode apply(
                            org.json_kula.jsonata_jvm.JsonataFunctionArguments args) {
                        return DoubleNode.valueOf(0);
                    }
                });
        assertThrows(org.json_kula.jsonata_jvm.JsonataEvaluationException.class,
                () -> expr.evaluate(EMPTY_OBJECT, b));
    }
}
