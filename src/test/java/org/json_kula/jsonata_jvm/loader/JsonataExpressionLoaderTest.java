package org.json_kula.jsonata_jvm.loader;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.json_kula.jsonata_jvm.JsonataExpression;

import java.util.stream.Stream;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.parseJson;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Tests for {@link JsonataExpressionLoader#load(String)}.
 *
 * <p>30 valid sources verify that load() returns a working {@link JsonataExpression}
 * and that evaluate() produces the expected JSON output.
 * 20 invalid sources verify that load() throws {@link JsonataLoadException}.
 */
class JsonataExpressionLoaderTest {

    /** Minimal valid JSON used as a neutral input where the expression ignores it. */
    private static final JsonNode EMPTY_OBJ = EMPTY_OBJECT;

    // -----------------------------------------------------------------------
    // 30 valid sources
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("validSources")
    void load_validSource_returnsExpectedResult(String description,
                                                String source,
                                                JsonNode inputJson,
                                                String expectedJson) throws Exception {
        JsonataExpression expr = new JsonataExpressionLoader().load(source);
        assertNotNull(expr, description);
        JsonNode result = expr.evaluate(inputJson);
        assertNotNull(result, description);
        assertEquals(expectedJson, result.toString(), description);
    }

    static Stream<Arguments> validSources() {
        return Stream.of(

            // 1. Minimal class — returns NullNode
            arguments("returns NullNode", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.NullNode;
                public class Expr01 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return NullNode.getInstance();
                    }
                }
                """, EMPTY_OBJ, "null"),

            // 2. Returns TextNode
            arguments("returns TextNode", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.TextNode;
                public class Expr02 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new TextNode("hello");
                    }
                }
                """, EMPTY_OBJ, "\"hello\""),

            // 3. Returns IntNode
            arguments("returns IntNode(42)", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.IntNode;
                public class Expr03 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new IntNode(42);
                    }
                }
                """, EMPTY_OBJ, "42"),

            // 4. Returns DoubleNode
            arguments("returns DoubleNode(3.14)", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.DoubleNode;
                public class Expr04 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new DoubleNode(3.14);
                    }
                }
                """, EMPTY_OBJ, "3.14"),

            // 5. Returns BooleanNode TRUE
            arguments("returns BooleanNode.TRUE", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.BooleanNode;
                public class Expr05 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return BooleanNode.TRUE;
                    }
                }
                """, EMPTY_OBJ, "true"),

            // 6. Returns BooleanNode FALSE
            arguments("returns BooleanNode.FALSE", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.BooleanNode;
                public class Expr06 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return BooleanNode.FALSE;
                    }
                }
                """, EMPTY_OBJ, "false"),

            // 7. Passthrough — returns the input tree unchanged
            arguments("passthrough: returns JSON as-is", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                public class Expr07 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return input;
                    }
                }
                """, parseJson("42"), "42"),

            // 8. Extracts a text field
            arguments("extracts text field 'name'", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                public class Expr08 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return input.get("name");
                    }
                }
                """, parseJson("{\"name\":\"Alice\"}"), "\"Alice\""),

            // 9. Extracts an integer field
            arguments("extracts int field 'age'", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                public class Expr09 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return input.get("age");
                    }
                }
                """, parseJson("{\"age\":30}"), "30"),

            // 10. Extracts a boolean field
            arguments("extracts boolean field 'active'", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                public class Expr10 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return input.get("active");
                    }
                }
                """, parseJson("{\"active\":true}"), "true"),

            // 11. Extracts a nested field via JSON Pointer
            arguments("extracts nested field /a/b via JSON Pointer", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                public class Expr11 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return input.at("/a/b");
                    }
                }
                """, parseJson("{\"a\":{\"b\":\"deep\"}}"), "\"deep\""),

            // 12. Returns empty ArrayNode
            arguments("returns empty ArrayNode []", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.ArrayNode;
                public class Expr12 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
                    }
                }
                """, EMPTY_OBJ, "[]"),

            // 13. Returns ArrayNode with elements
            arguments("returns ArrayNode [1,2,3]", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.ArrayNode;
                public class Expr13 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        ArrayNode arr = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
                        arr.add(1); arr.add(2); arr.add(3);
                        return arr;
                    }
                }
                """, EMPTY_OBJ, "[1,2,3]"),

            // 14. Returns ObjectNode with a field
            arguments("returns ObjectNode {\"result\":true}", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.ObjectNode;
                public class Expr14 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        ObjectNode obj = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                        obj.put("result", true);
                        return obj;
                    }
                }
                """, EMPTY_OBJ, "{\"result\":true}"),

            // 15. Returns array size of the input
            arguments("returns size of input JSON array", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.IntNode;
                public class Expr15 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new IntNode(input.size());
                    }
                }
                """, parseJson("[1,2,3]"), "3"),

            // 16. Uses a private helper method
            arguments("delegates to private helper method", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.TextNode;
                public class Expr16 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new TextNode(compute());
                    }
                    private String compute() { return "computed"; }
                }
                """, EMPTY_OBJ, "\"computed\""),

            // 17. Uses a static helper method
            arguments("delegates to static helper method", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.IntNode;
                public class Expr17 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new IntNode(square(5));
                    }
                    private static int square(int n) { return n * n; }
                }
                """, EMPTY_OBJ, "25"),

            // 18. Has static final constant field
            arguments("reads from static final constant", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.TextNode;
                public class Expr18 implements JsonataExpression {
                    private static final String VERSION = "1.0";
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new TextNode(VERSION);
                    }
                }
                """, EMPTY_OBJ, "\"1.0\""),

            // 19. Uses local variables
            arguments("uses local variables (7 * 6 = 42)", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.IntNode;
                public class Expr19 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        int x = 7;
                        int y = 6;
                        return new IntNode(x * y);
                    }
                }
                """, EMPTY_OBJ, "42"),

            // 20. Uses StringBuilder
            arguments("uses StringBuilder to assemble a string", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.TextNode;
                public class Expr20 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        StringBuilder sb = new StringBuilder();
                        sb.append("foo").append("-").append("bar");
                        return new TextNode(sb.toString());
                    }
                }
                """, EMPTY_OBJ, "\"foo-bar\""),

            // 21. Uses String.format
            arguments("uses String.format to embed input", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.TextNode;
                public class Expr21 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new TextNode(String.format("type=%s", input.getNodeType().name()));
                    }
                }
                """, EMPTY_OBJ, "\"type=OBJECT\""),

            // 22. if-else conditional
            arguments("if-else based on input being non-blank", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.BooleanNode;
                public class Expr22 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        if (input != null && !input.isNull()) return BooleanNode.TRUE;
                        else return BooleanNode.FALSE;
                    }
                }
                """, EMPTY_OBJ, "true"),

            // 23. for loop — sums 1+2+3+4+5
            arguments("for loop: sums 1 through 5", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.IntNode;
                public class Expr23 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        int sum = 0;
                        for (int i = 1; i <= 5; i++) sum += i;
                        return new IntNode(sum);
                    }
                }
                """, EMPTY_OBJ, "15"),

            // 24. while loop
            arguments("while loop: counts halving steps from 100", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.IntNode;
                public class Expr24 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        int n = 100, steps = 0;
                        while (n > 1) { n /= 2; steps++; }
                        return new IntNode(steps);
                    }
                }
                """, EMPTY_OBJ, "6"),

            // 25. Input validation
            arguments("validates input and throws JsonataEvaluationException", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                public class Expr25 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        if (input == null || input.isNull()) {
                            throw new JsonataEvaluationException("Input cannot be null");
                        }
                        return input;
                    }
                }
                """, parseJson("true"), "true"),

            // 26. Inner static utility class
            arguments("has inner static utility class", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.TextNode;
                public class Expr26 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new TextNode(Util.tag());
                    }
                    private static class Util {
                        static String tag() { return "inner-class"; }
                    }
                }
                """, EMPTY_OBJ, "\"inner-class\""),

            // 27. Class in package org.json_kula.jsonata_jvm.generated
            arguments("class declared in package org.json_kula.jsonata_jvm.generated", """
                package org.json_kula.jsonata_jvm.generated;
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.TextNode;
                public class Expr27 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new TextNode("packaged");
                    }
                }
                """, EMPTY_OBJ, "\"packaged\""),

            // 28. No package declaration
            arguments("class with no package declaration", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.IntNode;
                public class Expr28 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new IntNode(0);
                    }
                }
                """, EMPTY_OBJ, "0"),

            // 29. Very long class name
            arguments("class with a long descriptive name", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.BooleanNode;
                public class CompiledJsonataExpressionForAccountOrderProductPriceTotal
                        implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return BooleanNode.TRUE;
                    }
                }
                """, EMPTY_OBJ, "true"),

            // 30. Thread-safety: evaluate() uses only local state (no shared mutable fields)
            arguments("stateless evaluate() — thread-safe by construction", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.IntNode;
                import com.fasterxml.jackson.databind.node.TextNode;
                public class Expr30 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        if (input.isObject()) {
                            return new IntNode(input.size());
                        } else if (input.isArray()) {
                            return new TextNode("array:" + input.size());
                        }
                        return new IntNode(0);
                    }
                }
                """, parseJson("{\"x\":1}"), "1")
        );
    }

    // -----------------------------------------------------------------------
    // 20 invalid sources
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidSources")
    void load_invalidSource_throwsJsonataLoadException(String description, String source) {
        assertThrows(JsonataLoadException.class,
                () -> new JsonataExpressionLoader().load(source),
                description);
    }

    static Stream<Arguments> invalidSources() {
        return Stream.of(

            // 31. Empty string
            arguments("empty string", ""),

            // 32. Whitespace only
            arguments("whitespace and newlines only", "   \n\t  \n"),

            // 33. Plain English prose
            arguments("plain English prose, not Java",
                    "This expression sums all order prices in the Account object."),

            // 34. Valid Java class NOT implementing JsonataExpression
            arguments("class does not implement JsonataExpression", """
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.NullNode;
                public class NotAnExpression {
                    public JsonNode evaluate(String json) { return NullNode.getInstance(); }
                }
                """),

            // 35. Interface declaration — no 'class' keyword for extractClassName
            arguments("interface declaration (no class keyword to extract name from)", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import com.fasterxml.jackson.databind.JsonNode;
                public interface MyPlugin extends JsonataExpression {
                }
                """),

            // 36. Abstract class — compiles but cannot be instantiated
            arguments("abstract class cannot be instantiated", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                public abstract class AbstractExpr implements JsonataExpression {
                    protected abstract JsonNode doEvaluate(JsonNode input) throws JsonataEvaluationException;
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return doEvaluate(input);
                    }
                }
                """),

            // 37. Missing closing brace for class body
            arguments("missing closing brace", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.NullNode;
                public class Expr37 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return NullNode.getInstance();
                    }
                // class closing brace intentionally missing
                """),

            // 38. Missing semicolon in method body
            arguments("missing semicolon after return statement", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.NullNode;
                public class Expr38 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return NullNode.getInstance()
                    }
                }
                """),

            // 39. Undeclared variable reference
            arguments("undeclared variable in method body", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.TextNode;
                public class Expr39 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return new TextNode(undeclaredVariable);
                    }
                }
                """),

            // 40. Import of a non-existent class
            arguments("import of non-existent class", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.example.nonexistent.MagicEvaluator;
                public class Expr40 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return MagicEvaluator.run(input);
                    }
                }
                """),

            // 41. evaluate() returns String instead of JsonNode — wrong interface override
            arguments("evaluate() returns String instead of JsonNode", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                public class Expr41 implements JsonataExpression {
                    public String evaluate(JsonNode input) throws JsonataEvaluationException {
                        return "hello";
                    }
                }
                """),

            // 42. evaluate() takes int instead of JsonNode — wrong interface override
            arguments("evaluate() takes int instead of JsonNode", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.NullNode;
                public class Expr42 implements JsonataExpression {
                    public JsonNode evaluate(int input) throws JsonataEvaluationException {
                        return NullNode.getInstance();
                    }
                }
                """),

            // 43. Only a parameterized constructor — no no-arg constructor to call
            arguments("only parameterized constructor, no no-arg constructor", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.NullNode;
                public class Expr43 implements JsonataExpression {
                    private final String prefix;
                    public Expr43(String prefix) { this.prefix = prefix; }
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return NullNode.getInstance();
                    }
                }
                """),

            // 44. Enum implementing JsonataExpression — no accessible no-arg constructor via reflection
            arguments("enum has no accessible no-arg constructor", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.NullNode;
                public enum Expr44 implements JsonataExpression {
                    INSTANCE;
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return NullNode.getInstance();
                    }
                }
                """),

            // 45. Private no-arg constructor — inaccessible via reflection
            arguments("private constructor is inaccessible via reflection", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.NullNode;
                public class Expr45 implements JsonataExpression {
                    private Expr45() {}
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return NullNode.getInstance();
                    }
                }
                """),

            // 46. Call to an undefined method
            arguments("method body calls an undefined method", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                public class Expr46 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return undefinedMethod(input);
                    }
                }
                """),

            // 47. Type mismatch: assigning String literal to int
            arguments("type mismatch: String literal assigned to int", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.IntNode;
                public class Expr47 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        int x = "not an int";
                        return new IntNode(x);
                    }
                }
                """),

            // 48. Two classes, neither implements JsonataExpression
            arguments("two classes, neither implements JsonataExpression", """
                import com.fasterxml.jackson.databind.JsonNode;
                public class Expr48 {
                    public JsonNode evaluate(JsonNode input) { return null; }
                }
                class Expr48Helper {
                    public String process(String s) { return s; }
                }
                """),

            // 49. Duplicate evaluate() method — compilation error
            arguments("duplicate evaluate() method definition", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.NullNode;
                public class Expr49 implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return NullNode.getInstance();
                    }
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return NullNode.getInstance();
                    }
                }
                """),

            // 50. Extends a non-existent superclass
            arguments("extends non-existent superclass", """
                import org.json_kula.jsonata_jvm.JsonataExpression;
                import org.json_kula.jsonata_jvm.JsonataEvaluationException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.NullNode;
                public class Expr50 extends NonExistentBaseClass implements JsonataExpression {
                    public JsonNode evaluate(JsonNode input) throws JsonataEvaluationException {
                        return NullNode.getInstance();
                    }
                }
                """)
        );
    }
}
