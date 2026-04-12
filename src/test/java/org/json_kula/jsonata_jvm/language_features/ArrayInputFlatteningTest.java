package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the array-inputs.json flattening test cases from the JSONata test suite.
 */
class ArrayInputFlatteningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();
    private static final String ARRAY_DATA =
        "[{\"phone\":[{\"number\":0}]},{\"phone\":[{\"number\":1}]},{\"phone\":[{\"number\":2}]}]";

    private JsonNode input() throws Exception { return MAPPER.readTree(ARRAY_DATA); }
    private JsonNode emptyArr() throws Exception { return MAPPER.readTree("[]"); }

    private JsonNode eval(String expr, JsonNode data) throws Exception {
        return FACTORY.compile(expr).evaluate(data);
    }
    private void assertEq(String expected, JsonNode actual) throws Exception {
        assertEquals(MAPPER.readTree(expected), actual);
    }

    @Test void case0_phone() throws Exception {
        assertEq("[{\"number\":0},{\"number\":1},{\"number\":2}]", eval("phone", input()));
    }
    @Test void case1_phone0() throws Exception {
        assertEq("{\"number\":0}", eval("phone[0]", input()));
    }
    @Test void case2_phone0_array() throws Exception {
        assertEq("[{\"number\":0}]", eval("phone[0][]", input()));
    }
    @Test void case3_phone0_number() throws Exception {
        assertEq("0", eval("phone[0].number", input()));
    }
    @Test void case4_paren_phone0_number() throws Exception {
        assertEq("0", eval("(phone)[0].number", input()));
    }
    @Test void case5_dollar_phone0_number() throws Exception {
        assertEq("[0,1,2]", eval("$.phone[0].number", input()));
    }
    @Test void case6_object_literal_empty_array() throws Exception {
        assertEq("{\"Hello\":\"World\"}", eval("{'Hello':'World'}", emptyArr()));
    }
    @Test void case7_dollar_object_literal_empty_array() throws Exception {
        assertTrue(eval("$.{'Hello':'World'}", emptyArr()).isMissingNode(),
            "Expected undefined result");
    }
}
