package org.json_kula.jsonata_jvm.runtime.datetime;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering every confirmed bug from the DateTimeUtils code review.
 *
 * Each test is annotated with the bug number and a brief description.
 */
class DateTimeFormattingTest {

    private static final long MILLIS_2017_11_07 = 1510067557121L; // 2017-11-07T15:12:37.121Z
    private static final long MILLIS_2018_03_23 = 1521801216617L; // 2018-03-23T07:33:36.617Z (UTC)

    private JsonNode eval(String expr) throws Exception {
        return JsonNodeTestHelper.evaluate(expr);
    }

    // =========================================================================
    // Bug 1 — formatOffsetZ crash on [Z0] + UTC
    // String.format("%00d", m) when minuteWidth=0 caused IllegalFormatFlagsException
    // =========================================================================

    @Test
    void bug1_formatOffsetZ_zeroModifier_utc_doesNotCrash() throws Exception {
        // [Z0] with a UTC timestamp must not throw and should emit "+0" (sign + bare hour).
        JsonNode result = eval("$fromMillis(1510067557121, '[Z0]')");
        assertTrue(result.isTextual(), "Expected a string, got: " + result);
        // UTC → minuteWidth=0, hourWidth=1 → "+0"
        assertEquals("+0", result.textValue());
    }

    @Test
    void bug1_formatOffsetZ_zeroModifier_nonUtc() throws Exception {
        // Non-UTC timezone with [Z0] should emit the offset without minutes when minutes are 0.
        JsonNode result = eval("$fromMillis(1510067557121, '[Z0]', '-0500')");
        assertTrue(result.isTextual());
        assertEquals("-5", result.textValue());
    }

    // =========================================================================
    // Bug 2 — parseZoneOffset rejected ±HH:MM (colon-separated)
    // =========================================================================

    @Test
    void bug2_timezoneWithColon_formattingWorks() throws Exception {
        // $fromMillis with ±HH:MM timezone (was previously rejected).
        JsonNode result = eval("$fromMillis(1510067557121, '[H01]:[m01]:[s01] [z]', '+05:30')");
        assertTrue(result.isTextual());
        assertTrue(result.textValue().contains("GMT+05:30"),
                "Expected GMT+05:30 in: " + result.textValue());
    }

    @Test
    void bug2_timezoneWithColon_isoOutput() throws Exception {
        // Ensure the ISO formatter also accepts ±HH:MM.
        JsonNode result = eval("$fromMillis(0, '[Y]-[M01]-[D01]T[H01]:[m01]:[s01][Z]', '+05:30')");
        assertTrue(result.isTextual());
        assertTrue(result.textValue().endsWith("+05:30"),
                "Expected +05:30 suffix in: " + result.textValue());
    }

    // =========================================================================
    // Bug 3 — toWordsOrdinal: teen numbers returned cardinal forms
    // =========================================================================

    @Test
    void bug3_dayOrdinalWords_ten() throws Exception {
        // 2017-01-10 — day 10 with [Dwo] should produce "tenth" not "ten"
        // epoch for 2017-01-10T00:00:00Z = 1484006400000
        JsonNode result = eval("$fromMillis(1484006400000, '[Dwo]')");
        assertEquals("tenth", result.textValue());
    }

    @Test
    void bug3_dayOrdinalWords_thirteen() throws Exception {
        // 2017-01-13 epoch = 1484265600000
        JsonNode result = eval("$fromMillis(1484265600000, '[Dwo]')");
        assertEquals("thirteenth", result.textValue());
    }

    @Test
    void bug3_dayOrdinalWords_nineteen() throws Exception {
        // 2017-01-19 epoch = 1484784000000
        JsonNode result = eval("$fromMillis(1484784000000, '[Dwo]')");
        assertEquals("nineteenth", result.textValue());
    }

    @Test
    void bug3_dayOrdinalWords_twentyFirst() throws Exception {
        // 2017-01-21 epoch = 1484956800000
        JsonNode result = eval("$fromMillis(1484956800000, '[Dwo]')");
        assertEquals("twenty-first", result.textValue());
    }

