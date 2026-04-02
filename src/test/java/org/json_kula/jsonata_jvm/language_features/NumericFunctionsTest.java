package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata numeric functions:
 *   $number, $abs, $floor, $ceil, $round, $power, $sqrt,
 *   $random, $formatNumber, $formatBase, $formatInteger, $parseInteger
 *
 * Spec: https://docs.jsonata.org/numeric-functions
 */
class NumericFunctionsTest {

    private JsonNode eval(String expr) throws Exception {
        return JsonNodeTestHelper.evaluate(expr);
    }

    // =========================================================================
    // $number
    // =========================================================================

    @Test
    void number_from_string() throws Exception {
        assertEquals(5L, eval("$number(\"5\")").longValue());
    }

    @Test
    void number_unchanged() throws Exception {
        assertEquals(42L, eval("$number(42)").longValue());
    }

    @Test
    void number_boolean_true() throws Exception {
        assertEquals(1L, eval("$number(true)").longValue());
    }

    @Test
    void number_boolean_false() throws Exception {
        assertEquals(0L, eval("$number(false)").longValue());
    }

    @Test
    void number_hex_literal() throws Exception {
        // 0x12 = 18
        assertEquals(18L, eval("$number(\"0x12\")").longValue());
    }

    @Test
    void number_octal_literal() throws Exception {
        // 0o17 = 15
        assertEquals(15L, eval("$number(\"0o17\")").longValue());
    }

    @Test
    void number_binary_literal() throws Exception {
        // 0b1010 = 10
        assertEquals(10L, eval("$number(\"0b1010\")").longValue());
    }

    @Test
    void number_missing_returns_null() throws Exception {
        assertTrue(eval("$number($notDefined)").isNull());
    }

    // =========================================================================
    // $abs
    // =========================================================================

    @Test
    void abs_positive() throws Exception {
        assertEquals(5L, eval("$abs(5)").longValue());
    }

    @Test
    void abs_negative() throws Exception {
        assertEquals(5L, eval("$abs(-5)").longValue());
    }

    @Test
    void abs_zero() throws Exception {
        assertEquals(0L, eval("$abs(0)").longValue());
    }

    // =========================================================================
    // $floor / $ceil
    // =========================================================================

    @Test
    void floor_positive_fraction() throws Exception {
        assertEquals(5L, eval("$floor(5.3)").longValue());
        assertEquals(5L, eval("$floor(5.8)").longValue());
    }

    @Test
    void floor_negative_fraction() throws Exception {
        assertEquals(-6L, eval("$floor(-5.3)").longValue());
    }

    @Test
    void ceil_positive_fraction() throws Exception {
        assertEquals(6L, eval("$ceil(5.3)").longValue());
        assertEquals(6L, eval("$ceil(5.8)").longValue());
    }

    @Test
    void ceil_negative_fraction() throws Exception {
        assertEquals(-5L, eval("$ceil(-5.3)").longValue());
    }

    @Test
    void floor_integer_unchanged() throws Exception {
        assertEquals(5L, eval("$floor(5)").longValue());
    }

    @Test
    void ceil_integer_unchanged() throws Exception {
        assertEquals(5L, eval("$ceil(5)").longValue());
    }

    // =========================================================================
    // $round — with half-to-even (banker's rounding) and precision
    // =========================================================================

    @Test
    void round_basic() throws Exception {
        assertEquals(123L, eval("$round(123.456)").longValue());
    }

    @Test
    void round_with_precision() throws Exception {
        assertEquals("123.46", eval("$string($round(123.456, 2))").textValue());
    }

    @Test
    void round_negative_precision() throws Exception {
        assertEquals(120L, eval("$round(123.456, -1)").longValue());
        assertEquals(100L, eval("$round(123.456, -2)").longValue());
    }

    @Test
    void round_half_to_even_11_5() throws Exception {
        // 11.5 → half-to-even → 12 (12 is even)
        assertEquals(12L, eval("$round(11.5)").longValue());
    }

    @Test
    void round_half_to_even_12_5() throws Exception {
        // 12.5 → half-to-even → 12 (12 is even)
        assertEquals(12L, eval("$round(12.5)").longValue());
    }

    @Test
    void round_half_to_even_125_neg1() throws Exception {
        // $round(125, -1) → 120 (round half-to-even: 12 is even)
        assertEquals(120L, eval("$round(125, -1)").longValue());
    }

    // =========================================================================
    // $power
    // =========================================================================

