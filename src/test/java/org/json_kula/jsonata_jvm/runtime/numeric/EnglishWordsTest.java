package org.json_kula.jsonata_jvm.runtime.numeric;

import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EnglishWords}: cardinal/ordinal generation and parsing.
 *
 * <p>Key regression: {@link #parseWords_one_million_one_thousand()} guards against
 * the accumulatedMagnitude bug where "one thousand" after "one million" was
 * multiplied instead of added, yielding 10^9 instead of 1,001,000.
 */
class EnglishWordsTest {

    // =========================================================================
    // toWords — cardinal
    // =========================================================================

    @Test void toWords_zero() throws Exception {
        assertEquals("zero", EnglishWords.toWords(0, false));
    }

    @Test void toWords_one() throws Exception {
        assertEquals("one", EnglishWords.toWords(1, false));
    }

    @Test void toWords_nineteen() throws Exception {
        assertEquals("nineteen", EnglishWords.toWords(19, false));
    }

    @Test void toWords_twenty() throws Exception {
        assertEquals("twenty", EnglishWords.toWords(20, false));
    }

    @Test void toWords_twenty_one() throws Exception {
        assertEquals("twenty-one", EnglishWords.toWords(21, false));
    }

    @Test void toWords_one_hundred() throws Exception {
        assertEquals("one hundred", EnglishWords.toWords(100, false));
    }

    @Test void toWords_one_hundred_and_one() throws Exception {
        assertEquals("one hundred and one", EnglishWords.toWords(101, false));
    }

    @Test void toWords_two_thousand_seven_eighty_nine() throws Exception {
        assertEquals("two thousand, seven hundred and eighty-nine",
                EnglishWords.toWords(2789, false));
    }

    @Test void toWords_one_million() throws Exception {
        assertEquals("one million", EnglishWords.toWords(1_000_000, false));
    }

    @Test void toWords_negative() throws Exception {
        assertEquals("minus one", EnglishWords.toWords(-1, false));
    }

    @Test void toWords_negative_hundred() throws Exception {
        assertEquals("minus one hundred", EnglishWords.toWords(-100, false));
    }

    // =========================================================================
    // toWords — ordinal
    // =========================================================================

    @Test void toWords_ordinal_zero() throws Exception {
        assertEquals("zeroth", EnglishWords.toWords(0, true));
    }

    @Test void toWords_ordinal_1() throws Exception {
        assertEquals("first", EnglishWords.toWords(1, true));
    }

    @Test void toWords_ordinal_2() throws Exception {
        assertEquals("second", EnglishWords.toWords(2, true));
    }

    @Test void toWords_ordinal_3() throws Exception {
        assertEquals("third", EnglishWords.toWords(3, true));
    }

    @Test void toWords_ordinal_11() throws Exception {
        assertEquals("eleventh", EnglishWords.toWords(11, true));
    }

    @Test void toWords_ordinal_12() throws Exception {
        assertEquals("twelfth", EnglishWords.toWords(12, true));
    }

    @Test void toWords_ordinal_20() throws Exception {
        assertEquals("twentieth", EnglishWords.toWords(20, true));
    }

    @Test void toWords_ordinal_21() throws Exception {
        assertEquals("twenty-first", EnglishWords.toWords(21, true));
    }

    @Test void toWords_ordinal_1000() throws Exception {
        assertEquals("one thousandth", EnglishWords.toWords(1000, true));
    }

    // =========================================================================
    // parseWords — regression and correctness
    // =========================================================================

    @Test void parseWords_zero() throws Exception {
        assertEquals(0L, EnglishWords.parseWords("zero"));
    }

    @Test void parseWords_one() throws Exception {
        assertEquals(1L, EnglishWords.parseWords("one"));
    }

    @Test void parseWords_compound() throws Exception {
        // Existing round-trip value used by NumericFunctionsTest
        assertEquals(12_476L, EnglishWords.parseWords(
                "twelve thousand, four hundred and seventy-six"));
    }

    /** Regression: prior algorithm multiplied total by thousand instead of adding. */
    @Test void parseWords_one_million_one_thousand() throws Exception {
        assertEquals(1_001_000L, EnglishWords.parseWords("one million one thousand"));
    }

    @Test void parseWords_one_billion_two_million_three_thousand() throws Exception {
        assertEquals(1_002_003_000L,
                EnglishWords.parseWords("one billion two million three thousand"));
    }

    /** Ascending magnitudes: "one thousand trillion" means 1000 × 10^12 = 10^15. */
    @Test void parseWords_one_thousand_trillion() throws Exception {
        assertEquals(1_000_000_000_000_000L,
                EnglishWords.parseWords("one thousand trillion"));
    }

    @Test void parseWords_negative() throws Exception {
        assertEquals(-5L, EnglishWords.parseWords("minus five"));
    }

    @Test void parseWords_unknown_token_throws() {
        assertThrows(RuntimeEvaluationException.class,
                () -> EnglishWords.parseWords("gazillion"));
    }

    // =========================================================================
    // titleCase
    // =========================================================================

    @Test void titleCase_and_stays_lowercase() {
        // "and" should remain lowercase; other words capitalised
        String result = EnglishWords.titleCase("one hundred and one");
        assertEquals("One Hundred and One", result);
    }

    @Test void titleCase_hyphenated() {
        assertEquals("Twenty-One", EnglishWords.titleCase("twenty-one"));
    }
}
