package org.json_kula.jsonata_jvm.runtime.numeric;

import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IntegerPicture}: ordinal suffixes, Roman numerals,
 * alphabetic labels, decimal grouping, and integer parsing.
 *
 * <p>Key regressions covered:
 * <ul>
 *   <li>{@link #ordinalSuffix_negative_1st()} — was "th" before {@code Math.abs} fix
 *   <li>{@link #ordinalSuffix_negative_11th()} — was correct but now explicitly pinned
 * </ul>
 */
class IntegerPictureTest {

    // =========================================================================
    // ordinalSuffix
    // =========================================================================

    @Test void ordinalSuffix_1()  { assertEquals("st", IntegerPicture.ordinalSuffix(1)); }
    @Test void ordinalSuffix_2()  { assertEquals("nd", IntegerPicture.ordinalSuffix(2)); }
    @Test void ordinalSuffix_3()  { assertEquals("rd", IntegerPicture.ordinalSuffix(3)); }
    @Test void ordinalSuffix_4()  { assertEquals("th", IntegerPicture.ordinalSuffix(4)); }
    @Test void ordinalSuffix_11() { assertEquals("th", IntegerPicture.ordinalSuffix(11)); }
    @Test void ordinalSuffix_12() { assertEquals("th", IntegerPicture.ordinalSuffix(12)); }
    @Test void ordinalSuffix_13() { assertEquals("th", IntegerPicture.ordinalSuffix(13)); }
    @Test void ordinalSuffix_21() { assertEquals("st", IntegerPicture.ordinalSuffix(21)); }

    /** Regression: negative -1 was returning "th" instead of "st" before Math.abs fix. */
    @Test void ordinalSuffix_negative_1st() {
        assertEquals("st", IntegerPicture.ordinalSuffix(-1));
    }

    @Test void ordinalSuffix_negative_2nd() {
        assertEquals("nd", IntegerPicture.ordinalSuffix(-2));
    }

    /** -11 must remain "th" (the 11–13 exception applies to absolute value). */
    @Test void ordinalSuffix_negative_11th() {
        assertEquals("th", IntegerPicture.ordinalSuffix(-11));
    }

    // =========================================================================
    // toRoman
    // =========================================================================

    @Test void toRoman_1()    { assertEquals("I",       IntegerPicture.toRoman(1)); }
    @Test void toRoman_4()    { assertEquals("IV",      IntegerPicture.toRoman(4)); }
    @Test void toRoman_9()    { assertEquals("IX",      IntegerPicture.toRoman(9)); }
    @Test void toRoman_1999() { assertEquals("MCMXCIX", IntegerPicture.toRoman(1999)); }
    @Test void toRoman_4000() { assertEquals("MMMM",    IntegerPicture.toRoman(4000)); }

    /** Spec: $formatInteger(0, 'I') must return "" (zero has no Roman representation). */
    @Test void toRoman_zero_returns_empty() {
        assertEquals("", IntegerPicture.toRoman(0));
    }

    @Test void toRoman_negative_throws() {
        assertThrows(RuntimeEvaluationException.class, () -> IntegerPicture.toRoman(-1));
    }

    @Test void toRoman_too_large_throws() {
        assertThrows(RuntimeEvaluationException.class, () -> IntegerPicture.toRoman(4_000_000));
    }

    // =========================================================================
    // parseRoman
    // =========================================================================

    @Test void parseRoman_I()       { assertEquals(1L,    IntegerPicture.parseRoman("I")); }
    @Test void parseRoman_IV()      { assertEquals(4L,    IntegerPicture.parseRoman("IV")); }
    @Test void parseRoman_MCMXCIX() { assertEquals(1999L, IntegerPicture.parseRoman("MCMXCIX")); }
    @Test void parseRoman_lower()   { assertEquals(1999L, IntegerPicture.parseRoman("mcmxcix")); }
    @Test void parseRoman_empty()   { assertEquals(0L,    IntegerPicture.parseRoman("")); }

    @Test void parseRoman_invalid_char_throws() {
        assertThrows(RuntimeEvaluationException.class, () -> IntegerPicture.parseRoman("IZI"));
    }

    // =========================================================================
    // toAlpha / parseAlpha
    // =========================================================================

    @Test void toAlpha_1()  { assertEquals("A",  IntegerPicture.toAlpha(1, true)); }
    @Test void toAlpha_26() { assertEquals("Z",  IntegerPicture.toAlpha(26, true)); }
    @Test void toAlpha_27() { assertEquals("AA", IntegerPicture.toAlpha(27, true)); }
    @Test void toAlpha_lower() { assertEquals("a", IntegerPicture.toAlpha(1, false)); }

    @Test void toAlpha_zero_throws() {
        assertThrows(RuntimeEvaluationException.class, () -> IntegerPicture.toAlpha(0, true));
    }

    @Test void parseAlpha_A()  { assertEquals(1L,  IntegerPicture.parseAlpha("A")); }
    @Test void parseAlpha_Z()  { assertEquals(26L, IntegerPicture.parseAlpha("Z")); }
    @Test void parseAlpha_AA() { assertEquals(27L, IntegerPicture.parseAlpha("AA")); }
    @Test void parseAlpha_lower() { assertEquals(1L, IntegerPicture.parseAlpha("a")); }

    @Test void parseAlpha_invalid_char_throws() {
        assertThrows(RuntimeEvaluationException.class, () -> IntegerPicture.parseAlpha("1"));
    }

    // =========================================================================
    // format — decimal picture with negative number
    // =========================================================================

    /** Regression: custom grouping path lost the minus sign for negative numbers. */
    @Test void format_negative_standard_pattern() {
        // Standard DecimalFormat path — was always correct
        assertEquals("-1,234", IntegerPicture.format(-1234, "#,##0"));
    }

    @Test void format_negative_word() {
        assertEquals("minus one", IntegerPicture.format(-1, "w"));
    }

    // =========================================================================
    // format — ordinal
    // =========================================================================

    @Test void format_ordinal_1st() {
        assertEquals("1st", IntegerPicture.format(1, "#;o"));
    }

    @Test void format_ordinal_2nd() {
        assertEquals("2nd", IntegerPicture.format(2, "#;o"));
    }

    @Test void format_ordinal_3rd() {
        assertEquals("3rd", IntegerPicture.format(3, "#;o"));
    }

    @Test void format_ordinal_11th() {
        assertEquals("11th", IntegerPicture.format(11, "#;o"));
    }

    /** Regression: was "-1th" because ordinalSuffix(-1 % 10) == -1 matched "default". */
    @Test void format_ordinal_neg1st() {
        assertEquals("-1st", IntegerPicture.format(-1, "#;o"));
    }

    @Test void format_ordinal_neg11th() {
        assertEquals("-11th", IntegerPicture.format(-11, "#;o"));
    }

    // =========================================================================
    // parse — roundtrip
    // =========================================================================

    @Test void parse_decimal_roundtrip() {
        assertEquals(12_345_678L, IntegerPicture.parse("12,345,678", "#,##0"));
    }

    @Test void parse_roman_roundtrip() {
        assertEquals(1066L, IntegerPicture.parse(IntegerPicture.toRoman(1066).toUpperCase(), "I"));
    }

    @Test void parse_alpha_roundtrip() {
        assertEquals(27L, IntegerPicture.parse("AA", "A"));
    }

    @Test void parse_empty_roman_is_zero() {
        assertEquals(0L, IntegerPicture.parse("", "I"));
    }
}
