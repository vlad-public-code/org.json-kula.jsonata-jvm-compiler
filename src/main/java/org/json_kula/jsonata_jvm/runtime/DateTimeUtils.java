package org.json_kula.jsonata_jvm.runtime;

import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Map;

/**
 * Utility methods for the JSONata date/time built-in functions.
 *
 * <p>Implements a subset of the XPath/XQuery {@code fn:format-dateTime} picture-string
 * notation used by {@code $fromMillis}, {@code $toMillis}, and the optional
 * {@code picture} parameter of {@code $now}.
 *
 * <h2>Supported picture-string components</h2>
 * <pre>
 *   [Y]  / [Y0001]  — year (4-digit zero-padded by default)
 *   [M]  / [M01]    — month as decimal (2-digit zero-padded by default)
 *   [MN] / [Mn]     — month name (full, title / lower case)
 *   [D]  / [D01]    — day of month (2-digit zero-padded by default)
 *   [d]  / [d001]   — day of year
 *   [F]  / [Fn]     — day-of-week name (full, title / lower case)
 *   [H]  / [H01]    — hour 0–23 (2-digit zero-padded by default)
 *   [h]  / [h#1]    — hour 1–12 (no leading zero when modifier starts with #)
 *   [m]  / [m01]    — minute (2-digit zero-padded by default)
 *   [s]  / [s01]    — second (2-digit zero-padded by default)
 *   [f]  / [f001]   — milliseconds (3-digit zero-padded by default)
 *   [P]             — am / pm (lower case)
 *   [Z]             — timezone offset, e.g. +05:30 or Z for UTC
 *   [z]             — timezone as GMT±HH:MM, or GMT for UTC
 * </pre>
 *
 * <p>Literal {@code [} and {@code ]} inside a picture string are escaped as
 * {@code [[} and {@code ]]}.
 */
final class DateTimeUtils {

    private DateTimeUtils() {}

    // =========================================================================
    // Formatting: millis → string
    // =========================================================================

    /**
     * Formats {@code millis} as an ISO 8601 UTC string (the default JSONata format).
     * Example: {@code "2017-11-07T15:12:37.121Z"}.
     */
    static String millisToIso(long millis) {
        // ISO_INSTANT omits the fractional-second part when nanoseconds == 0,
        // which is the correct ISO 8601 behaviour.
        return java.time.format.DateTimeFormatter.ISO_INSTANT
                .format(Instant.ofEpochMilli(millis));
    }

    /**
     * Formats {@code millis} using an XPath/XQuery picture string.
     *
     * @param millis   milliseconds since the Unix epoch
     * @param picture  XPath/XQuery fn:format-dateTime picture string
     * @param timezone optional timezone offset in {@code ±HHMM} notation, or {@code null} for UTC
     */
    static String millisToPicture(long millis, String picture, String timezone)
            throws RuntimeEvaluationException {
        ZoneOffset offset = (timezone != null && !timezone.isEmpty())
                ? parseZoneOffset(timezone) : ZoneOffset.UTC;
        ZonedDateTime dt = Instant.ofEpochMilli(millis).atZone(offset);
        return applyPicture(dt, picture);
    }

    // =========================================================================
    // Parsing: string → millis
    // =========================================================================

    /**
     * Parses an ISO 8601 timestamp string and returns milliseconds since the Unix epoch.
     */
    static long isoToMillis(String timestamp) throws RuntimeEvaluationException {
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (DateTimeParseException e) {
            // Try date-only ISO 8601 (YYYY-MM-DD)
            try {
                return LocalDate.parse(timestamp).atStartOfDay(ZoneOffset.UTC)
                        .toInstant().toEpochMilli();
            } catch (DateTimeParseException e2) {
                throw new RuntimeEvaluationException(
                        "$toMillis: invalid ISO 8601 timestamp: " + timestamp);
            }
        }
    }

    /**
     * Parses {@code timestamp} using an XPath/XQuery picture string and returns
     * milliseconds since the Unix epoch.
     */
    static long pictureToMillis(String timestamp, String picture)
            throws RuntimeEvaluationException {
        java.time.format.DateTimeFormatter formatter = pictureToFormatter(picture);
        try {
            TemporalAccessor ta = formatter.parse(timestamp);
            try {
                return Instant.from(ta).toEpochMilli();
            } catch (DateTimeException e) {
                // No timezone in picture — treat as UTC
                return LocalDateTime.from(ta).toInstant(ZoneOffset.UTC).toEpochMilli();
            }
        } catch (DateTimeParseException e) {
            throw new RuntimeEvaluationException(
                    "$toMillis: cannot parse '" + timestamp + "' with picture '" + picture + "': "
                            + e.getMessage());
        }
    }