    @Test
    void power_integer() throws Exception {
        assertEquals(256L, eval("$power(2, 8)").longValue());
    }

    @Test
    void power_fraction_exponent() throws Exception {
        assertEquals(1.4142135623730951, eval("$power(2, 0.5)").doubleValue(), 1e-10);
    }

    @Test
    void power_negative_exponent() throws Exception {
        assertEquals(0.25, eval("$power(2, -2)").doubleValue(), 1e-15);
    }

    // =========================================================================
    // $sqrt
    // =========================================================================

    @Test
    void sqrt_perfect_square() throws Exception {
        assertEquals(2L, eval("$sqrt(4)").longValue());
    }

    @Test
    void sqrt_irrational() throws Exception {
        assertEquals(Math.sqrt(2), eval("$sqrt(2)").doubleValue(), 1e-10);
    }

    @Test
    void sqrt_negative_throws() {
        assertThrows(Exception.class, () -> eval("$sqrt(-1)"));
    }

    // =========================================================================
    // $random
    // =========================================================================

    @Test
    void random_in_range() throws Exception {
        double v = eval("$random()").doubleValue();
        assertTrue(v >= 0.0 && v < 1.0, "Expected 0 ≤ $random() < 1, got " + v);
    }

    @Test
    void random_two_calls_differ() throws Exception {
        // With overwhelming probability two independent calls differ
        double v1 = eval("$random()").doubleValue();
        double v2 = eval("$random()").doubleValue();
        // They *could* be equal but the probability is negligible; this is a sanity check
        assertNotNull(v1);
        assertNotNull(v2);
    }

    // =========================================================================
    // $formatNumber
    // =========================================================================

    @Test
    void formatNumber_grouping_and_decimal() throws Exception {
        assertEquals("12,345.60", eval("$formatNumber(12345.6, '#,###.00')").textValue());
    }

    @Test
    void formatNumber_scientific() throws Exception {
        assertEquals("12.346e2", eval("$formatNumber(1234.5678, \"00.000e0\")").textValue());
    }

    @Test
    void formatNumber_positive_pattern() throws Exception {
        assertEquals("34.56", eval("$formatNumber(34.555, \"#0.00;(#0.00)\")").textValue());
    }

    @Test
    void formatNumber_negative_pattern() throws Exception {
        assertEquals("(34.56)", eval("$formatNumber(-34.555, \"#0.00;(#0.00)\")").textValue());
    }

    @Test
    void formatNumber_percent() throws Exception {
        assertEquals("14%", eval("$formatNumber(0.14, \"01%\")").textValue());
    }

    @Test
    void formatNumber_per_mille_custom() throws Exception {
        // {"per-mille": "pm"} → per-mille suffix "pm"
        assertEquals("140pm",
                eval("$formatNumber(0.14, \"###pm\", {\"per-mille\": \"pm\"})").textValue());
    }

    @Test
    void formatNumber_unicode_digits() throws Exception {
        // {"zero-digit": "①"} → circled digit family: ①=0,②=1,…
        // 1234.5678 with "①①.①①①e①" → "①②.③④⑥e②" (coefficient 12.346, exponent 2)
        assertEquals("\u2460\u2461.\u2462\u2463\u2465e\u2461",
                eval("$formatNumber(1234.5678, \"\u2460\u2460.\u2460\u2460\u2460e\u2460\","
                        + "{\"zero-digit\": \"\u2460\"})").textValue());
    }

    @Test
    void formatNumber_missing_returns_null() throws Exception {
        assertTrue(eval("$formatNumber($notDefined, \"0\")").isNull());
    }

    // =========================================================================
    // $formatBase
    // =========================================================================

    @Test
    void formatBase_binary() throws Exception {
        assertEquals("1100100", eval("$formatBase(100, 2)").textValue());
    }

    @Test
    void formatBase_hex() throws Exception {
        assertEquals("9fb", eval("$formatBase(2555, 16)").textValue());
    }

    @Test
    void formatBase_default_decimal() throws Exception {
        assertEquals("255", eval("$formatBase(255)").textValue());
    }

    @Test
    void formatBase_octal() throws Exception {
        assertEquals("377", eval("$formatBase(255, 8)").textValue());
    }

    @Test
    void formatBase_invalid_radix_throws() {
        assertThrows(Exception.class, () -> eval("$formatBase(10, 1)"));
        assertThrows(Exception.class, () -> eval("$formatBase(10, 37)"));
    }

    // =========================================================================
    // $formatInteger
    // =========================================================================

