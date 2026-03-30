package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests covering examples from https://docs.jsonata.org/expressions.
 *
 * Four sections:
 *   1. String Expressions
 *   2. Numeric Expressions
 *   3. Comparison Expressions
 *   4. Boolean Expressions
 */
class ExpressionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    // Fred Smith's document — used for string and comparison examples
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
              ]
            }
            """;

    // Document used for numeric and boolean examples
    private static final String NUMBERS = """
            { "Numbers": [1, 2.4, 3.5, 10, 20.9, 30] }
            """;

    private JsonNode eval(String expr, String json) throws Exception {
        return FACTORY.compile(expr).evaluate(json);
    }

    private void assertJsonEqual(String expected, JsonNode actual) throws Exception {
        assertEquals(MAPPER.readTree(expected).toString(), actual.toString());
    }

    // =========================================================================
    // Section 1 — String Expressions
    // =========================================================================

    @Test
    void string_concatenate_firstName_surname() throws Exception {
        // FirstName & ' ' & Surname → "Fred Smith"
        assertJsonEqual("\"Fred Smith\"", eval("FirstName & ' ' & Surname", FRED));
    }

    @Test
    void string_concatenate_in_sub_object() throws Exception {
        // Address.(Street & ', ' & City) → "Hursley Park, Winchester"
        assertJsonEqual("\"Hursley Park, Winchester\"",
                eval("Address.(Street & ', ' & City)", FRED));
    }

    @Test
    void string_concat_coerces_number_and_boolean() throws Exception {
        // 5 & 0 & true → "50true"  (non-string operands are coerced to strings)
        assertJsonEqual("\"50true\"", eval("5&0&true", NUMBERS));
    }

    // =========================================================================
    // Section 2 — Numeric Expressions
    // =========================================================================

    @Test
    void numeric_addition() throws Exception {
        // Numbers[0] + Numbers[1]  →  1 + 2.4 = 3.4
        assertEquals(3.4, eval("Numbers[0] + Numbers[1]", NUMBERS).doubleValue(), 1e-10);
    }

    @Test
    void numeric_subtraction() throws Exception {
        // Numbers[0] - Numbers[4]  →  1 - 20.9 = -19.9
        assertEquals(-19.9, eval("Numbers[0] - Numbers[4]", NUMBERS).doubleValue(), 1e-10);
    }

    @Test
    void numeric_multiplication() throws Exception {
        // Numbers[0] * Numbers[5]  →  1 * 30 = 30
        assertEquals(30, eval("Numbers[0] * Numbers[5]", NUMBERS).intValue());
    }

    @Test
    void numeric_division() throws Exception {
        // Numbers[0] / Numbers[4]  →  1 / 20.9 ≈ 0.04784688995215
        assertEquals(1.0 / 20.9, eval("Numbers[0] / Numbers[4]", NUMBERS).doubleValue(), 1e-12);
    }

    @Test
    void numeric_modulo() throws Exception {
        // Numbers[2] % Numbers[5]  →  3.5 % 30 = 3.5
        assertEquals(3.5, eval("Numbers[2] % Numbers[5]", NUMBERS).doubleValue(), 1e-10);
    }

    // =========================================================================
    // Section 3 — Comparison Expressions
    // =========================================================================

    @Test
    void comparison_equal_false() throws Exception {
        // Numbers[0] = Numbers[5]  →  1 = 30  →  false
        assertFalse(eval("Numbers[0] = Numbers[5]", NUMBERS).booleanValue());
    }

    @Test
    void comparison_notEqual_true() throws Exception {
        // Numbers[0] != Numbers[4]  →  1 != 20.9  →  true
        assertTrue(eval("Numbers[0] != Numbers[4]", NUMBERS).booleanValue());
    }

    @Test
    void comparison_lessThan_true() throws Exception {
        // Numbers[1] < Numbers[5]  →  2.4 < 30  →  true
        assertTrue(eval("Numbers[1] < Numbers[5]", NUMBERS).booleanValue());
    }

    @Test
    void comparison_lessThanOrEqual_true() throws Exception {
        // Numbers[1] <= Numbers[5]  →  2.4 <= 30  →  true
        assertTrue(eval("Numbers[1] <= Numbers[5]", NUMBERS).booleanValue());
    }

    @Test
    void comparison_greaterThan_false() throws Exception {
        // Numbers[2] > Numbers[4]  →  3.5 > 20.9  →  false
        assertFalse(eval("Numbers[2] > Numbers[4]", NUMBERS).booleanValue());
    }

    @Test
    void comparison_greaterThanOrEqual_false() throws Exception {
        // Numbers[2] >= Numbers[4]  →  3.5 >= 20.9  →  false
        assertFalse(eval("Numbers[2] >= Numbers[4]", NUMBERS).booleanValue());
    }

    @Test
    void comparison_in_membership_true() throws Exception {
        // "01962 001234" in Phone.number  →  true
        assertTrue(eval("\"01962 001234\" in Phone.number", FRED).booleanValue());
    }

    @Test
    void comparison_in_membership_false() throws Exception {
        // "555 000 0000" not in Phone.number  →  the 'in' expression returns false
        assertFalse(eval("\"555 000 0000\" in Phone.number", FRED).booleanValue());
    }

    // =========================================================================
    // Section 4 — Boolean Expressions
    // =========================================================================

    @Test
    void boolean_and_true() throws Exception {
        // (Numbers[2] != 0) and (Numbers[5] != Numbers[1])  →  true
        assertTrue(eval("(Numbers[2] != 0) and (Numbers[5] != Numbers[1])", NUMBERS).booleanValue());
    }

    @Test
    void boolean_or_true() throws Exception {
        // (Numbers[2] != 0) or (Numbers[5] = Numbers[1])  →  true
        assertTrue(eval("(Numbers[2] != 0) or (Numbers[5] = Numbers[1])", NUMBERS).booleanValue());
    }

    @Test
    void boolean_not_false() throws Exception {
        // not(Numbers[0] = Numbers[5])  →  not(false) → true
        assertTrue(eval("not(Numbers[0] = Numbers[5])", NUMBERS).booleanValue());
    }
}
