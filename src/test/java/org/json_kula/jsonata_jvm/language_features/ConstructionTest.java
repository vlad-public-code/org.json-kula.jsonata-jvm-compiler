package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests covering examples from https://docs.jsonata.org/construction.
 *
 * Two sections:
 *   1. Array Constructors
 *   2. Object Constructors
 */
class ConstructionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

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
        return FACTORY.compile(expr).evaluate(MAPPER.readTree(json));
    }

    private void assertJsonEqual(String expected, JsonNode actual) throws Exception {
        assertEquals(MAPPER.readTree(expected).toString(), actual.toString());
    }

    // =========================================================================
    // Section 1 — Array Constructors
    // =========================================================================

    @Test
    void array_flatMap_emailAddresses() throws Exception {
        // Email.address — maps over each Email element and flattens the address arrays
        assertJsonEqual(
                "[\"fred.smith@my-work.com\",\"fsmith@my-work.com\","
                        + "\"freddy@my-social.com\",\"frederic.smith@very-serious.com\"]",
                eval("Email.address", FRED));
    }

    @Test
    void array_constructor_nestedAddresses() throws Exception {
        // Email.[address] — wraps each element's address array in its own array
        assertJsonEqual(
                "[[\"fred.smith@my-work.com\",\"fsmith@my-work.com\"],"
                        + "[\"freddy@my-social.com\",\"frederic.smith@very-serious.com\"]]",
                eval("Email.[address]", FRED));
    }

    @Test
    void array_constructor_multiplePathsCities() throws Exception {
        // [Address, Other.`Alternative.Address`].City — build array from two objects, navigate City
        assertJsonEqual("[\"Winchester\",\"London\"]",
                eval("[Address, Other.`Alternative.Address`].City", FRED));
    }

    // =========================================================================
    // Section 2 — Object Constructors
    // =========================================================================

    @Test
    void object_dotConstructor_arrayOfObjects() throws Exception {
        // Phone.{type: number} — one object per Phone element
        assertJsonEqual(
                "[{\"home\":\"0203 544 1234\"},{\"office\":\"01962 001234\"},"
                        + "{\"office\":\"01962 001235\"},{\"mobile\":\"077 7700 1234\"}]",
                eval("Phone.{type: number}", FRED));
    }

    @Test
    void object_groupBy_duplicateKeysAggregated() throws Exception {
        // Phone{type: number} — single object; duplicate keys merged into array
        assertJsonEqual(
                "{\"home\":\"0203 544 1234\","
                        + "\"office\":[\"01962 001234\",\"01962 001235\"],"
                        + "\"mobile\":\"077 7700 1234\"}",
                eval("Phone{type: number}", FRED));
    }

    @Test
    void object_groupBy_forceArrayValues() throws Exception {
        // Phone{type: number[]} — every value is an array, even singletons
        assertJsonEqual(
                "{\"home\":[\"0203 544 1234\"],"
                        + "\"office\":[\"01962 001234\",\"01962 001235\"],"
                        + "\"mobile\":[\"077 7700 1234\"]}",
                eval("Phone{type: number[]}", FRED));
    }
}
