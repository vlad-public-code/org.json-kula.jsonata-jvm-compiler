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
    // wordsToDigits — cardinals
    // -------------------------------------------------------------------------

    @Test
    void wordsToDigits_cardinalUnits() {
        assertEquals("1", WordNumbers.wordsToDigits("one"));
        assertEquals("2", WordNumbers.wordsToDigits("two"));
        assertEquals("3", WordNumbers.wordsToDigits("three"));
        assertEquals("4", WordNumbers.wordsToDigits("four"));
        assertEquals("5", WordNumbers.wordsToDigits("five"));
        assertEquals("6", WordNumbers.wordsToDigits("six"));
        assertEquals("7", WordNumbers.wordsToDigits("seven"));
        assertEquals("8", WordNumbers.wordsToDigits("eight"));
        assertEquals("9", WordNumbers.wordsToDigits("nine"));
    }

    @Test
    void wordsToDigits_cardinalTeens() {
        assertEquals("10", WordNumbers.wordsToDigits("ten"));
        assertEquals("11", WordNumbers.wordsToDigits("eleven"));
        assertEquals("12", WordNumbers.wordsToDigits("twelve"));
        assertEquals("13", WordNumbers.wordsToDigits("thirteen"));
        assertEquals("14", WordNumbers.wordsToDigits("fourteen"));
        assertEquals("15", WordNumbers.wordsToDigits("fifteen"));
        assertEquals("16", WordNumbers.wordsToDigits("sixteen"));
        assertEquals("17", WordNumbers.wordsToDigits("seventeen"));
        assertEquals("18", WordNumbers.wordsToDigits("eighteen"));
        assertEquals("19", WordNumbers.wordsToDigits("nineteen"));
    }

    @Test
    void wordsToDigits_cardinalTens() {
        assertEquals("20", WordNumbers.wordsToDigits("twenty"));
        assertEquals("30", WordNumbers.wordsToDigits("thirty"));
        assertEquals("40", WordNumbers.wordsToDigits("forty"));
        assertEquals("50", WordNumbers.wordsToDigits("fifty"));
        assertEquals("60", WordNumbers.wordsToDigits("sixty"));
        assertEquals("70", WordNumbers.wordsToDigits("seventy"));
        assertEquals("80", WordNumbers.wordsToDigits("eighty"));
        assertEquals("90", WordNumbers.wordsToDigits("ninety"));
    }

    @Test
    void wordsToDigits_cardinalComposite() {
        assertEquals("21",   WordNumbers.wordsToDigits("twenty-one"));
        assertEquals("99",   WordNumbers.wordsToDigits("ninety-nine"));
        assertEquals("105",  WordNumbers.wordsToDigits("one hundred and five"));
        assertEquals("365",  WordNumbers.wordsToDigits("three hundred and sixty five"));
        assertEquals("2018", WordNumbers.wordsToDigits("two thousand and eighteen"));
        assertEquals("1000", WordNumbers.wordsToDigits("one thousand"));
    }

    // -------------------------------------------------------------------------
    // wordsToDigits — ordinals (unit, teen, ten, multiplier)
    // -------------------------------------------------------------------------

    @Test
    void wordsToDigits_ordinalUnits() {
        assertEquals("1", WordNumbers.wordsToDigits("first"));
        assertEquals("2", WordNumbers.wordsToDigits("second"));
        assertEquals("3", WordNumbers.wordsToDigits("third"));
        assertEquals("4", WordNumbers.wordsToDigits("fourth"));
        assertEquals("5", WordNumbers.wordsToDigits("fifth"));
        assertEquals("6", WordNumbers.wordsToDigits("sixth"));
        assertEquals("7", WordNumbers.wordsToDigits("seventh"));
        assertEquals("8", WordNumbers.wordsToDigits("eighth"));
        assertEquals("9", WordNumbers.wordsToDigits("ninth"));
    }

    @Test
    void wordsToDigits_ordinalTeens() {
        assertEquals("10", WordNumbers.wordsToDigits("tenth"));
        assertEquals("11", WordNumbers.wordsToDigits("eleventh"));
        assertEquals("12", WordNumbers.wordsToDigits("twelfth"));
        assertEquals("13", WordNumbers.wordsToDigits("thirteenth"));
        assertEquals("14", WordNumbers.wordsToDigits("fourteenth"));
        assertEquals("15", WordNumbers.wordsToDigits("fifteenth"));
        assertEquals("16", WordNumbers.wordsToDigits("sixteenth"));
        assertEquals("17", WordNumbers.wordsToDigits("seventeenth"));
        assertEquals("18", WordNumbers.wordsToDigits("eighteenth"));
        assertEquals("19", WordNumbers.wordsToDigits("nineteenth"));
    }

    @Test
    void wordsToDigits_ordinalTens() {
        assertEquals("20", WordNumbers.wordsToDigits("twentieth"));
        assertEquals("30", WordNumbers.wordsToDigits("thirtieth"));
        assertEquals("40", WordNumbers.wordsToDigits("fortieth"));
        assertEquals("50", WordNumbers.wordsToDigits("fiftieth"));
        assertEquals("60", WordNumbers.wordsToDigits("sixtieth"));
        assertEquals("70", WordNumbers.wordsToDigits("seventieth"));
        assertEquals("80", WordNumbers.wordsToDigits("eightieth"));
        assertEquals("90", WordNumbers.wordsToDigits("ninetieth"));
    }

    @Test
    void wordsToDigits_ordinalMultipliers() {
        assertEquals("100",  WordNumbers.wordsToDigits("one hundredth"));
        assertEquals("200",  WordNumbers.wordsToDigits("two hundredth"));
        assertEquals("1000", WordNumbers.wordsToDigits("one thousandth"));
        assertEquals("2000", WordNumbers.wordsToDigits("two thousandth"));
    }

    @Test
    void wordsToDigits_ordinalComposite() {
        assertEquals("21",  WordNumbers.wordsToDigits("twenty-first"));
        assertEquals("32",  WordNumbers.wordsToDigits("thirty-second"));
        assertEquals("65",  WordNumbers.wordsToDigits("sixty-fifth"));
        assertEquals("12",  WordNumbers.wordsToDigits("twelfth"));
        assertEquals("20",  WordNumbers.wordsToDigits("twentieth"));
        assertEquals("101", WordNumbers.wordsToDigits("one hundred and first"));
        assertEquals("365", WordNumbers.wordsToDigits("three hundred and sixty-fifth"));
        assertEquals("2001", WordNumbers.wordsToDigits("two thousand and first"));
    }

    // -------------------------------------------------------------------------
    // wordsToDigits — no-match
    // -------------------------------------------------------------------------

    @Test
    void wordsToDigits_noMatch_returnsOriginal() {
        assertEquals("hello",    WordNumbers.wordsToDigits("hello"));
        assertEquals("2018",     WordNumbers.wordsToDigits("2018"));
        assertEquals("january",  WordNumbers.wordsToDigits("january"));
    }
}