    // =========================================================================
    // Picture-string formatting implementation
    // =========================================================================

    private static String applyPicture(ZonedDateTime dt, String picture)
            throws RuntimeEvaluationException {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = picture.length();
        while (i < len) {
            char c = picture.charAt(i);
            if (c == '[') {
                if (i + 1 < len && picture.charAt(i + 1) == '[') {
                    sb.append('[');
                    i += 2;
                } else {
                    int j = picture.indexOf(']', i + 1);
                    if (j < 0) {
                        throw new RuntimeEvaluationException(
                                "Unclosed '[' in picture string at position " + i);
                    }
                    sb.append(formatComponent(dt, picture.substring(i + 1, j)));
                    i = j + 1;
                }
            } else if (c == ']' && i + 1 < len && picture.charAt(i + 1) == ']') {
                sb.append(']');
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static String formatComponent(ZonedDateTime dt, String spec)
            throws RuntimeEvaluationException {
        if (spec.isEmpty()) return "";
        char d = spec.charAt(0);
        String mod = spec.length() > 1 ? spec.substring(1) : "";
        return switch (d) {
            case 'Y' -> formatInt(dt.getYear(), mod, 4);
            case 'M' -> {
                if (!mod.isEmpty() && (mod.charAt(0) == 'N' || mod.charAt(0) == 'n'))
                    yield formatMonthName(dt, mod);
                yield formatInt(dt.getMonthValue(), mod, 2);
            }
            case 'D' -> formatInt(dt.getDayOfMonth(), mod, 2);
            case 'd' -> formatInt(dt.getDayOfYear(), mod, 3);
            case 'F' -> formatDayName(dt, mod);
            case 'H' -> formatInt(dt.getHour(), mod, 2);
            case 'h' -> {
                int h = dt.getHour() % 12;
                yield formatInt(h == 0 ? 12 : h, mod, 2);
            }
            case 'm' -> formatInt(dt.getMinute(), mod, 2);
            case 's' -> formatInt(dt.getSecond(), mod, 2);
            case 'f' -> formatMillisComponent(dt.getNano() / 1_000_000, mod);
            case 'P' -> dt.getHour() < 12 ? "am" : "pm";
            case 'Z' -> formatOffsetZ(dt.getOffset());
            case 'z' -> formatOffsetName(dt.getOffset());
            default  -> throw new RuntimeEvaluationException(
                    "Unknown picture-string component: [" + spec + "]");
        };
    }

    /**
     * Formats an integer value according to the picture modifier.
     *
     * <ul>
     *   <li>{@code ""} or {@code "1"} — no leading zeros</li>
     *   <li>{@code "#1"} — no leading zeros (explicit)</li>
     *   <li>{@code "01"} — 2-digit zero-padded</li>
     *   <li>{@code "001"} — 3-digit zero-padded</li>
     *   <li>{@code "0001"} — 4-digit zero-padded</li>
     * </ul>
     */
    private static String formatInt(int value, String mod, int defaultWidth) {
        if (mod.isEmpty() || mod.equals("1") || mod.startsWith("#")) {
            return String.valueOf(value);
        }
        // Width = number of digit characters in modifier (e.g., "01"→2, "0001"→4)
        int width = (int) mod.chars().filter(Character::isDigit).count();
        if (width == 0) width = defaultWidth;
        return String.format("%0" + width + "d", value);
    }

    private static String formatMillisComponent(int millis, String mod) {
        // Default width 3 (milliseconds); modifier "001"→3, "01"→2, etc.
        int width = (int) mod.chars().filter(Character::isDigit).count();
        if (width == 0) width = 3;
        return String.format("%0" + width + "d", millis);
    }

    private static String formatMonthName(ZonedDateTime dt, String mod) {
        String name = dt.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return mod.startsWith("n") ? name.toLowerCase(Locale.ENGLISH) : name;
    }

    private static String formatDayName(ZonedDateTime dt, String mod) {
        String name = dt.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return mod.startsWith("n") ? name.toLowerCase(Locale.ENGLISH) : name;
    }

    /** Formats timezone offset as {@code +HH:MM}, {@code -HH:MM}, or {@code Z}. */
    private static String formatOffsetZ(ZoneOffset offset) {
        if (offset.getTotalSeconds() == 0) return "Z";
        int totalMins = offset.getTotalSeconds() / 60;
        int h = Math.abs(totalMins) / 60;
        int m = Math.abs(totalMins) % 60;
        return String.format("%s%02d:%02d", totalMins >= 0 ? "+" : "-", h, m);
    }

    /** Formats timezone offset as {@code GMT±HH:MM} or {@code GMT}. */
    private static String formatOffsetName(ZoneOffset offset) {
        if (offset.getTotalSeconds() == 0) return "GMT";
        int totalMins = offset.getTotalSeconds() / 60;
        int h = Math.abs(totalMins) / 60;
        int m = Math.abs(totalMins) % 60;
        return String.format("GMT%s%02d:%02d", totalMins >= 0 ? "+" : "-", h, m);
    }

    // =========================================================================
    // Picture-string → DateTimeFormatter (for parsing)
    // =========================================================================

    private static java.time.format.DateTimeFormatter pictureToFormatter(String picture)
            throws RuntimeEvaluationException {
        DateTimeFormatterBuilder b = new DateTimeFormatterBuilder().parseCaseInsensitive();
        int i = 0;
        int len = picture.length();
        while (i < len) {
            char c = picture.charAt(i);
            if (c == '[') {
                if (i + 1 < len && picture.charAt(i + 1) == '[') {
                    b.appendLiteral('[');
                    i += 2;
                } else {
                    int j = picture.indexOf(']', i + 1);
                    if (j < 0) throw new RuntimeEvaluationException(
                            "Unclosed '[' in picture string at position " + i);
                    appendFormatterComponent(b, picture.substring(i + 1, j));
                    i = j + 1;
                }
            } else if (c == ']' && i + 1 < len && picture.charAt(i + 1) == ']') {
                b.appendLiteral(']');
                i += 2;
            } else {
                // Collect run of literal characters
                int j = i;
                while (j < len && picture.charAt(j) != '[' && !(picture.charAt(j) == ']' && j + 1 < len && picture.charAt(j + 1) == ']'))
                    j++;
                b.appendLiteral(picture.substring(i, j));
                i = j;
            }
        }
        return b.toFormatter(Locale.ENGLISH);
    }

    private static void appendFormatterComponent(DateTimeFormatterBuilder b, String spec)
            throws RuntimeEvaluationException {
        if (spec.isEmpty()) return;
        char d = spec.charAt(0);
        String mod = spec.length() > 1 ? spec.substring(1) : "";
        int width = (int) mod.chars().filter(Character::isDigit).count();

        switch (d) {
            case 'Y' -> b.appendValue(ChronoField.YEAR, width > 0 ? width : 4);
            case 'M' -> {
                if (!mod.isEmpty() && (mod.charAt(0) == 'N' || mod.charAt(0) == 'n'))
                    b.appendText(ChronoField.MONTH_OF_YEAR);
                else b.appendValue(ChronoField.MONTH_OF_YEAR, width > 0 ? width : 2);
            }
            case 'D' -> b.appendValue(ChronoField.DAY_OF_MONTH, width > 0 ? width : 2);
            case 'd' -> b.appendValue(ChronoField.DAY_OF_YEAR, width > 0 ? width : 3);
            case 'F' -> b.appendText(ChronoField.DAY_OF_WEEK);
            case 'H' -> b.appendValue(ChronoField.HOUR_OF_DAY, width > 0 ? width : 2);
            case 'h' -> b.appendValue(ChronoField.CLOCK_HOUR_OF_AMPM,
                    mod.startsWith("#") ? 1 : Math.max(1, width));
            case 'm' -> b.appendValue(ChronoField.MINUTE_OF_HOUR, width > 0 ? width : 2);
            case 's' -> b.appendValue(ChronoField.SECOND_OF_MINUTE, width > 0 ? width : 2);
            case 'f' -> b.appendValue(ChronoField.MILLI_OF_SECOND, width > 0 ? width : 3);
            case 'P' -> b.appendText(ChronoField.AMPM_OF_DAY, Map.of(0L, "am", 1L, "pm"));
            case 'Z', 'z' -> b.appendOffsetId();
            default -> throw new RuntimeEvaluationException(
                    "Unknown picture-string component: [" + spec + "]");
        }
    }

    // =========================================================================
    // Timezone parsing
    // =========================================================================

    /**
     * Parses a timezone string in {@code ±HHMM} notation (e.g. {@code "-0500"},
     * {@code "+0530"}) into a {@link ZoneOffset}.
     */
    static ZoneOffset parseZoneOffset(String tz) throws RuntimeEvaluationException {
        if (tz == null || tz.isEmpty() || tz.equals("Z")) return ZoneOffset.UTC;
        try {
            char sign = tz.charAt(0);
            if (sign != '+' && sign != '-')
                throw new RuntimeEvaluationException("Invalid timezone: " + tz);
            int h = Integer.parseInt(tz.substring(1, 3));
            int m = Integer.parseInt(tz.substring(3, 5));
            int totalSeconds = (h * 60 + m) * 60;
            return ZoneOffset.ofTotalSeconds(sign == '-' ? -totalSeconds : totalSeconds);
        } catch (RuntimeEvaluationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeEvaluationException("Invalid timezone: " + tz);
        }
    }
}
