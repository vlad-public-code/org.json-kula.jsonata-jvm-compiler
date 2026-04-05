package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests covering examples from https://docs.jsonata.org/simple.
 *
 * Three sections:
 *   1. Navigating JSON Objects
 *   2. Navigating JSON Arrays
 *   3. Top-Level Arrays and Array Flattening
 */
class SimpleQueriesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    // Fred Smith's document used in sections 1 and 2
    private static final String FRED = """
            {
              "FirstName": "Fred",
              "Surname": "Smith",
              "Age": 28,
              "Address": {
                "Street": "Hursley Park",
                "City": "Winchester",
                "Postcode": "SO21 2JN"
              },
              "Phone": [
                { "type": "home",   "number": "0203 544 1234" },
                { "type": "office", "number": "01962 001234"  },
                { "type": "office", "number": "01962 001235"  },
                { "type": "mobile", "number": "077 7700 1234" }
              ],
              "Email": [
                { "type": "work",
                  "address": ["fred.smith@my-work.com", "fsmith@my-work.com"] },
                { "type": "home",
                  "address": ["freddy@my-social.com", "frederic.smith@very-serious.com"] }
              ],
              "Other": {
                "Over 18 ?": true,
                "Misc": null,
                "Alternative.Address": {
                  "Street": "Brick Lane",
                  "City": "London",
                  "Postcode": "E1 6RF"
                }
              }
            }
            """;

    // Top-level array used in section 3
    private static final String REFS = "[{ \"ref\": [ 1,2 ] }, { \"ref\": [ 3,4 ] }]";

    private JsonNode eval(String expr, String json) throws Exception {
        return FACTORY.compile(expr).evaluate(MAPPER.readTree(json));
    }

    private JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    private void assertJsonEqual(String expected, JsonNode actual) throws Exception {
        assertEquals(MAPPER.readTree(expected).toString(), actual.toString());
    }

    // =========================================================================
    // Section 1 — Navigating JSON Objects
    // =========================================================================

    @Test
    void object_surname() throws Exception {
        assertJsonEqual("\"Smith\"", eval("Surname", FRED));
    }

    @Test
    void object_age() throws Exception {
        assertEquals(28, eval("Age", FRED).intValue());
    }

    @Test
    void object_nestedPath_city() throws Exception {
        assertJsonEqual("\"Winchester\"", eval("Address.City", FRED));
    }

    @Test
    void object_nullField_returnsNull() throws Exception {
        // Other.Misc exists but is null → NullNode
        assertTrue(eval("Other.Misc", FRED).isNull());
    }

    @Test
    void object_missingPath_returnsNull() throws Exception {
        // Other.Nothing does not exist — JSONata returns "nothing" (undefined).
        // evaluate() converts the internal MISSING sentinel to JSON null at the boundary.
        assertTrue(eval("Other.Nothing", FRED).isMissingNode());
    }

    @Test
    void object_backtickField_over18() throws Exception {
        // Field name contains whitespace and reserved characters — use backticks
        assertTrue(eval("Other.`Over 18 ?`", FRED).booleanValue());
    }

    // =========================================================================
    // Section 2 — Navigating JSON Arrays
    // =========================================================================

    @Test
    void array_subscript_first() throws Exception {
        // Phone[0] → first element
        assertJsonEqual("{\"type\":\"home\",\"number\":\"0203 544 1234\"}", eval("Phone[0]", FRED));
    }

    @Test
    void array_subscript_second() throws Exception {
        // Phone[1] → second element
        assertJsonEqual("{\"type\":\"office\",\"number\":\"01962 001234\"}", eval("Phone[1]", FRED));
    }

    @Test
    void array_subscript_last() throws Exception {
        // Phone[-1] → last element
        assertJsonEqual("{\"type\":\"mobile\",\"number\":\"077 7700 1234\"}", eval("Phone[-1]", FRED));
    }

    @Test
    void array_subscript_secondToLast() throws Exception {
        // Phone[-2] → second-to-last element
        assertJsonEqual("{\"type\":\"office\",\"number\":\"01962 001235\"}", eval("Phone[-2]", FRED));
    }

    @Test
    void array_subscript_outOfBounds_returnsNull() throws Exception {
        // Phone[8] — index beyond array length → nothing.
        // evaluate() converts the internal MISSING sentinel to JSON null at the boundary.
        assertTrue(eval("Phone[8]", FRED).isMissingNode());
    }

    @Test
    void array_subscriptThenField() throws Exception {
        // Phone[0].number
        assertJsonEqual("\"0203 544 1234\"", eval("Phone[0].number", FRED));
    }

    @Test
    void array_mapField_allNumbers() throws Exception {
        // Phone.number → all numbers from every element, in order
        assertJsonEqual(
                "[\"0203 544 1234\",\"01962 001234\",\"01962 001235\",\"077 7700 1234\"]",
                eval("Phone.number", FRED));
    }

    @Test
    void array_mapFieldThenSubscript_firstOfEach() throws Exception {
        // Phone.number[0] — [0] is bound to the "number" step, not the whole path.
        // For each Phone object the "number" field is a scalar string, so [0] on a
        // scalar returns the scalar itself, preserving all four numbers.
        assertJsonEqual(
                "[\"0203 544 1234\",\"01962 001234\",\"01962 001235\",\"077 7700 1234\"]",
                eval("Phone.number[0]", FRED));
    }

    @Test
    void array_parenthesisedResult_firstElement() throws Exception {
        // (Phone.number)[0] — subscript applied to the whole result sequence
        assertJsonEqual("\"0203 544 1234\"", eval("(Phone.number)[0]", FRED));
    }

    @Test
    void array_rangeSubscript() throws Exception {
        // Phone[[0..1]] → first two elements
        assertJsonEqual(
                "[{\"type\":\"home\",\"number\":\"0203 544 1234\"},{\"type\":\"office\",\"number\":\"01962 001234\"}]",
                eval("Phone[[0..1]]", FRED));
    }

    // =========================================================================
    // Section 3 — Top-Level Arrays and Array Flattening
    // =========================================================================

    @Test
    void topLevel_contextFirstElement() throws Exception {
        // $[0] → first object in the top-level array
        assertJsonEqual("{\"ref\":[1,2]}", eval("$[0]", REFS));
    }

    @Test
    void topLevel_contextFirstElement_refArray() throws Exception {
        // $[0].ref → the internal array [1,2]
        assertJsonEqual("[1,2]", eval("$[0].ref", REFS));
    }

    @Test
    void topLevel_contextFirstElement_refFirstItem() throws Exception {
        // $[0].ref[0] → first element of the internal array
        assertEquals(1, eval("$[0].ref[0]", REFS).intValue());
    }

    @Test
    void topLevel_flattenNestedArrays() throws Exception {
        // $.ref → nested arrays flattened into a single sequence
        assertJsonEqual("[1,2,3,4]", eval("$.ref", REFS));
    }
}
