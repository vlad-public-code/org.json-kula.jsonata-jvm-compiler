package org.json_kula.jsonata_jvm.runtime.datetime;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimezoneUtilsTest {

    // -------------------------------------------------------------------------
    // parseZoneOffset — fix: ±HH:MM was rejected; now accepted
    // -------------------------------------------------------------------------

    @Test
    void parseZoneOffset_utcVariants() {
        assertEquals(ZoneOffset.UTC, TimezoneUtils.parseZoneOffset(null));
        assertEquals(ZoneOffset.UTC, TimezoneUtils.parseZoneOffset(""));
        assertEquals(ZoneOffset.UTC, TimezoneUtils.parseZoneOffset("Z"));
        assertEquals(ZoneOffset.UTC, TimezoneUtils.parseZoneOffset("UTC"));
        assertEquals(ZoneOffset.UTC, TimezoneUtils.parseZoneOffset("0000"));
        assertEquals(ZoneOffset.UTC, TimezoneUtils.parseZoneOffset("+0000"));
        assertEquals(ZoneOffset.UTC, TimezoneUtils.parseZoneOffset("-0000"));
    }

    @Test
    void parseZoneOffset_hmhmFormat() {
        // Original supported format: ±HHMM
        assertEquals(ZoneOffset.ofHoursMinutes(-5, 0),  TimezoneUtils.parseZoneOffset("-0500"));
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30),  TimezoneUtils.parseZoneOffset("+0530"));
    }

    @Test
    void parseZoneOffset_hmColonFormat() {
        // Fix: ±HH:MM was previously rejected with an exception.
        assertEquals(ZoneOffset.ofHoursMinutes(-5, 0),  TimezoneUtils.parseZoneOffset("-05:00"));
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30),  TimezoneUtils.parseZoneOffset("+05:30"));
        assertEquals(ZoneOffset.ofHoursMinutes(0, 0),   TimezoneUtils.parseZoneOffset("+00:00"));
    }

    @Test
    void parseZoneOffset_gmtFormat() {
        assertEquals(ZoneOffset.UTC,                     TimezoneUtils.parseZoneOffset("GMT"));
        assertEquals(ZoneOffset.ofHoursMinutes(-5, 0),   TimezoneUtils.parseZoneOffset("GMT-05:00"));
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30),   TimezoneUtils.parseZoneOffset("GMT+5:30"));
        assertEquals(ZoneOffset.ofHoursMinutes(1, 0),    TimezoneUtils.parseZoneOffset("GMT+1"));
    }

    @Test
    void parseZoneOffset_invalidThrows() {
        assertThrows(Exception.class, () -> TimezoneUtils.parseZoneOffset("invalid"));
        assertThrows(Exception.class, () -> TimezoneUtils.parseZoneOffset("X05:00"));
    }

    // -------------------------------------------------------------------------
    // normalizeOffsetInTimestamp — fix: dead length-6 branches removed
    // -------------------------------------------------------------------------

    @Test
    void normalizeOffset_bareHhmm() {
        // +0530 → +05:30
        assertEquals("2020-01-01T10:30:00+05:30",
                TimezoneUtils.normalizeOffsetInTimestamp("2020-01-01T10:30:00+0530"));
        assertEquals("2020-01-01T10:30:00-05:00",
                TimezoneUtils.normalizeOffsetInTimestamp("2020-01-01T10:30:00-0500"));
    }

    @Test
    void normalizeOffset_alreadyHasColon_unchanged() {
        String ts = "2020-01-01T10:30:00+05:30";
        assertEquals(ts, TimezoneUtils.normalizeOffsetInTimestamp(ts));
    }

    @Test
    void normalizeOffset_endsWithZ_unchanged() {
        String ts = "2020-01-01T10:30:00Z";
        assertEquals(ts, TimezoneUtils.normalizeOffsetInTimestamp(ts));
    }

    @Test
    void normalizeOffset_noTimezone_unchanged() {
        String ts = "2020-01-01T10:30:00";
        assertEquals(ts, TimezoneUtils.normalizeOffsetInTimestamp(ts));
    }
}
