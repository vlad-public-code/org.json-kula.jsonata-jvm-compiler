package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlatteningCaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    private static final String DATA = """
        {
            "nest0": [
                {"nest1": [{"nest2": [{"nest3": 1},{"nest3": 2}]},{"nest2": [{"nest3": 3},{"nest3": 4}]}]},
                {"nest1": [{"nest2": [{"nest3": 5},{"nest3": 6}]},{"nest2": [{"nest3": 7},{"nest3": 8}]}]}
            ]
        }""";

    private JsonNode eval(String expr) throws Exception {
        return FACTORY.compile(expr).evaluate(MAPPER.readTree(DATA));
    }
    private void assertEq(String expected, JsonNode actual, String expr) throws Exception {
        assertEquals(MAPPER.readTree(expected), actual, "expr: " + expr);
    }

    @Test void case023() throws Exception {
        String expr = "nest0.nest1.nest2.[nest3]";
        assertEq("[[1],[2],[3],[4],[5],[6],[7],[8]]", eval(expr), expr);
    }

    @Test void case019() throws Exception {
        String expr = "nest0.[nest1.nest2.[nest3]]";
        assertEq("[[[1],[2],[3],[4]],[[5],[6],[7],[8]]]", eval(expr), expr);
    }

    @Test void case018() throws Exception {
        String expr = "nest0.nest1.[nest2.[nest3]]";
        assertEq("[[[1],[2]],[[3],[4]],[[5],[6]],[[7],[8]]]", eval(expr), expr);
    }

    @Test void case017() throws Exception {
        String expr = "nest0.[nest1.[nest2.[nest3]]]";
        assertEq("[[[[1],[2]],[[3],[4]]],[[[5],[6]],[[7],[8]]]]", eval(expr), expr);
    }
}
