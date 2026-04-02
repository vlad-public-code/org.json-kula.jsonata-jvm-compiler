package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests covering examples from https://docs.jsonata.org/sorting-grouping.
 *
 * Three sections mapped to the Fred Smith document:
 *   1. Sorting   — order-by operator  ^(key) / ^(>key)
 *   2. Grouping  — dynamic key/value object constructor  {key: value}
 *   3. Aggregation — $count, $sum applied to sub-sequences
 *
 * The Fred Smith document is used throughout (same as SimpleQueriesTest).
 *
 * Phone entries:
 *   home   / 0203 544 1234
 *   office / 01962 001234
 *   office / 01962 001235
 *   mobile / 077 7700 1234
 */
class SortingGroupingTest {

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
    // Section 1 — Sorting (order-by operator)
    // =========================================================================

    @Test
    void sort_phoneByTypeAscending() throws Exception {
        // Phone^(type) — sort phones alphabetically by type
        // home < mobile < office → home, mobile, office, office (stable: 001234 before 001235)
        assertJsonEqual(
                "[{\"type\":\"home\",\"number\":\"0203 544 1234\"},"
                        + "{\"type\":\"mobile\",\"number\":\"077 7700 1234\"},"
                        + "{\"type\":\"office\",\"number\":\"01962 001234\"},"
                        + "{\"type\":\"office\",\"number\":\"01962 001235\"}]",
                eval("Phone^(type)", FRED));
    }

    @Test
    void sort_phoneByTypeDescending() throws Exception {
        // Phone^(>type) — sort phones reverse-alphabetically by type.
        // Implemented as fn_reverse(fn_sort(...)), so equal-key elements
        // also appear in reversed order (01962 001235 before 01962 001234).
        assertJsonEqual(
                "[{\"type\":\"office\",\"number\":\"01962 001235\"},"
                        + "{\"type\":\"office\",\"number\":\"01962 001234\"},"
                        + "{\"type\":\"mobile\",\"number\":\"077 7700 1234\"},"
                        + "{\"type\":\"home\",\"number\":\"0203 544 1234\"}]",
                eval("Phone^(>type)", FRED));
    }

    @Test
    void sort_phoneByTypeAscending_extractNumbers() throws Exception {
        // Phone^(type).number — sort by type then project to numbers
        assertJsonEqual(
                "[\"0203 544 1234\",\"077 7700 1234\",\"01962 001234\",\"01962 001235\"]",
                eval("Phone^(type).number", FRED));
    }

    @Test
    void sort_phoneByNumberAscending() throws Exception {
        // Phone^(number).number — sort by number (string lexicographic order)
        // 01962 001234 < 01962 001235 < 0203 544 1234 < 077 7700 1234
        assertJsonEqual(
                "[\"01962 001234\",\"01962 001235\",\"0203 544 1234\",\"077 7700 1234\"]",
                eval("Phone^(number).number", FRED));
    }

    // =========================================================================
    // Section 2 — Grouping (dynamic key/value object constructor)
    // =========================================================================

    @Test
    void group_phoneByType_scalarAndArray() throws Exception {
        // Phone{type: number} — group phone numbers by type;
        // single-entry types remain scalars, multi-entry types become arrays
        assertJsonEqual(
                "{\"home\":\"0203 544 1234\","
                        + "\"office\":[\"01962 001234\",\"01962 001235\"],"
                        + "\"mobile\":\"077 7700 1234\"}",
                eval("Phone{type: number}", FRED));
    }

    @Test
    void group_phoneByType_forceArrayValues() throws Exception {
        // Phone{type: number[]} — every value is an array, even single entries
        assertJsonEqual(
                "{\"home\":[\"0203 544 1234\"],"
                        + "\"office\":[\"01962 001234\",\"01962 001235\"],"
                        + "\"mobile\":[\"077 7700 1234\"]}",
                eval("Phone{type: number[]}", FRED));
    }

    @Test
    void group_emailByType_countAddresses() throws Exception {
        // Email{type: $count(address)} — count the addresses in each email-type group
        assertJsonEqual("{\"work\":2,\"home\":2}",
                eval("Email{type: $count(address)}", FRED));
    }

    // =========================================================================
    // Section 3 — Aggregation
    // =========================================================================

    @Test
    void aggregation_countAllPhones() throws Exception {
        // $count(Phone) → total number of phone entries
        assertEquals(4, eval("$count(Phone)", FRED).intValue());
    }

    @Test
    void aggregation_countOfficePhones() throws Exception {
        // $count(Phone[type='office']) → how many office phones are listed
        assertEquals(2, eval("$count(Phone[type='office'])", FRED).intValue());
    }

    @Test
    void aggregation_countAllEmailAddresses() throws Exception {
        // $count(Email.address) → total email addresses across all entries (flat sequence)
        assertEquals(4, eval("$count(Email.address)", FRED).intValue());
    }

    @Test
    void aggregation_sumOfExplicitArray() throws Exception {
        // $sum(Account.Order.Product.Price) pattern applied to inline data:
        // $sum([34.45, 34.45, 21.67, 107.99]) — demonstrates $sum over a constructed array
        assertEquals(198.56, eval("$sum([34.45, 34.45, 21.67, 107.99])", FRED).doubleValue(), 1e-9);
    }
}
