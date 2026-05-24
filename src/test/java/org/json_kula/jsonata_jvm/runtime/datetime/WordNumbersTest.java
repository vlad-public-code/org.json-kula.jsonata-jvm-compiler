package org.json_kula.jsonata_jvm.runtime.datetime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WordNumbersTest {

    // -------------------------------------------------------------------------
    // toOrdinal — fix: teen ordinals were cardinal; exact multiples missing "th"
    // -------------------------------------------------------------------------

    @Test
    void toOrdinal_irregulars() {
        assertEquals("first",   WordNumbers.toOrdinal(1));
        assertEquals("second",  WordNumbers.toOrdinal(2));
        assertEquals("third",   WordNumbers.toOrdinal(3));
        assertEquals("fifth",   WordNumbers.toOrdinal(5));
        assertEquals("eighth",  WordNumbers.toOrdinal(8));
        assertEquals("ninth",   WordNumbers.toOrdinal(9));
        assertEquals("twelfth", WordNumbers.toOrdinal(12));
    }

    @Test
    void toOrdinal_teensWereCardinal_nowOrdinal() {
        // Fix: 10, 11, 13–19 previously returned the cardinal word (ten, eleven, …).
        assertEquals("tenth",       WordNumbers.toOrdinal(10));
        assertEquals("eleventh",    WordNumbers.toOrdinal(11));
        assertEquals("thirteenth",  WordNumbers.toOrdinal(13));
        assertEquals("fourteenth",  WordNumbers.toOrdinal(14));
        assertEquals("fifteenth",   WordNumbers.toOrdinal(15));
        assertEquals("sixteenth",   WordNumbers.toOrdinal(16));
        assertEquals("seventeenth", WordNumbers.toOrdinal(17));
        assertEquals("eighteenth",  WordNumbers.toOrdinal(18));
        assertEquals("nineteenth",  WordNumbers.toOrdinal(19));
    }

    @Test
    void toOrdinal_exactTens() {
        assertEquals("twentieth",  WordNumbers.toOrdinal(20));
        assertEquals("thirtieth",  WordNumbers.toOrdinal(30));
        assertEquals("fortieth",   WordNumbers.toOrdinal(40));
        assertEquals("ninetieth",  WordNumbers.toOrdinal(90));
    }

    @Test
    void toOrdinal_compound() {
        assertEquals("twenty-first",  WordNumbers.toOrdinal(21));
        assertEquals("thirty-second", WordNumbers.toOrdinal(32));
        assertEquals("sixty-fifth",   WordNumbers.toOrdinal(65));
    }

    @Test
    void toOrdinal_hundreds() {
        // Fix: exact multiples of 100 previously ended without "th".
        assertEquals("one hundredth",  WordNumbers.toOrdinal(100));
        assertEquals("two hundredth",  WordNumbers.toOrdinal(200));
    }

    @Test
    void toOrdinal_thousands() {
        // Fix: exact multiple of 1000 previously ended without "th".
        assertEquals("one thousandth", WordNumbers.toOrdinal(1000));
    }

    @Test
    void toOrdinal_dayRange() {
        // Sanity check for the actual usage range 1–366
        for (int n = 1; n <= 31; n++) {
            String ord = WordNumbers.toOrdinal(n);
            assertFalse(ord.isEmpty(), "Empty ordinal for n=" + n);
            assertFalse(Character.isDigit(ord.charAt(0)), "Starts with digit for n=" + n);
        }
    }

    @Test
    void toOrdinal_nonPositive() {
        assertEquals("0",  WordNumbers.toOrdinal(0));
        assertEquals("-1", WordNumbers.toOrdinal(-1));
    }

    // -------------------------------------------------------------------------
    // toCardinal
    // -------------------------------------------------------------------------

    @Test
    void toCardinal_basic() {
        assertEquals("one",   WordNumbers.toCardinal(1));
        assertEquals("twelve", WordNumbers.toCardinal(12));
        assertEquals("twenty-three", WordNumbers.toCardinal(23));
        assertEquals("one hundred and five", WordNumbers.toCardinal(105));
        assertEquals("two thousand and eighteen", WordNumbers.toCardinal(2018));
    }

    // -------------------------------------------------------------------------
    // wordsToDigits
    // -------------------------------------------------------------------------

    @Test
    void wordsToDigits_cardinal() {
        assertEquals("1",    WordNumbers.wordsToDigits("one"));
        assertEquals("12",   WordNumbers.wordsToDigits("twelve"));
        assertEquals("2018", WordNumbers.wordsToDigits("two thousand and eighteen"));
        assertEquals("365",  WordNumbers.wordsToDigits("three hundred and sixty five"));
    }

    @Test
    void wordsToDigits_ordinalInput() {
        assertEquals("20",  WordNumbers.wordsToDigits("twentieth"));
        assertEquals("12",  WordNumbers.wordsToDigits("twelfth"));
        assertEquals("365", WordNumbers.wordsToDigits("three hundred and sixty-fifth"));
    }

    @Test
    void wordsToDigits_noMatch_returnsOriginal() {
        assertEquals("hello", WordNumbers.wordsToDigits("hello"));
        assertEquals("2018",  WordNumbers.wordsToDigits("2018")); // already numeric → unchanged
    }
}