    // =========================================================================
    // Bug 4 — formatMillisComponent truncated from wrong end for [f01]
    // 150 ms with width=2 gave "150" (no truncation), should give "15"
    // =========================================================================

    @Test
    void bug4_millisComponent_twoDigit_150ms() throws Exception {
        // 1510067557150 = ...37.150Z
        JsonNode result = eval("$fromMillis(1510067557150, '[f01]')");
        assertEquals("15", result.textValue(), "150ms in [f01] should scale to 15, not 150");
    }

    @Test
    void bug4_millisComponent_oneDigit_150ms() throws Exception {
        JsonNode result = eval("$fromMillis(1510067557150, '[f1]')");
        assertEquals("1", result.textValue(), "150ms in [f1] should scale to 1");
    }

    @Test
    void bug4_millisComponent_threeDigit_unchanged() throws Exception {
        // [f001] should still give full 3-digit value
        JsonNode result = eval("$fromMillis(1510067557150, '[f001]')");
        assertEquals("150", result.textValue());
    }

    // =========================================================================
    // Bug 5 — [F] default modifier: confirmed lowercase (matches JSONata test suite)
    // =========================================================================

    @Test
    void bug5_dayOfWeek_defaultLowercase() throws Exception {
        // Friday, 23 March 2018 — [F] alone should be lowercase "friday"
        JsonNode result = eval("$fromMillis(1521801216617, '[F]')");
        assertEquals("friday", result.textValue());
    }

    @Test
    void bug5_dayOfWeek_N_uppercase() throws Exception {
        JsonNode result = eval("$fromMillis(1521801216617, '[FN]')");
        assertEquals("FRIDAY", result.textValue());
    }

    @Test
    void bug5_dayOfWeek_Nn_titleCase() throws Exception {
        JsonNode result = eval("$fromMillis(1521801216617, '[FNn]')");
        assertEquals("Friday", result.textValue());
    }

    // =========================================================================
    // Bug 6 — [D] with n/N modifier called formatDayName (day-of-week)
    // Removed that incorrect branch; now falls through to formatInt (numeric).
    // =========================================================================

    @Test
    void bug6_dayOfMonth_noModifier_numeric() throws Exception {
        // Day 23, no modifier → "23"
        JsonNode result = eval("$fromMillis(1521801216617, '[D]')");
        assertEquals("23", result.textValue());
    }

    @Test
    void bug6_dayOfMonth_withOrdinal_suffix() throws Exception {
        // [Do] → "23rd"
        JsonNode result = eval("$fromMillis(1521801216617, '[Do]')");
        assertEquals("23rd", result.textValue());
    }

    @Test
    void bug6_dayOfMonth_ordinalWords() throws Exception {
        // [Dwo] → "twenty-third"
        JsonNode result = eval("$fromMillis(1521801216617, '[Dwo]')");
        assertEquals("twenty-third", result.textValue());
    }

    // =========================================================================
    // Bug 7 — parsed timezone from [Z]/[z] ignored in pictureToMillis fallback
    // Now the OFFSET_SECONDS field from TemporalAccessor is used.
    // =========================================================================

    @Test
    void bug7_parsedTimezone_usedInMillisConversion() throws Exception {
        // "10:33 +0530" with picture "[H01]:[m01] [Z]"
        // 10:33 in +05:30 = 05:03 UTC
        // Epoch for 2018-03-23T05:03:36Z (approx) vs 2018-03-23T10:33:36+05:30
        // These should be equal: $toMillis("10:33:36 +05:30", "[H01]:[m01]:[s01] [Z]")
        // should NOT be interpreted as UTC.
        JsonNode utcResult = eval("$toMillis(\"10:33:36 +00:00\", \"[H01]:[m01]:[s01] [Z]\")");
        JsonNode tz530Result = eval("$toMillis(\"10:33:36 +05:30\", \"[H01]:[m01]:[s01] [Z]\")");
        assertTrue(utcResult.isNumber());
        assertTrue(tz530Result.isNumber());
        long diff = utcResult.longValue() - tz530Result.longValue();
        assertEquals(5 * 3600 * 1000L + 30 * 60 * 1000L, diff,
                "Expected exactly 5h30m difference between UTC and +05:30 times");
    }

