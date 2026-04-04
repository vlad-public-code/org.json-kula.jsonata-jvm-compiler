package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsonNodeTestHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final JsonNode EMPTY_OBJECT = MAPPER.createObjectNode();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    /**
     * Parses a JSON string into a JsonNode.
     */
    public static JsonNode parseJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }

    /**
     * Compiles and evaluates a JSONata expression against a JSON string.
     */
    public static JsonNode evaluate(String expression, String json) throws Exception {
        return FACTORY.compile(expression).evaluate(parseJson(json));
    }

    /**
     * Prints generated Java code, compiles and evaluates a JSONata expression against a JSON string.
     */
    public static JsonNode printJavaAndEvaluate(String expression, String json) throws Exception {
        System.out.println(FACTORY.translate(expression));
        return FACTORY.compile(expression).evaluate(parseJson(json));
    }

    /**
     * Compiles and evaluates a JSONata expression against a JsonNode.
     */
    public static JsonNode evaluate(String expression, JsonNode json) throws Exception {
        return FACTORY.compile(expression).evaluate(json);
    }

    /**
     * Compiles and evaluates a JSONata expression against a JSON string with bindings.
     */
    public static JsonNode evaluate(String expression, String json, JsonataBindings bindings) {
        try {
            return FACTORY.compile(expression).evaluate(parseJson(json), bindings);
        } catch (Exception e) {
            throw new RuntimeException("Failed to evaluate: " + expression, e);
        }
    }

    /**
     * Compiles and evaluates a JSONata expression against a JsonNode with bindings.
     */
    public static JsonNode evaluate(String expression, JsonNode json, JsonataBindings bindings) {
        try {
            return FACTORY.compile(expression).evaluate(json, bindings);
        } catch (Exception e) {
            throw new RuntimeException("Failed to evaluate: " + expression, e);
        }
    }

    /**
     * Compiles and evaluates a JSONata expression against an empty object.
     */
    public static JsonNode evaluate(String expression) throws Exception {
        return evaluate(expression, EMPTY_OBJECT);
    }

    /**
     * Recursively normalizes numeric nodes to LongNode.
     */
    private static JsonNode normalizeNumbers(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            obj.fieldNames().forEachRemaining(field -> {
                obj.set(field, normalizeNumbers(obj.get(field)));
            });
            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, normalizeNumbers(arr.get(i)));
            }
            return arr;
        } else if (node.isNumber()) {
            return new LongNode(node.longValue());
        }
        return node;
    }

    /**
     * Assert two JsonNodes are equal ignoring IntNode vs LongNode differences
     */
    public static void assertJsonEquals(JsonNode expected, JsonNode actual, String message) {
        assertEquals(
                normalizeNumbers(expected),
                normalizeNumbers(actual),
                message
        );
    }
}
