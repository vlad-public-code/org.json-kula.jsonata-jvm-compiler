package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.parser.ParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonataExpressionFactory}.
 */
class JsonataExpressionFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    private static JsonNode eval(String expr, String json) throws Exception {
        return FACTORY.compile(expr).evaluate(JsonNodeTestHelper.parseJson(json));
    }

    private static JsonNode eval(String expr) throws Exception {
        return FACTORY.compile(expr).evaluate(EMPTY_OBJECT);
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    private static void assertJsonEqual(String expected, JsonNode actual) throws Exception {
        assertEquals(MAPPER.readTree(expected).toString(), actual.toString());
    }

    // =========================================================================
    // Return type and contract
    // =========================================================================

    @Test
    void compile_returnsNonNull() throws Exception {
        assertNotNull(FACTORY.compile("42"));
    }

    @Test
    void compile_returnsJsonataExpression() throws Exception {
        assertTrue(FACTORY.compile("42") instanceof JsonataExpression);
    }

    @Test
    void compile_eachCallReturnsDistinctInstance() throws Exception {
        JsonataExpression a = FACTORY.compile("1");
        JsonataExpression b = FACTORY.compile("1");
        assertNotSame(a, b);
    }

    @Test
    void compile_getSourceJsonata_returnsOriginalExpression() throws Exception {
        String expr = "Account.Order.Product.Price";
        assertEquals(expr, FACTORY.compile(expr).getSourceJsonata());
    }

    @Test
    void compile_getSourceJsonata_complexExpression() throws Exception {
        String expr = "$sum(items.price) * 1.1";
        assertEquals(expr, FACTORY.compile(expr).getSourceJsonata());
    }

    // =========================================================================
    // Literals
    // =========================================================================

    @Test
    void compile_integerLiteral() throws Exception {
        assertEquals(7.0, eval("7").doubleValue(), 1e-9);
    }

    @Test
    void compile_decimalLiteral() throws Exception {
        assertEquals(2.718, eval("2.718").doubleValue(), 1e-9);
    }

    @Test
    void compile_stringLiteral() throws Exception {
        assertEquals(json("\"hello\""), eval("\"hello\""));
    }

    @Test
    void compile_booleanTrue() throws Exception {
        assertTrue(eval("true").booleanValue());
    }

    @Test
    void compile_booleanFalse() throws Exception {
        assertFalse(eval("false").booleanValue());
    }

    @Test
    void compile_nullLiteral() throws Exception {
        assertTrue(eval("null").isNull());
    }

    // =========================================================================
    // Field navigation
    // =========================================================================

    @Test
    void compile_singleField() throws Exception {
        assertEquals(json("\"Alice\""), eval("name", "{\"name\":\"Alice\"}"));
    }

    @Test
    void compile_nestedPath() throws Exception {
        assertEquals(42.0, eval("a.b", "{\"a\":{\"b\":42}}").doubleValue(), 1e-9);
    }

    @Test
    void compile_fieldMappingOverArray() throws Exception {
        JsonNode result = eval("items.price",
                "{\"items\":[{\"price\":10},{\"price\":20},{\"price\":30}]}");
        assertJsonEqual("[10,20,30]", result);
    }

    // =========================================================================
    // Arithmetic
    // =========================================================================

    @ParameterizedTest
    @CsvSource({
        "2 + 3,   5.0",
        "10 - 4,  6.0",
        "3 * 7,   21.0",
        "15 / 4,  3.75",
        "11 % 3,  2.0"
    })
    void compile_arithmeticOp(String expr, double expected) throws Exception {
        assertEquals(expected, eval(expr).doubleValue(), 1e-9);
    }

    @Test
    void compile_nestedArithmetic() throws Exception {
        JsonNode result = eval("(a + b) * c", "{\"a\":2,\"b\":3,\"c\":4}");
        assertEquals(20.0, result.doubleValue(), 1e-9);
    }

    // =========================================================================
    // String concatenation
    // =========================================================================

    @Test
    void compile_stringConcat() throws Exception {
        assertEquals(json("\"hello world\""), eval("\"hello\" & \" world\""));
    }

    @Test
    void compile_stringConcat_withFields() throws Exception {
        JsonNode result = eval("first & \" \" & last",
                "{\"first\":\"John\",\"last\":\"Doe\"}");
        assertEquals(json("\"John Doe\""), result);
    }

    // =========================================================================
    // Comparisons and boolean logic
    // =========================================================================

    @Test
    void compile_lessThan() throws Exception {
        assertTrue(eval("1 < 2").booleanValue());
    }

    @Test
    void compile_greaterThan_false() throws Exception {
        assertFalse(eval("5 > 10").booleanValue());
    }

    @Test
    void compile_equality_withField() throws Exception {
        assertTrue(eval("status = \"active\"", "{\"status\":\"active\"}").booleanValue());
    }

    @Test
    void compile_andLogic() throws Exception {
        assertFalse(eval("true and false").booleanValue());
    }

    @Test
    void compile_orLogic() throws Exception {
        assertTrue(eval("false or true").booleanValue());
    }

    // =========================================================================
    // Conditional
    // =========================================================================

    @Test
    void compile_conditionalTrue() throws Exception {
        assertEquals(json("\"yes\""), eval("true ? \"yes\" : \"no\""));
    }

    @Test
    void compile_conditionalWithField() throws Exception {
        JsonNode result = eval("score >= 60 ? \"pass\" : \"fail\"", "{\"score\":75}");
        assertEquals(json("\"pass\""), result);
    }

    // =========================================================================
    // Variable binding
    // =========================================================================

    @Test
    void compile_variableBinding() throws Exception {
        assertEquals(12.0, eval("($x := 3; $x * 4)").doubleValue(), 1e-9);
    }

    @Test
    void compile_multipleBindings() throws Exception {
        assertEquals(json("\"hello world\""),
                eval("($a := \"hello\"; $b := \" world\"; $a & $b)"));
    }

    // =========================================================================
    // Array and object constructors
    // =========================================================================

    @Test
    void compile_arrayConstructor() throws Exception {
        assertJsonEqual("[1,2,3]", eval("[1, 2, 3]"));
    }

    @Test
    void compile_objectConstructor() throws Exception {
        JsonNode result = eval("{\"sum\": a + b}", "{\"a\":3,\"b\":7}");
        assertEquals(10.0, result.get("sum").doubleValue(), 1e-9);
    }

    @Test
    void compile_rangeExpression() throws Exception {
        assertJsonEqual("[1,2,3,4,5]", eval("[1..5]"));
    }

    // =========================================================================
    // Predicates
    // =========================================================================

    @Test
    void compile_predicateFilter() throws Exception {
        JsonNode result = eval("items[active = true].name",
                "{\"items\":[{\"name\":\"A\",\"active\":true},{\"name\":\"B\",\"active\":false}]}");
        assertEquals(json("\"A\""), result);
    }

    @Test
    void compile_arraySubscript() throws Exception {
        assertEquals(json("\"b\""),
                eval("arr[1]", "{\"arr\":[\"a\",\"b\",\"c\"]}"));
    }

    // =========================================================================
    // Built-in functions
    // =========================================================================

    @Test
    void compile_fn_sum() throws Exception {
        assertEquals(15.0, eval("$sum([1, 2, 3, 4, 5])").doubleValue(), 1e-9);
    }

    @Test
    void compile_fn_count() throws Exception {
        assertEquals(3.0, eval("$count([\"a\",\"b\",\"c\"])").doubleValue(), 1e-9);
    }

    @Test
    void compile_fn_uppercase() throws Exception {
        assertEquals(json("\"WORLD\""), eval("$uppercase(\"world\")"));
    }

    @Test
    void compile_fn_string() throws Exception {
        assertEquals(json("\"3.14\""), eval("$string(3.14)"));
    }

    @Test
    void compile_fn_contains() throws Exception {
        assertTrue(eval("$contains(\"foobar\", \"foo\")").booleanValue());
    }

    @Test
    void compile_fn_join() throws Exception {
        assertEquals(json("\"a,b,c\""), eval("$join([\"a\",\"b\",\"c\"], \",\")"));
    }

    // =========================================================================
    // Higher-order functions
    // =========================================================================

    @Test
    void compile_fn_map() throws Exception {
        JsonNode result = eval("$map([1, 2, 3], function($v){ $v * $v })");
        assertTrue(result.isArray() && result.size() == 3);
        assertEquals(1.0,  result.get(0).doubleValue(), 1e-9);
        assertEquals(4.0,  result.get(1).doubleValue(), 1e-9);
        assertEquals(9.0,  result.get(2).doubleValue(), 1e-9);
    }

    @Test
    void compile_fn_filter() throws Exception {
        JsonNode result = eval("$filter([1, 2, 3, 4, 5, 6], function($v){ $v % 2 = 0 })");
        assertJsonEqual("[2,4,6]", result);
    }

    // =========================================================================
    // Reuse — same factory instance, multiple compiles
    // =========================================================================

    private JsonNode parseJson(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void compile_factoryIsReusable() throws Exception {
        JsonataExpression sum  = FACTORY.compile("a + b");
        JsonataExpression prod = FACTORY.compile("a * b");
        assertEquals(5.0,  sum.evaluate(parseJson("{\"a\":2,\"b\":3}")).doubleValue(), 1e-9);
        assertEquals(6.0, prod.evaluate(parseJson("{\"a\":2,\"b\":3}")).doubleValue(), 1e-9);
    }

    @Test
    void compile_expressionIsReusableAcrossInputs() throws Exception {
        JsonataExpression expr = FACTORY.compile("x * 2");
        assertEquals(4.0,  expr.evaluate(parseJson("{\"x\":2}")).doubleValue(), 1e-9);
        assertEquals(10.0, expr.evaluate(parseJson("{\"x\":5}")).doubleValue(), 1e-9);
        assertEquals(20.0, expr.evaluate(parseJson("{\"x\":10}")).doubleValue(), 1e-9);
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {
        "1 +",          // incomplete expression
        "(1 + 2",       // unclosed parenthesis
        "\"unterminated" // unclosed string
    })
    void compile_invalidSyntax_throwsJsonataCompilationException(String expr) {
        assertThrows(JsonataCompilationException.class, () -> FACTORY.compile(expr));
    }

    @Test
    void compile_invalidSyntax_exceptionCauseIsParseException() {
        JsonataCompilationException ex = assertThrows(
                JsonataCompilationException.class, () -> FACTORY.compile("1 +"));
        assertInstanceOf(ParseException.class, ex.getCause());
    }
}