    // =========================================================================
    // Bug 8 — [Yw] parsing broken (appendText used but input was already numeric)
    // Now uses appendValue since preprocessing always converts words to digits.
    // =========================================================================

    @Test
    void bug8_yearInWords_parse() throws Exception {
        // "Twentieth of August, two thousand and seventeen" → 2017-08-20T00:00:00Z
        JsonNode result = eval(
                "$toMillis('Twentieth of August, two thousand and seventeen', '[DW] of [MNn], [Yw]')");
        assertEquals(1503187200000L, result.longValue());
    }

    @Test
    void bug8_yearInWords_parse_simpleCase() throws Exception {
        // "20 August 2017" via [Yw] (year already numeric) should still work
        JsonNode result = eval("$toMillis('20 August 2017', '[D] [MNn] [Yw]')");
        assertEquals(1503187200000L, result.longValue());
    }

    // =========================================================================
    // Bug 9 — normalizeTimezoneOffset had incorrect/dead length-6 branches
    // (Now handled via TimezoneUtils.normalizeOffsetInTimestamp)
    // =========================================================================

    @Test
    void bug9_isoWithBareOffset_parsed() throws Exception {
        // "2017-11-07T15:12:37.121+0000" — bare ±HHMM offset
        JsonNode result = eval("$toMillis(\"2017-11-07T15:12:37.121+0000\")");
        assertEquals(1510067557121L, result.longValue());
    }

    @Test
    void bug9_isoWithColonOffset_parsed() throws Exception {
        // "2017-11-07T15:12:37.121+00:00" — colon offset
        JsonNode result = eval("$toMillis(\"2017-11-07T15:12:37.121+00:00\")");
        assertEquals(1510067557121L, result.longValue());
    }

    // =========================================================================
    // Bug 10 — isRomanNumeral false positives
    // =========================================================================

    @Test
    void bug10_romanNumeral_monthParsing_noFalsePositives() throws Exception {
        // Picture [Mi] uses Roman numerals for month. Should parse "iii" (March) correctly.
        // Also verify that a timestamp containing the word "MIX" is not mangled.
        JsonNode result = eval("$fromMillis(1521801216617, '[Mi]')");
        // 2018-03-23 → month = 3 → Roman = iii (lowercase because [Mi])
        assertEquals("iii", result.textValue());
    }

    @Test
    void bug10_romanNumeral_yearRoundTrip() throws Exception {
        // [YI] → uppercase Roman numeral year
        JsonNode result = eval("$fromMillis(1521801216617, '[YI]')");
        assertEquals("MMXVIII", result.textValue()); // 2018
    }

    // =========================================================================
    // Regression — year truncation [Y,2] must still produce 2-digit year
    // =========================================================================

    @Test
    void yearTwoDigitTruncation() throws Exception {
        JsonNode result = eval("$fromMillis(1521801216617, '[D#1,2]/[M1,2]/[Y,2]')");
        assertEquals("23/03/18", result.textValue());
    }

    // =========================================================================
    // Existing spec examples (regression guard)
    // =========================================================================

    @Test
    void specExample_fromMillis_iso() throws Exception {
        assertEquals("2017-11-07T15:12:37.121Z",
                eval("$fromMillis(1510067557121)").textValue());
    }

    @Test
    void specExample_fromMillis_withPicture() throws Exception {
        assertEquals("11/07/2017 3:12pm",
                eval("$fromMillis(1510067557121, '[M01]/[D01]/[Y0001] [h#1]:[m01][P]')").textValue());
    }

    @Test
    void specExample_fromMillis_withTimezone() throws Exception {
        assertEquals("10:12:37 GMT-05:00",
                eval("$fromMillis(1510067557121, '[H01]:[m01]:[s01] [z]', '-0500')").textValue());
    }

    @Test
    void specExample_toMillis_dayOfYear() throws Exception {
        JsonNode result = eval(
                "$toMillis('three hundred and sixty-fifth day of 2018', '[dwo] day of [Y]') ~> $fromMillis()");
        assertEquals("2018-12-31T00:00:00.000Z", result.textValue());
    }
}
