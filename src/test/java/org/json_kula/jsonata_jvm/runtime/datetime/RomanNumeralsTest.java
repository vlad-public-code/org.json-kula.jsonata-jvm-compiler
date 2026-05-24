package org.json_kula.jsonata_jvm.runtime.datetime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RomanNumeralsTest {

    // -------------------------------------------------------------------------
    // isValid — fix: was based on character membership; now structural
    // -------------------------------------------------------------------------

    @Test
    void isValid_standardNumerals() {
        assertTrue(RomanNumerals.isValid("I"));
        assertTrue(RomanNumerals.isValid("IV"));
        assertTrue(RomanNumerals.isValid("XII"));
        assertTrue(RomanNumerals.isValid("MMXXIV"));
        assertTrue(RomanNumerals.isValid("MCMXCIX")); // 1999
    }

    @Test
    void isValid_lowercaseInput() {
        assertTrue(RomanNumerals.isValid("xii"));
        assertTrue(RomanNumerals.isValid("iv"));
    }

    @Test
    void isValid_falsePositivesPrevented() {
        // Fix: these matched the old "^[IVXLCDM]+$" regex but are NOT structurally valid Roman numerals.
        assertFalse(RomanNumerals.isValid("DIM"));    // D + I + M — wrong ordering
        assertFalse(RomanNumerals.isValid("CIVIL"));  // not a valid numeral structure
        assertFalse(RomanNumerals.isValid("MILD"));   // M + I + L + D — wrong ordering
        assertFalse(RomanNumerals.isValid("VIM"));    // V + I + M — M after I is invalid
        assertFalse(RomanNumerals.isValid("IIM"));    // subtractive notation requires single-letter
        assertFalse(RomanNumerals.isValid(""));
        assertFalse(RomanNumerals.isValid(null));
    }

    @Test
    void isValid_mix_isValidRoman() {
        // MIX = M (1000) + IX (9) = 1009, which IS a valid Roman numeral.
        // The old char-set check also accepted it; the new structural check also accepts it.
        assertTrue(RomanNumerals.isValid("MIX"));
        assertEquals(1009, RomanNumerals.toArabic("MIX"));
    }

    // -------------------------------------------------------------------------
    // toArabic
    // -------------------------------------------------------------------------

    @Test
    void toArabic_basicValues() {
        assertEquals(1,    RomanNumerals.toArabic("I"));
        assertEquals(4,    RomanNumerals.toArabic("IV"));
        assertEquals(9,    RomanNumerals.toArabic("IX"));
        assertEquals(12,   RomanNumerals.toArabic("XII"));
        assertEquals(40,   RomanNumerals.toArabic("XL"));
        assertEquals(1999, RomanNumerals.toArabic("MCMXCIX"));
        assertEquals(2024, RomanNumerals.toArabic("MMXXIV"));
    }

    @Test
    void toArabic_caseInsensitive() {
        assertEquals(12, RomanNumerals.toArabic("xii"));
        assertEquals(4,  RomanNumerals.toArabic("iv"));
    }

    // -------------------------------------------------------------------------
    // toRoman
    // -------------------------------------------------------------------------

    @Test
    void toRoman_basic() {
        assertEquals("I",       RomanNumerals.toRoman(1));
        assertEquals("XII",     RomanNumerals.toRoman(12));
        assertEquals("MCMXCIX", RomanNumerals.toRoman(1999));
        assertEquals("MMXXIV",  RomanNumerals.toRoman(2024));
    }

    @Test
    void toRoman_outOfRange() {
        // Outside 1–3999: return decimal string
        assertEquals("0",    RomanNumerals.toRoman(0));
        assertEquals("4000", RomanNumerals.toRoman(4000));
        assertEquals("-1",   RomanNumerals.toRoman(-1));
    }

    @Test
    void roundTrip() {
        for (int n = 1; n <= 3999; n++) {
            assertEquals(n, RomanNumerals.toArabic(RomanNumerals.toRoman(n)),
                    "Round-trip failed for n=" + n);
        }
    }
}
