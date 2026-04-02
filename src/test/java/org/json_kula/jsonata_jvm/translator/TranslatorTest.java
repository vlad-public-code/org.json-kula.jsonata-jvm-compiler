package org.json_kula.jsonata_jvm.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.loader.JsonataExpressionLoader;
import org.json_kula.jsonata_jvm.optimizer.Optimizer;
import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 50 tests for {@link Translator}.
 *
 * <p>Tests fall into two categories:
 * <ul>
 *   <li><b>Source-structure tests</b> — inspect the generated Java source text
 *       to verify the class skeleton is correct.</li>
 *   <li><b>End-to-end tests</b> — parse → optimize → translate → compile
 *       ({@link JsonataExpressionLoader}) → evaluate; compare result to the
 *       expected JSON value.</li>
 * </ul>
 */
class TranslatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionLoader LOADER = new JsonataExpressionLoader();
    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Translate {@code expr} and return the generated Java source. */
    private static String source(String expr) throws Exception {
        AstNode ast = Optimizer.optimize(Parser.parse(expr));
        return Translator.translate(ast, "test.gen", nextClass());
    }

    /** Full pipeline: parse → optimize → translate → compile → evaluate. */
    private static JsonNode eval(String expr, String json) throws Exception {
        AstNode ast = Optimizer.optimize(Parser.parse(expr));
        String src  = Translator.translate(ast, "test.gen", nextClass());
        JsonataExpression compiled = LOADER.load(src);
        return compiled.evaluate(JsonNodeTestHelper.parseJson(json));
    }

    /**
     * Compile {@code expr} with an explicit source annotation and return the
     * loaded {@link JsonataExpression} so callers can test {@code getSourceJsonata()}.
     */
    private static JsonataExpression compile(String expr) throws Exception {
        AstNode ast = Optimizer.optimize(Parser.parse(expr));
        String src  = Translator.translate(ast, "test.gen", nextClass(), expr);
        return LOADER.load(src);
    }

    /** Convenience: eval where input JSON is an empty object. */
    private static JsonNode eval(String expr) throws Exception {
        return eval(expr, "{}");
    }

    private static String nextClass() {
        return "Expr" + CLASS_COUNTER.incrementAndGet();
    }

    /** Parses {@code json} with Jackson so we can do deep-equals assertions. */
    private static JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    /**
     * Asserts that two {@link JsonNode} values are semantically equal by
     * comparing their JSON string representation. This avoids false failures
     * caused by Jackson using different numeric node types (IntNode vs LongNode
     * vs DoubleNode) for the same numeric value.
     */
    private static void assertJsonEqual(String expected, JsonNode actual) throws Exception {
        JsonNode expectedNode = MAPPER.readTree(expected);
        assertEquals(expectedNode.toString(), actual.toString(),
                "JSON mismatch — expected: " + expected + " but was: " + actual);
    }

    // =========================================================================
    // Source-structure tests
    // =========================================================================

    @Test
    void translate_generatedSource_implementsJsonataExpression() throws Exception {
        String src = source("42");
        assertTrue(src.contains("implements JsonataExpression"),
                "Generated class must implement JsonataExpression");
    }

    @Test
    void translate_generatedSource_hasEvaluateMethod() throws Exception {
        String src = source("42");
        assertTrue(src.contains("public JsonNode evaluate(JsonNode __input)"),
                "Generated class must have evaluate(JsonNode) method");
    }

    @Test
    void translate_generatedSource_hasPackageDeclaration() throws Exception {
        String src = source("42");
        assertTrue(src.startsWith("package test.gen;"),
                "Generated source must start with the supplied package declaration");
    }

    @Test
    void translate_generatedSource_hasStaticImport() throws Exception {
        String src = source("42");
        assertTrue(src.contains("import static org.json_kula.jsonata_jvm.runtime.JsonataRuntime.*;"),
                "Generated source must static-import the runtime");
    }

    @Test
    void translate_fieldRef_usesRuntimeFieldMethod() throws Exception {
        String src = source("Account");
        assertTrue(src.contains("field("),
                "Field reference must generate a 'field(' call");
    }

    // =========================================================================
    // Literals
    // =========================================================================

    @Test
    void eval_stringLiteral() throws Exception {
        assertEquals(json("\"hello\""), eval("\"hello\""));
    }

    @Test
    void eval_integerLiteral() throws Exception {
        assertEquals(42.0, eval("42").doubleValue(), 1e-9);
    }

    @Test
    void eval_decimalLiteral() throws Exception {
        assertEquals(3.14, eval("3.14").doubleValue(), 1e-9);
    }

    @Test
    void eval_booleanTrue() throws Exception {
        assertTrue(eval("true").booleanValue());
    }

    @Test
    void eval_booleanFalse() throws Exception {
        assertFalse(eval("false").booleanValue());
    }

    @Test
    void eval_nullLiteral() throws Exception {
        assertTrue(eval("null").isNull());
    }

    @Test
    void eval_negativeNumber() throws Exception {
        assertEquals(-7.0, eval("-7").doubleValue(), 1e-9);
    }

    // =========================================================================
    // Context and root references
    // =========================================================================

    @Test
    void eval_contextRef_returnsInputDocument() throws Exception {
        assertEquals(json("{\"x\":1}"), eval("$", "{\"x\":1}"));
    }

    @Test
    void eval_rootRef_returnsInputDocument() throws Exception {
        assertEquals(json("{\"y\":2}"), eval("$$", "{\"y\":2}"));
    }

    // =========================================================================
    // Field navigation
    // =========================================================================

    @Test
    void eval_singleField() throws Exception {
        assertEquals(json("\"Alice\""), eval("name", "{\"name\":\"Alice\"}"));
    }

    @Test
    void eval_twoLevelPath() throws Exception {
        assertEquals(42.0, eval("a.b", "{\"a\":{\"b\":42}}").doubleValue(), 1e-9);
    }

    @Test
    void eval_threeLevelPath() throws Exception {
        assertEquals(json("\"deep\""), eval("a.b.c", "{\"a\":{\"b\":{\"c\":\"deep\"}}}"));
    }

    @Test
    void eval_fieldMappingOverArray() throws Exception {
        // JSONata maps field access over arrays automatically
        JsonNode result = eval("items.name",
                "{\"items\":[{\"name\":\"A\"},{\"name\":\"B\"},{\"name\":\"C\"}]}");
        assertEquals(json("[\"A\",\"B\",\"C\"]"), result);
    }

    @Test
    void eval_wildcardStep() throws Exception {
        JsonNode result = eval("*", "{\"a\":1,\"b\":2}");
        assertTrue(result.isArray() || result.isNumber(),
                "Wildcard should return array or single value");
        // Both values must appear
        String txt = result.toString();
        assertTrue(txt.contains("1") && txt.contains("2"));
    }

    // =========================================================================
    // Arithmetic
    // =========================================================================

    @Test
    void eval_addition() throws Exception {
        assertEquals(5.0, eval("2 + 3").doubleValue(), 1e-9);
    }

    @Test
    void eval_subtraction() throws Exception {
        assertEquals(7.0, eval("10 - 3").doubleValue(), 1e-9);
    }

    @Test
    void eval_multiplication() throws Exception {
        assertEquals(20.0, eval("4 * 5").doubleValue(), 1e-9);
    }

    @Test
    void eval_division() throws Exception {
        assertEquals(2.5, eval("10 / 4").doubleValue(), 1e-9);
    }

    @Test
    void eval_modulo() throws Exception {
        assertEquals(1.0, eval("7 % 3").doubleValue(), 1e-9);
    }

    @Test
    void eval_nestedArithmetic_withFieldRefs() throws Exception {
        // price * qty
        JsonNode result = eval("price * qty", "{\"price\":12,\"qty\":3}");
        assertEquals(36.0, result.doubleValue(), 1e-9);
    }

    @Test
    void eval_unaryMinusOnField() throws Exception {
        JsonNode result = eval("-score", "{\"score\":5}");
        assertEquals(-5.0, result.doubleValue(), 1e-9);
    }

    // =========================================================================
    // String concatenation
    // =========================================================================

    @Test
    void eval_stringConcatenation() throws Exception {
        assertEquals(json("\"hello world\""), eval("\"hello\" & \" world\""));
    }

    @Test
    void eval_stringConcatenation_withFields() throws Exception {
        JsonNode result = eval("first & \" \" & last",
                "{\"first\":\"John\",\"last\":\"Doe\"}");
        assertEquals(json("\"John Doe\""), result);
    }

    // =========================================================================
    // Comparisons and boolean logic
    // =========================================================================

    @Test
    void eval_lessThan_true() throws Exception {
        assertTrue(eval("1 < 2").booleanValue());
    }

    @Test
    void eval_greaterThan_false() throws Exception {
        assertFalse(eval("2 > 5").booleanValue());
    }

    @Test
    void eval_equalityComparison_withField() throws Exception {
        JsonNode result = eval("status = \"active\"", "{\"status\":\"active\"}");
        assertTrue(result.booleanValue());
    }

    @Test
    void eval_notEqual_withFields() throws Exception {
        JsonNode result = eval("x != y", "{\"x\":1,\"y\":2}");
        assertTrue(result.booleanValue());
    }

    @Test
    void eval_andExpression() throws Exception {
        assertFalse(eval("true and false").booleanValue());
    }

    @Test
    void eval_orExpression_withFields() throws Exception {
        JsonNode result = eval("x or y", "{\"x\":false,\"y\":true}");
        assertTrue(result.booleanValue());
    }

    @Test
    void eval_inOperator() throws Exception {
        JsonNode result = eval("\"b\" in tags", "{\"tags\":[\"a\",\"b\",\"c\"]}");
        assertTrue(result.booleanValue());
    }

    // =========================================================================
    // Conditional
    // =========================================================================

    @Test
    void eval_conditionalTrue() throws Exception {
        assertEquals(json("\"yes\""), eval("true ? \"yes\" : \"no\""));
    }

    @Test
    void eval_conditionalFalse() throws Exception {
        assertEquals(json("\"no\""), eval("false ? \"yes\" : \"no\""));
    }

    @Test
    void eval_conditionalWithField() throws Exception {
        JsonNode result = eval("score > 90 ? \"A\" : \"B\"", "{\"score\":95}");
        assertEquals(json("\"A\""), result);
    }

    // =========================================================================
    // Variable binding and blocks
    // =========================================================================

    @Test
    void eval_singleVariableBinding() throws Exception {
        JsonNode result = eval("($x := 42; $x)");
        assertEquals(42.0, result.doubleValue(), 1e-9);
    }

    @Test
    void eval_multipleVariableBindings() throws Exception {
        JsonNode result = eval("($a := 3; $b := 4; $a * $b)");
        assertEquals(12.0, result.doubleValue(), 1e-9);
    }

    @Test
    void eval_blockWithFieldAndConcatenation() throws Exception {
        JsonNode result = eval("($n := name; \"Hello \" & $n)", "{\"name\":\"World\"}");
        assertEquals(json("\"Hello World\""), result);
    }

    // =========================================================================
    // Array and object constructors
    // =========================================================================

    @Test
    void eval_arrayConstructorOfLiterals() throws Exception {
        assertJsonEqual("[1,2,3]", eval("[1, 2, 3]"));
    }

    @Test
    void eval_arrayConstructorWithFields() throws Exception {
        JsonNode result = eval("[a, b]", "{\"a\":10,\"b\":20}");
        assertEquals(json("[10,20]"), result);
    }

    @Test
    void eval_objectConstructor() throws Exception {
        JsonNode result = eval("{\"x\": a, \"y\": b}", "{\"a\":1,\"b\":2}");
        assertEquals(json("{\"x\":1,\"y\":2}"), result);
    }

    @Test
    void eval_rangeExpression() throws Exception {
        assertJsonEqual("[1,2,3,4,5]", eval("[1..5]"));
    }

    // =========================================================================
    // Predicates and subscripts
    // =========================================================================

    @Test
    void eval_arraySubscriptByIndex() throws Exception {
        JsonNode result = eval("items[0]", "{\"items\":[\"a\",\"b\",\"c\"]}");
        assertEquals(json("\"a\""), result);
    }

    @Test
    void eval_predicateFilter() throws Exception {
        JsonNode result = eval("items[active]",
                "{\"items\":[{\"name\":\"A\",\"active\":true},{\"name\":\"B\",\"active\":false}]}");
        // Only the first item passes
        assertFalse(result.isArray(), "Single match should not be wrapped in array");
        assertEquals(json("\"A\""), result.get("name"));
    }

    @Test
    void eval_predicateWithComparison() throws Exception {
        JsonNode result = eval("items[price > 10].name",
                "{\"items\":[{\"name\":\"cheap\",\"price\":5},{\"name\":\"expensive\",\"price\":15}]}");
        assertEquals(json("\"expensive\""), result);
    }

    // =========================================================================
    // Built-in functions
    // =========================================================================

    @Test
    void eval_fn_string() throws Exception {
        assertEquals(json("\"42\""), eval("$string(42)"));
    }

    @Test
    void eval_fn_number() throws Exception {
        assertEquals(3.14, eval("$number(\"3.14\")").doubleValue(), 1e-9);
    }

    @Test
    void eval_fn_boolean() throws Exception {
        assertTrue(eval("$boolean(1)").booleanValue());
        assertFalse(eval("$boolean(0)").booleanValue());
    }

    @Test
    void eval_fn_count() throws Exception {
        assertEquals(3.0, eval("$count([1, 2, 3])").doubleValue(), 1e-9);
    }

    @Test
    void eval_fn_sum() throws Exception {
        assertEquals(10.0, eval("$sum([1, 2, 3, 4])").doubleValue(), 1e-9);
    }

    @Test
    void eval_fn_max() throws Exception {
        assertEquals(9.0, eval("$max([3, 1, 9, 4])").doubleValue(), 1e-9);
    }

    @Test
    void eval_fn_uppercase() throws Exception {
        assertEquals(json("\"HELLO\""), eval("$uppercase(\"hello\")"));
    }

    @Test
    void eval_fn_lowercase() throws Exception {
        assertEquals(json("\"world\""), eval("$lowercase(\"WORLD\")"));
    }

    @Test
    void eval_fn_length() throws Exception {
        assertEquals(5.0, eval("$length(\"hello\")").doubleValue(), 1e-9);
    }

    @Test
    void eval_fn_trim() throws Exception {
        assertEquals(json("\"hi\""), eval("$trim(\"  hi  \")"));
    }

    @Test
    void eval_fn_contains() throws Exception {
        assertTrue(eval("$contains(\"hello world\", \"world\")").booleanValue());
        assertFalse(eval("$contains(\"hello world\", \"xyz\")").booleanValue());
    }

    @Test
    void eval_fn_join() throws Exception {
        assertEquals(json("\"a-b-c\""), eval("$join([\"a\",\"b\",\"c\"], \"-\")"));
    }

    // =========================================================================
    // Higher-order functions with inline lambdas
    // =========================================================================

    @Test
    void eval_fn_map_withLambda() throws Exception {
        JsonNode result = eval("$map([1, 2, 3], function($v){ $v * 2 })");
        assertTrue(result.isArray() && result.size() == 3,
                "Expected array of 3 elements but got: " + result);
        assertEquals(2.0,  result.get(0).doubleValue(), 1e-9);
        assertEquals(4.0,  result.get(1).doubleValue(), 1e-9);
        assertEquals(6.0,  result.get(2).doubleValue(), 1e-9);
    }

    @Test
    void eval_fn_filter_withLambda() throws Exception {
        JsonNode result = eval("$filter([1, 2, 3, 4, 5], function($v){ $v > 3 })");
        assertJsonEqual("[4,5]", result);
    }

    // =========================================================================
    // getSourceJsonata
    // =========================================================================

    @Test
    void getSourceJsonata_literalNumber() throws Exception {
        assertEquals("42", compile("42").getSourceJsonata());
    }

    @Test
    void getSourceJsonata_literalString() throws Exception {
        assertEquals("\"hello\"", compile("\"hello\"").getSourceJsonata());
    }

    @Test
    void getSourceJsonata_fieldPath() throws Exception {
        assertEquals("a.b.c", compile("a.b.c").getSourceJsonata());
    }

    @Test
    void getSourceJsonata_arithmeticExpression() throws Exception {
        assertEquals("price * qty", compile("price * qty").getSourceJsonata());
    }

    @Test
    void getSourceJsonata_complexExpression() throws Exception {
        String expr = "$sum(items.price)";
        assertEquals(expr, compile(expr).getSourceJsonata());
    }

    @Test
    void getSourceJsonata_withSpecialCharacters() throws Exception {
        String expr = "\"it's a \\\"test\\\"\"";
        assertEquals(expr, compile(expr).getSourceJsonata());
    }

    @Test
    void getSourceJsonata_functionLambda() throws Exception {
        String expr = "$map([1, 2, 3], function($v){ $v * 2 })";
        assertEquals(expr, compile(expr).getSourceJsonata());
    }

    @Test
    void getSourceJsonata_defaultIsEmpty_whenNoSourceProvided() throws Exception {
        // translate() 3-arg overload does not pass a source expression
        AstNode ast = Optimizer.optimize(Parser.parse("42"));
        String src = Translator.translate(ast, "test.gen", nextClass());
        JsonataExpression compiled = LOADER.load(src);
        assertEquals("", compiled.getSourceJsonata());
    }

    @Test
    void getSourceJsonata_preserved_afterMultipleEvaluations() throws Exception {
        String expr = "x + y";
        JsonataExpression compiled = compile(expr);
        compiled.evaluate(JsonNodeTestHelper.parseJson("{\"x\":1,\"y\":2}"));
        compiled.evaluate(JsonNodeTestHelper.parseJson("{\"x\":10,\"y\":20}"));
        assertEquals(expr, compiled.getSourceJsonata(),
                "getSourceJsonata() must return the same value regardless of how many times evaluate() is called");
    }

    @Test
    void getSourceJsonata_sourceEmbeddedInGeneratedSource() throws Exception {
        String expr = "name & \" \" & surname";
        AstNode ast = Optimizer.optimize(Parser.parse(expr));
        String src = Translator.translate(ast, "test.gen", nextClass(), expr);
        assertTrue(src.contains("__SOURCE"),
                "Generated source must contain the __SOURCE field");
        assertTrue(src.contains("getSourceJsonata"),
                "Generated source must override getSourceJsonata()");
    }
}
