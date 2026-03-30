package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests covering examples from https://docs.jsonata.org/predicate.
 *
 * Four sections:
 *   1. Predicate Filtering
 *   2. Singleton Array and Value Equivalence
 *   3. Force Array Returns
 *   4. Wildcards
 */
class PredicateQueriesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    // Fred Smith's document (same as SimpleQueriesTest)
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

    private JsonNode eval(String expr, String json) throws Exception {
        return FACTORY.compile(expr).evaluate(json);
    }

    private void assertJsonEqual(String expected, JsonNode actual) throws Exception {
        assertEquals(MAPPER.readTree(expected).toString(), actual.toString());
    }

    // =========================================================================
    // Section 1 — Predicate Filtering
    // =========================================================================

    @Test
    void predicate_filterByType_mobile() throws Exception {
        // Phone[type='mobile'] → single matching object
        assertJsonEqual(
                "{\"type\":\"mobile\",\"number\":\"077 7700 1234\"}",
                eval("Phone[type='mobile']", FRED));
    }

    @Test
    void predicate_filterByType_mobileNumber() throws Exception {
        // Phone[type='mobile'].number → the number field of the matching element
        assertJsonEqual("\"077 7700 1234\"", eval("Phone[type='mobile'].number", FRED));
    }

    @Test
    void predicate_filterByType_officeNumbers() throws Exception {
        // Phone[type='office'].number → array of numbers for all office phones
        assertJsonEqual(
                "[\"01962 001234\",\"01962 001235\"]",
                eval("Phone[type='office'].number", FRED));
    }

    // =========================================================================
    // Section 2 — Singleton Array and Value Equivalence
    // =========================================================================

    @Test
    void singleton_addressCity() throws Exception {
        // Address.City → single string value
        assertJsonEqual("\"Winchester\"", eval("Address.City", FRED));
    }

    @Test
    void singleton_subscriptThenField() throws Exception {
        // Phone[0].number → single string value
        assertJsonEqual("\"0203 544 1234\"", eval("Phone[0].number", FRED));
    }

    @Test
    void singleton_filterByHome_number() throws Exception {
        // Phone[type='home'].number → single string value (one match)
        assertJsonEqual("\"0203 544 1234\"", eval("Phone[type='home'].number", FRED));
    }

    @Test
    void singleton_filterByOffice_returnsArray() throws Exception {
        // Phone[type='office'].number → array (two matches)
        assertJsonEqual(
                "[\"01962 001234\",\"01962 001235\"]",
                eval("Phone[type='office'].number", FRED));
    }

    // =========================================================================
    // Section 3 — Force Array Returns
    // =========================================================================

    @Test
    void forceArray_addressCity() throws Exception {
        // Address[].City → array wrapping the single City value
        assertJsonEqual("[\"Winchester\"]", eval("Address[].City", FRED));
    }

    @Test
    void forceArray_subscriptResult() throws Exception {
        // Phone[0][].number → array wrapping the single number
        assertJsonEqual("[\"0203 544 1234\"]", eval("Phone[0][].number", FRED));
    }

    // =========================================================================
    // Section 4 — Wildcards
    // =========================================================================

    @Test
    void wildcard_addressFields() throws Exception {
        // Address.* → all values of the Address object
        assertJsonEqual(
                "[\"Hursley Park\",\"Winchester\",\"SO21 2JN\"]",
                eval("Address.*", FRED));
    }

    @Test
    void wildcard_postcode_shallowStar() throws Exception {
        // *.Postcode → Postcode from any top-level object field (Address only, depth 1)
        assertJsonEqual("\"SO21 2JN\"", eval("*.Postcode", FRED));
    }

    @Test
    void wildcard_postcode_descendant() throws Exception {
        // **.Postcode → all Postcode values at any depth
        assertJsonEqual("[\"SO21 2JN\",\"E1 6RF\"]", eval("**.Postcode", FRED));
    }
}
