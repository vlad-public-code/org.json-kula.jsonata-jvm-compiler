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

    @Test void toWords_zero() {
        assertEquals("zero", EnglishWords.toWords(0, false));
    }

    @Test void toWords_one() {
        assertEquals("one", EnglishWords.toWords(1, false));
    }

    @Test void toWords_nineteen() {
        assertEquals("nineteen", EnglishWords.toWords(19, false));
    }

    @Test void toWords_twenty() {
        assertEquals("twenty", EnglishWords.toWords(20, false));
    }

    @Test void toWords_twenty_one() {
        assertEquals("twenty-one", EnglishWords.toWords(21, false));
    }

    @Test void toWords_one_hundred() {
        assertEquals("one hundred", EnglishWords.toWords(100, false));
    }

    @Test void toWords_one_hundred_and_one() {
        assertEquals("one hundred and one", EnglishWords.toWords(101, false));
    }

    @Test void toWords_two_thousand_seven_eighty_nine() {
        assertEquals("two thousand, seven hundred and eighty-nine",
                EnglishWords.toWords(2789, false));
    }

    @Test void toWords_one_million() {
        assertEquals("one million", EnglishWords.toWords(1_000_000, false));
    }

    @Test void toWords_negative() {
        assertEquals("minus one", EnglishWords.toWords(-1, false));
    }

    @Test void toWords_negative_hundred() {
        assertEquals("minus one hundred", EnglishWords.toWords(-100, false));
    }

    // =========================================================================
    // toWords — ordinal
    // =========================================================================

    @Test void toWords_ordinal_zero() {
        assertEquals("zeroth", EnglishWords.toWords(0, true));
    }

    @Test void toWords_ordinal_1() {
        assertEquals("first", EnglishWords.toWords(1, true));
    }

    @Test void toWords_ordinal_2() {
        assertEquals("second", EnglishWords.toWords(2, true));
    }

    @Test void toWords_ordinal_3() {
        assertEquals("third", EnglishWords.toWords(3, true));
    }

    @Test void toWords_ordinal_11() {
        assertEquals("eleventh", EnglishWords.toWords(11, true));
    }

    @Test void toWords_ordinal_12() {
        assertEquals("twelfth", EnglishWords.toWords(12, true));
    }

    @Test void toWords_ordinal_20() {
        assertEquals("twentieth", EnglishWords.toWords(20, true));
    }

    @Test void toWords_ordinal_21() {
        assertEquals("twenty-first", EnglishWords.toWords(21, true));
    }

    @Test void toWords_ordinal_1000() {
        assertEquals("one thousandth", EnglishWords.toWords(1000, true));
    }

    // =========================================================================
    // parseWords — regression and correctness
    // =========================================================================

    @Test void parseWords_zero() {
        assertEquals(0L, EnglishWords.parseWords("zero"));
    }

    @Test void parseWords_one() {
        assertEquals(1L, EnglishWords.parseWords("one"));
    }

    @Test void parseWords_compound() {
        // Existing round-trip value used by NumericFunctionsTest
        assertEquals(12_476L, EnglishWords.parseWords(
                "twelve thousand, four hundred and seventy-six"));
    }

    /** Regression: prior algorithm multiplied total by thousand instead of adding. */
    @Test void parseWords_one_million_one_thousand() {
        assertEquals(1_001_000L, EnglishWords.parseWords("one million one thousand"));
    }

    @Test void parseWords_one_billion_two_million_three_thousand() {
        assertEquals(1_002_003_000L,
                EnglishWords.parseWords("one billion two million three thousand"));
    }

    /** Ascending magnitudes: "one thousand trillion" means 1000 × 10^12 = 10^15. */
    @Test void parseWords_one_thousand_trillion() {
        assertEquals(1_000_000_000_000_000L,
                EnglishWords.parseWords("one thousand trillion"));
    }

    @Test void parseWords_negative() {
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