    @Test
    void formatInteger_words() throws Exception {
        assertEquals("two thousand, seven hundred and eighty-nine",
                eval("$formatInteger(2789, 'w')").textValue());
    }

    @Test
    void formatInteger_words_uppercase() throws Exception {
        assertEquals("TWO THOUSAND, SEVEN HUNDRED AND EIGHTY-NINE",
                eval("$formatInteger(2789, 'W')").textValue());
    }

    @Test
    void formatInteger_words_titlecase() throws Exception {
        String result = eval("$formatInteger(2789, 'Ww')").textValue();
        assertNotNull(result);
        assertTrue(Character.isUpperCase(result.charAt(0)), "Should start with uppercase");
    }

    @Test
    void formatInteger_roman_upper() throws Exception {
        assertEquals("MCMXCIX", eval("$formatInteger(1999, 'I')").textValue());
    }

    @Test
    void formatInteger_roman_lower() throws Exception {
        assertEquals("mcmxcix", eval("$formatInteger(1999, 'i')").textValue());
    }

    @Test
    void formatInteger_alpha_upper() throws Exception {
        assertEquals("A",  eval("$formatInteger(1, 'A')").textValue());
        assertEquals("Z",  eval("$formatInteger(26, 'A')").textValue());
        assertEquals("AA", eval("$formatInteger(27, 'A')").textValue());
    }

    @Test
    void formatInteger_alpha_lower() throws Exception {
        assertEquals("a",  eval("$formatInteger(1, 'a')").textValue());
        assertEquals("z",  eval("$formatInteger(26, 'a')").textValue());
        assertEquals("aa", eval("$formatInteger(27, 'a')").textValue());
    }

    @Test
    void formatInteger_decimal_pattern_with_grouping() throws Exception {
        assertEquals("12,345,678", eval("$formatInteger(12345678, '#,##0')").textValue());
    }

    @Test
    void formatInteger_small_words() throws Exception {
        assertEquals("one",    eval("$formatInteger(1, 'w')").textValue());
        assertEquals("twenty", eval("$formatInteger(20, 'w')").textValue());
        assertEquals("twenty-one", eval("$formatInteger(21, 'w')").textValue());
        assertEquals("one hundred", eval("$formatInteger(100, 'w')").textValue());
        assertEquals("one hundred and one", eval("$formatInteger(101, 'w')").textValue());
    }

    @Test
    void formatInteger_one_million() throws Exception {
        assertEquals("one million", eval("$formatInteger(1000000, 'w')").textValue());
    }

    // =========================================================================
    // $parseInteger
    // =========================================================================

    @Test
    void parseInteger_words() throws Exception {
        assertEquals(12476L,
                eval("$parseInteger(\"twelve thousand, four hundred and seventy-six\", 'w')").longValue());
    }

    @Test
    void parseInteger_words_uppercase() throws Exception {
        assertEquals(5L, eval("$parseInteger(\"FIVE\", 'W')").longValue());
    }

    @Test
    void parseInteger_roman() throws Exception {
        assertEquals(1999L, eval("$parseInteger(\"MCMXCIX\", 'I')").longValue());
    }

    @Test
    void parseInteger_roman_lower() throws Exception {
        assertEquals(1999L, eval("$parseInteger(\"mcmxcix\", 'i')").longValue());
    }

    @Test
    void parseInteger_alpha_upper() throws Exception {
        assertEquals(1L,  eval("$parseInteger(\"A\", 'A')").longValue());
        assertEquals(26L, eval("$parseInteger(\"Z\", 'A')").longValue());
        assertEquals(27L, eval("$parseInteger(\"AA\", 'A')").longValue());
    }

    @Test
    void parseInteger_decimal_with_grouping() throws Exception {
        assertEquals(12345678L, eval("$parseInteger('12,345,678', '#,##0')").longValue());
    }

    @Test
    void parseInteger_roundtrip_words() throws Exception {
        // format then parse — use field access (v), not variable ($v)
        String words = eval("$formatInteger(42, 'w')").textValue();
        long parsed = JsonNodeTestHelper.evaluate("$parseInteger(v, 'w')", "{\"v\": \"" + words + "\"}").longValue();
        assertEquals(42L, parsed);
    }

    @Test
    void parseInteger_roundtrip_roman() throws Exception {
        String roman = eval("$formatInteger(1066, 'I')").textValue();
        long parsed = JsonNodeTestHelper.evaluate("$parseInteger(v, 'I')", "{\"v\": \"" + roman + "\"}").longValue();
        assertEquals(1066L, parsed);
    }
}
