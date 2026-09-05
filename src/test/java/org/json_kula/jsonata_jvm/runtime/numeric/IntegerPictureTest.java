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

    /**
      * The sign never reaches toRoman: {@code format} strips it and prepends it to the
      * result, so {@code $formatInteger(-1, "I")} is "-I" in the reference.
      */
    @Test void toRoman_negative_is_empty() {
        assertEquals("", IntegerPicture.toRoman(-1));
    }

    /** Above 3,999 the reference simply repeats M; there is no upper bound. */
    @Test void toRoman_beyond_3999_repeats_m() {
        assertEquals("M".repeat(4000), IntegerPicture.toRoman(4_000_000));
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

    /** The reference's loop simply does not run for zero: $formatInteger(0, "a") is "". */
    @Test void toAlpha_zero_is_empty() {
        assertEquals("", IntegerPicture.toAlpha(0, true));
    }

    @Test void parseAlpha_A()  { assertEquals(1L,  IntegerPicture.parseAlpha("A")); }
    @Test void parseAlpha_Z()  { assertEquals(26L, IntegerPicture.parseAlpha("Z")); }
    @Test void parseAlpha_AA() { assertEquals(27L, IntegerPicture.parseAlpha("AA")); }
    @Test void parseAlpha_lower() { assertEquals(1L, IntegerPicture.parseAlpha("a")); }

    /**
      * $parseInteger does not validate: the reference runs the picture's parse function
      * over whatever it is given, so "1" against an "A" picture is the offset -15.
      */
    @Test void parseAlpha_invalid_char_is_unvalidated() throws Exception {
        assertEquals(-15.0, IntegerPicture.parse("1", "A"));
    }

    // =========================================================================
    // format — decimal picture with negative number
    // =========================================================================

    /** Regression: custom grouping path lost the minus sign for negative numbers. */
    @Test void format_negative_standard_pattern() {
        // Standard DecimalFormat path — was always correct
        assertEquals("-1,234", IntegerPicture.format(-1234, "#,##0"));
    }

    /** The sign is a literal "-" prefix for every primary format, words included. */
    @Test void format_negative_word() {
        assertEquals("-one", IntegerPicture.format(-1, "w"));
    }

    // =========================================================================
    // format — ordinal
    // =========================================================================

    /**
      * A picture with only optional digits has no mandatory digit, so it is a "numbering
      * sequence" — implementation-defined, and unsupported by the reference. "#;o" is
      * therefore D3130, not "1st"; the ordinal pictures that work are "1;o", "01;o" and
      * the like.
      */
    @Test void format_ordinal_optional_digits_only_is_D3130() {
        RuntimeEvaluationException e = assertThrows(RuntimeEvaluationException.class,
                () -> IntegerPicture.format(1, "#;o"));
        assertEquals("D3130", e.getErrorCode());
    }

    @Test void format_ordinal_1st() {
        assertEquals("1st", IntegerPicture.format(1, "1;o"));
    }

    @Test void format_ordinal_2nd() {
        assertEquals("2nd", IntegerPicture.format(2, "1;o"));
    }

    @Test void format_ordinal_3rd() {
        assertEquals("3rd", IntegerPicture.format(3, "1;o"));
    }

    @Test void format_ordinal_11th() {
        assertEquals("11th", IntegerPicture.format(11, "1;o"));
    }

    /** The suffix is chosen from the magnitude's digits; the sign is prepended after. */
    @Test void format_ordinal_neg1st() {
        assertEquals("-1st", IntegerPicture.format(-1, "1;o"));
    }

    @Test void format_ordinal_neg11th() {
        assertEquals("-11th", IntegerPicture.format(-11, "1;o"));
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
