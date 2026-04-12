package org.json_kula.jsonata_jvm.runtime;

import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.WeekFields;
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
        return millisToIso(millis, "UTC");
    }

    static String millisToIso(long millis, String timezone) {
        Instant instant = Instant.ofEpochMilli(millis);
        ZoneOffset offset = timezone == null || timezone.isEmpty() || "UTC".equals(timezone)
                ? ZoneOffset.UTC
                : parseZoneOffset(timezone);
        ZonedDateTime dt = instant.atZone(offset);
        String tzSuffix = offset == ZoneOffset.UTC ? "Z" : dt.getOffset().getId().replace("Z", "");
        return String.format("%04d-%02d-%02dT%02d:%02d:%02d.%03d%s",
                dt.getYear(),
                dt.getMonthValue(),
                dt.getDayOfMonth(),
                dt.getHour(),
                dt.getMinute(),
                dt.getSecond(),
                instant.getNano() / 1_000_000,
                tzSuffix);
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
        ZoneOffset offset;
        if (timezone == null || timezone.isEmpty()) {
            offset = ZoneOffset.UTC;
        } else {
            offset = parseZoneOffset(timezone);
        }
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
            String normalized = normalizeTimezoneOffset(timestamp);
            if (!normalized.equals(timestamp)) {
                try {
                    return Instant.parse(normalized).toEpochMilli();
                } catch (DateTimeParseException ignored) {
                }
            }
            // Try date-only ISO 8601 (YYYY-MM-DD) or partial dates
            try {
                if (timestamp.matches("\\d{4}")) {
                    // Just year: 2018 -> 2018-01-01T00:00:00Z
                    return LocalDate.of(Integer.parseInt(timestamp), 1, 1)
                            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                } else if (timestamp.matches("\\d{4}-\\d{2}")) {
                    // Year-month: 2018-02 -> 2018-02-01T00:00:00Z
                    String[] parts = timestamp.split("-");
                    return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1)
                            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                }
                return LocalDate.parse(timestamp).atStartOfDay(ZoneOffset.UTC)
                        .toInstant().toEpochMilli();
            } catch (DateTimeParseException e2) {
                throw new RuntimeEvaluationException(
                        "D3110: $toMillis: invalid ISO 8601 timestamp: " + timestamp);
            }
        }
    }

    private static String normalizeTimezoneOffset(String timestamp) {
        // Handle timezone offsets without colons: +0000 -> +00:00, +000000 -> +00:00:00
        int len = timestamp.length();
        if (len >= 2 && (timestamp.charAt(len - 1) == 'Z' || timestamp.charAt(len - 1) == 'z')) {
            return timestamp;
        }
        int lastPlus = -1;
        for (int i = len - 1; i >= 0; i--) {
            char c = timestamp.charAt(i);
            if (c == '+' || c == '-') {
                lastPlus = i;
                break;
            }
            if (Character.isDigit(c) || c == ':') {
                continue;
            } else {
                break;
            }
        }
        if (lastPlus < 0) return timestamp;
        int tzStart = lastPlus;
        int tzEnd = len;
        String tz = timestamp.substring(tzStart, tzEnd);
        if (!tz.contains(":")) {
            String normalizedTz;
            if (tz.length() == 5) {
                normalizedTz = tz.substring(0, 3) + ":" + tz.substring(3);
            } else if (tz.length() == 6 && tz.startsWith("+")) {
                normalizedTz = "+" + tz.substring(1, 3) + ":" + tz.substring(3);
            } else if (tz.length() == 6 && tz.startsWith("-")) {
                normalizedTz = "-" + tz.substring(1, 3) + ":" + tz.substring(3);
            } else {
                return timestamp;
            }
            return timestamp.substring(0, tzStart) + normalizedTz;
        }
        return timestamp;
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
                // No timezone or no time components in picture — treat as UTC with defaults
                int year = ta.isSupported(ChronoField.YEAR) ? ta.get(ChronoField.YEAR) : 1970;
                int month = ta.isSupported(ChronoField.MONTH_OF_YEAR) ? ta.get(ChronoField.MONTH_OF_YEAR) : 1;
                int day = ta.isSupported(ChronoField.DAY_OF_MONTH) ? ta.get(ChronoField.DAY_OF_MONTH) : 1;
                int hour = ta.isSupported(ChronoField.HOUR_OF_DAY) ? ta.get(ChronoField.HOUR_OF_DAY) : 0;
                int minute = ta.isSupported(ChronoField.MINUTE_OF_HOUR) ? ta.get(ChronoField.MINUTE_OF_HOUR) : 0;
                int second = ta.isSupported(ChronoField.SECOND_OF_MINUTE) ? ta.get(ChronoField.SECOND_OF_MINUTE) : 0;
                return LocalDateTime.of(year, month, day, hour, minute, second)
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
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
        if (countUnclosed(picture) > 0) {
            throw new RuntimeEvaluationException(
                    "D3135: Unclosed '[' in picture string");
        }
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
                                "D3135: Unclosed '[' in picture string");
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

    private static int countUnclosed(String spec) {
        int count = 0;
        for (int k = 0; k < spec.length(); k++) {
            if (k < spec.length() - 1 && spec.charAt(k) == '[' && spec.charAt(k + 1) == '[') {
                k++; // skip escaped [[
            } else if (k < spec.length() - 1 && spec.charAt(k) == ']' && spec.charAt(k + 1) == ']') {
                k++; // skip escaped ]]
            } else if (spec.charAt(k) == '[') {
                count++;
            } else if (spec.charAt(k) == ']') {
                count--;
            }
        }
        return count;
    }

    private static String formatComponent(ZonedDateTime dt, String spec)
            throws RuntimeEvaluationException {
        if (spec.isEmpty()) return "";
        // Remove whitespace from spec (JSONata ignores whitespace in variable markers)
        spec = spec.replaceAll("\\s+", "");
        char d = spec.charAt(0);
        String mod = spec.length() > 1 ? spec.substring(1) : "";
        return switch (d) {
            case 'Y' -> {
                if (!mod.isEmpty()) {
                    if (mod.equals("N") || mod.equals("n")) {
                        throw new RuntimeEvaluationException(
                                "D3133: Year name component is not supported");
                    } else if (mod.equals("I") || mod.equals("i")) {
                        String roman = toRoman(dt.getYear());
                        yield mod.equals("i") ? roman.toLowerCase() : roman;
                    } else if (mod.equals("w") || mod.equals("W")) {
                        yield toWords(dt.getYear());
                    }
                }
                yield formatInt(dt.getYear(), mod, 4);
            }
            case 'X' -> formatIsoWeekYear(dt, mod);
            case 'W' -> formatIsoWeek(dt, mod);
            case 'w' -> {
                // Calendar week of month (1-5)
                // Calculate which week of the context month
                LocalDate date = dt.toLocalDate();
                int dayOfMonth = date.getDayOfMonth();
                
                // Find Monday and Sunday of this week
                LocalDate mondayOfWeek = date;
                while (mondayOfWeek.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
                    mondayOfWeek = mondayOfWeek.minusDays(1);
                }
                
                // Determine which month is the "context month" (majority of week)
                int daysInCurrentMonth = 0;
                for (int i = 0; i < 7; i++) {
                    LocalDate day = mondayOfWeek.plusDays(i);
                    if (day.getMonthValue() == date.getMonthValue()) {
                        daysInCurrentMonth++;
                    }
                }
                
                // If more days in next month, it's week 1 of next month
                if (daysInCurrentMonth <= 3 && dayOfMonth >= 28) {
                    yield "1";
                } else if (mondayOfWeek.getMonthValue() != date.getMonthValue() && dayOfMonth <= 4) {
                    // Monday in previous month and we're in first 4 days = week 5 of previous
                    yield "5";
                } else {
                    int weekNum = (int) Math.ceil((double) dayOfMonth / 7);
                    yield formatInt(weekNum, mod, 1);
                }
            }
case 'x' -> {
                // Month name with week-of-month context
                // Use the month where MORE days of the week fall
                LocalDate date = dt.toLocalDate();
                int dayOfMonth = date.getDayOfMonth();
                
                // Find Monday and Sunday of this week
                LocalDate mondayOfWeek = date;
                while (mondayOfWeek.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
                    mondayOfWeek = mondayOfWeek.minusDays(1);
                }
                
                LocalDate sundayOfWeek = mondayOfWeek.plusDays(6);
                
                // Count days in previous, current, and next months
                LocalDate firstOfMonth = date.withDayOfMonth(1);
                int daysInCurrentMonth = 0;
                int daysInPrevMonth = 0;
                int daysInNextMonth = 0;
                
                for (int i = 0; i < 7; i++) {
                    LocalDate day = mondayOfWeek.plusDays(i);
                    if (day.isBefore(firstOfMonth)) {
                        daysInPrevMonth++;
                    } else if (day.getMonthValue() == date.getMonthValue()) {
                        daysInCurrentMonth++;
                    } else {
                        daysInNextMonth++;
                    }
                }
                
                Month contextMonth;
                if (daysInPrevMonth > daysInCurrentMonth && daysInPrevMonth > daysInNextMonth) {
                    contextMonth = date.minusMonths(1).getMonth();
                } else if (daysInNextMonth > daysInCurrentMonth && daysInNextMonth > daysInPrevMonth) {
                    contextMonth = date.plusMonths(1).getMonth();
                } else {
                    contextMonth = date.getMonth();
                }
                
                if (!mod.isEmpty() && mod.contains("N")) {
                    String name = contextMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                    if (mod.startsWith("n")) {
                        yield name.toLowerCase(Locale.ENGLISH);
                    }
                    yield name;
                }
                yield formatMonthName(dt, mod);
            }
            case 'M' -> {
                if (!mod.isEmpty() && Character.isLetter(mod.charAt(0))) {
                    // Letter modifier could be: n/N/nN (name) or a/A (alphabetic numbering)
                    if (mod.equals("a") || mod.equals("A")) {
                        // Alphabetic numbering: month as letter (1=a, 2=b, ...)
                        int month = dt.getMonthValue();
                        yield toAlphabetic(month, !mod.equals("a"));
                    } else if (mod.charAt(0) == 'n' || mod.charAt(0) == 'N') {
                        yield formatMonthName(dt, mod);
                    }
                }
                yield formatInt(dt.getMonthValue(), mod, 2);
            }
            case 'D' -> {
                if (!mod.isEmpty()) {
                    // Check for words modifier 'w' or 'wo'
                    if (mod.contains("w")) {
                        yield toWordsOrdinal(dt.getDayOfMonth());
                    }
                    // Check for ordinal modifier 'o' (without 'w')
                    else if (mod.contains("o")) {
                        yield formatOrdinal(dt.getDayOfMonth());
                    }
                    // Check for letter modifier (n/N/nN or a/A)
                    else if (Character.isLetter(mod.charAt(0))) {
                        if (mod.equals("a") || mod.equals("A")) {
                            // Alphabetic numbering: day as letter 
                            int day = dt.getDayOfMonth();
                            yield toAlphabetic(day, !mod.equals("a"));
                        } else if (mod.charAt(0) == 'n' || mod.charAt(0) == 'N') {
                            yield formatDayName(dt, mod);
                        }
                    }
                }
                yield formatInt(dt.getDayOfMonth(), mod, 2);
            }
            case 'd' -> {
                if (!mod.isEmpty() && mod.contains("w")) {
                    yield toWordsOrdinal(dt.getDayOfYear());
                }
                yield formatInt(dt.getDayOfYear(), mod, 3);
            }
            case 'F' -> formatDayName(dt, mod);
            case 'H' -> formatInt(dt.getHour(), mod, 2);
            case 'h' -> {
                int h = dt.getHour() % 12;
                yield formatInt(h == 0 ? 12 : h, mod, 2);
            }
            case 'C' -> "ISO";
            case 'E' -> "ISO";
            case 'm' -> formatInt(dt.getMinute(), mod.isEmpty() ? "01" : mod, 2);
            case 's' -> formatInt(dt.getSecond(), mod.isEmpty() ? "01" : mod, 2);
            case 'f' -> formatMillisComponent(dt.getNano() / 1_000_000, mod);
            case 'P' -> {
                if (!mod.isEmpty() && mod.equals("N")) {
                    yield dt.getHour() < 12 ? "AM" : "PM";
                } else if (!mod.isEmpty() && mod.equals("n")) {
                    yield dt.getHour() < 12 ? "am" : "pm";
                }
                yield dt.getHour() < 12 ? "am" : "pm";
            }
            case 'Z' -> formatOffsetZ(dt.getOffset(), mod);
            case 'z' -> formatOffsetName(dt.getOffset());
            default  -> throw new RuntimeEvaluationException(
                    "Unknown picture-string component: [" + spec + "]");
        };
    }

    private static String formatIsoWeekYear(ZonedDateTime dt, String mod) {
        int weekYear = dt.get(WeekFields.ISO.weekBasedYear());
        return formatInt(weekYear, mod, 4);
    }

    private static String formatIsoWeek(ZonedDateTime dt, String mod) {
        int week = dt.get(WeekFields.ISO.weekOfWeekBasedYear());
        return formatInt(week, mod, 2);
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
     *   <li>{@code "9"} — variable width, no minimum</li>
     *   <li>{@code "9,999"} — use thousands separator</li>
     *   <li>{@code "9,999,*"} — use thousands separator with fill char</li>
     * </ul>
     */
    private static String formatInt(int value, String mod, int defaultWidth) {
        // Check for pure # without comma - return as-is
        if ((mod.isEmpty() || mod.equals("1")) || (mod.startsWith("#") && !mod.contains(","))) {
            return String.valueOf(value);
        }
        
        boolean useThousandsSep = mod.contains(",");
        
        // Parse width modifier: "Y,2" means minimum width 2, not grouped with thousands
        // Extract the number after comma if present
        int minWidth = defaultWidth;
        int maxWidth = Integer.MAX_VALUE;
        boolean hasComma = mod.contains(",");
        boolean hasZerosInMinPart = false;
        
        if (hasComma) {
            String[] parts = mod.split(",");
            // part before comma is minimum width
            if (parts.length > 0 && parts[0].length() > 0) {
                String minPart = parts[0];
                // Check for zero padding: any '0' that's NOT escaped by preceding '#'
                // "##01" → "0" is escaped → no padding
                // "01" or "001" → "0" is padding
                boolean hasPaddingZero = false;
                int i = 0;
                while (i < minPart.length()) {
                    if (minPart.charAt(i) == '0') {
                        // Check if previous char is not '#' (or it's the first char)
                        hasPaddingZero = (i == 0 || minPart.charAt(i - 1) != '#');
                        break;
                    }
                    if (minPart.charAt(i) == '#' && i + 1 < minPart.length() && minPart.charAt(i + 1) == '#') {
                        i += 2; // skip "##"
                    } else {
                        i++;
                    }
                }
                hasZerosInMinPart = hasPaddingZero;
                
                if (hasZerosInMinPart) {
                    // has zeros - count ALL digits for padding width
                    String digitsOnly = minPart.replaceAll("[^0-9#]", "").replace("#", "");
                    int width = digitsOnly.length();
                    if (width > 0) minWidth = width;
                }
            }
            // part after comma is maximum width
            if (parts.length > 1 && parts[1].length() > 0) {
                try {
                    maxWidth = Integer.parseInt(parts[1].replaceAll("[^0-9].*", ""));
                } catch (NumberFormatException ignored) {}
            }
        } else {
            // Width calculation: count digit characters but handle special cases
            int width = (int) mod.chars().filter(Character::isDigit).count();
            if (width > 0) minWidth = width;
            // check for zeros in the full modifier for truncation logic
            hasZerosInMinPart = mod.contains("0");
        }
        
        // Check for minimum width specifiers like 9
        // "9" means no minimum (just show the number as-is with separators)
        // "0" means zero-padded to minimum width
        boolean noMinWidth = mod.startsWith("9");
        
        String formatted;
        if (noMinWidth) {
            formatted = String.valueOf(value);
        } else {
            formatted = String.format("%0" + minWidth + "d", value);
        }
        
        // Apply maxWidth truncation - only if comma was used AND it's min-max format (two numbers after comma)
        // e.g., Y,2 should NOT truncate (single number = use all), but Y,2-2 should truncate (two numbers = min-max)
        // Also apply truncation if no zeros in min part (e.g., "Y,2" has no zeros, so truncate)
        boolean shouldTruncate = false;
        if (hasComma && maxWidth < Integer.MAX_VALUE) {
            String[] parts = mod.split(",");
            // Two parts = min-max format → truncate
            // One part but no zeros → truncate (e.g., "Y,2")
            boolean isMinMaxFormat = parts.length > 1 && parts[1].contains("-");
            boolean noZerosInMin = !hasZerosInMinPart;
            shouldTruncate = isMinMaxFormat || noZerosInMin;
        }
        
        if (shouldTruncate && formatted.length() > maxWidth) {
            formatted = formatted.substring(formatted.length() - maxWidth);
        }
        
        // Only apply thousands separator if explicitly requested (e.g., Y9,999,*)
        // and not for just ",2" style modifiers
        if (useThousandsSep && mod.contains("*")) {
            StringBuilder sb = new StringBuilder();
            int cnt = 0;
            for (int i = formatted.length() - 1; i >= 0; i--) {
                if (cnt > 0 && cnt % 3 == 0) {
                    sb.insert(0, ",");
                }
                sb.insert(0, formatted.charAt(i));
                cnt++;
            }
            return sb.toString();
        }
        
        return formatted;
    }

    private static String formatMillisComponent(int millis, String mod) {
        // Default width 3 (milliseconds); modifier "001"→3, "01"→2, etc.
        int width = (int) mod.chars().filter(Character::isDigit).count();
        if (width == 0) width = 3;
        return String.format("%0" + width + "d", millis);
    }

    private static String formatMonthName(ZonedDateTime dt, String mod) {
        // If empty or just number (like "01"), use numeric
        if (mod.isEmpty() || mod.matches("\\d+.*")) {
            return String.valueOf(dt.getMonthValue());
        }
        
        // Check for min-max (e.g., "3-3") - abbreviate to given length
        if (mod.contains(",")) {
            String[] parts = mod.split(",");
            if (parts.length > 1 && parts[1].contains("-")) {
                String[] range = parts[1].split("-");
                try {
                    int maxLen = Integer.parseInt(range[0]);
                    String name = dt.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
                    // Check for format: Nn/Mn = title case, n = lowercase
                    if (parts[0].length() > 0 && parts[0].charAt(0) == 'N') {
                        name = name.substring(0, 1).toUpperCase(Locale.ENGLISH) + 
                               name.substring(1).toLowerCase(Locale.ENGLISH);
                    } else if (parts[0].contains("n") || mod.startsWith("n")) {
                        name = name.toLowerCase(Locale.ENGLISH);
                    }
                    return name.substring(0, Math.min(maxLen, name.length()));
                } catch (NumberFormatException ignored) {}
            }
        }
        
        // Check for name format (n, N, or Nn)
        if (mod.equals("n") || mod.contains("n")) {
            // Full name, lowercase
            String name = dt.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            // If starts with capital N only (like "Nn"), use title case
            if (mod.startsWith("N") && !mod.equals("n")) {
                return name.substring(0, 1).toUpperCase(Locale.ENGLISH) + 
                       name.substring(1).toLowerCase(Locale.ENGLISH);
            }
            return name.toLowerCase(Locale.ENGLISH);
        }
        
        // If just 'N' (without 'n'), use FULL uppercase
        if (mod.equals("N")) {
            return dt.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase(Locale.ENGLISH);
        }
        
        // Single letter modifier: use NARROW style
        if (mod.length() == 1 && Character.isLetter(mod.charAt(0))) {
            return dt.getMonth().getDisplayName(TextStyle.NARROW, Locale.ENGLISH);
        }
        
        // Otherwise use abbreviated
        String name = dt.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return name;
    }

    private static String formatDayName(ZonedDateTime dt, String mod) {
        // Check for '0' modifier - returns day of week as number (1-7)
        // Also check for '1' modifier - returns day of week as number (1-7)
        if (mod.contains("0") || mod.contains("1")) {
            return String.valueOf(dt.getDayOfWeek().getValue());
        }
        
        // Check for ordinal 'o' modifier - returns day of month with suffix
        if (mod.contains("o")) {
            int dayOfMonth = dt.getDayOfMonth();
            String suffix = switch (dayOfMonth) {
                case 1, 21, 31 -> "st";
                case 2, 22 -> "nd";
                case 3, 23 -> "rd";
                default -> "th";
            };
            return dayOfMonth + suffix;
        }
        
        // Check for abbreviated: a or A -> abbreviated day name
        // Also check narrow: just 'a' or 'A' without other letters means narrow (single letter)
        if (mod.contains("a") || mod.contains("A")) {
            // If just 'a' or 'A' alone (single letter), use narrow
            if (mod.length() == 1 && (mod.equals("a") || mod.equals("A"))) {
                String name = dt.getDayOfWeek().getDisplayName(TextStyle.NARROW, Locale.ENGLISH);
                return name;
            }
            
            // Check for min-max (e.g., "Nn,3-3") - abbreviate to given length
            if (mod.contains(",")) {
                String[] parts = mod.split(",");
                if (parts.length > 1 && parts[1].contains("-")) {
                    String[] range = parts[1].split("-");
                    try {
                        int maxLen = Integer.parseInt(range[0]);
                        String name = dt.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
                        // Check for format: Nn = title case, n = lowercase, a = lowercase
                        if (parts[0].length() > 0 && parts[0].charAt(0) == 'N') {
                            name = name.substring(0, 1).toUpperCase(Locale.ENGLISH) + 
                                   name.substring(1).toLowerCase(Locale.ENGLISH);
                        } else if (parts[0].contains("n") || mod.startsWith("n") || mod.startsWith("a")) {
                            name = name.toLowerCase(Locale.ENGLISH);
                        }
                        return name.substring(0, Math.min(maxLen, name.length()));
                    } catch (NumberFormatException ignored) {}
                }
            }
            
            String name = dt.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            if (mod.startsWith("a") && !mod.startsWith("A")) {
                // 'a' at start = lowercase
                return name.toLowerCase(Locale.ENGLISH);
            }
            return name;
        }
        
        // Check for min-max for day name (e.g., "FNn,3-3")
        if (mod.contains(",")) {
            String[] parts = mod.split(",");
            if (parts.length > 1 && parts[1].contains("-")) {
                String[] range = parts[1].split("-");
                try {
                    int maxLen = Integer.parseInt(range[0]);
                    String name = dt.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
                    // Check for format: Nn = title case, n = lowercase
                    if (parts[0].length() > 0 && parts[0].charAt(0) == 'N') {
                        // Title case: first letter uppercase, rest lowercase
                        name = name.substring(0, 1).toUpperCase(Locale.ENGLISH) + 
                               name.substring(1).toLowerCase(Locale.ENGLISH);
                    } else if (parts[0].contains("n") || mod.startsWith("n")) {
                        name = name.toLowerCase(Locale.ENGLISH);
                    }
                    return name.substring(0, Math.min(maxLen, name.length()));
                } catch (NumberFormatException ignored) {}
            }
        }
        
        // Otherwise format the day name
        String name = dt.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        if (mod.isEmpty() || mod.equals("n")) {
            // Default modifier: lowercase
            return name.toLowerCase(Locale.ENGLISH);
        }
        if (mod.contains("n")) {
            // Title case: first letter uppercase, rest lowercase
            name = name.substring(0, 1).toUpperCase(Locale.ENGLISH) + 
                   name.substring(1).toLowerCase(Locale.ENGLISH);
        } else if (mod.startsWith("N") && !mod.contains("n")) {
            // Uppercase only if N present but no n
            name = name.toUpperCase(Locale.ENGLISH);
        }
        return name;
    }

    /** Formats timezone offset as {@code +HHMM} or {@code +HH:MM} or {@code Z}. */
    private static String formatOffsetZ(ZoneOffset offset, String mod) {
        int totalMins = offset.getTotalSeconds() / 60;
        int h = Math.abs(totalMins) / 60;
        int m = Math.abs(totalMins) % 60;
        
        // Parse modifier - handle format like "01:01" or "0101" or "01t"
        // "01:01" means hour width 2, minute width 2, with colon separator
        // "0101" means hour width 2, minute width 2, no colon
        // "01t" means hour width 2, short format (no minutes)
        
        int hourWidth = 2;
        int minuteWidth = 2;
        boolean useColon = mod.contains(":");
        boolean shortFormat = mod.contains("t");
        
        if (useColon) {
            String[] parts = mod.split(":");
            if (parts.length >= 1 && !parts[0].isEmpty()) {
                hourWidth = parts[0].length();
            }
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                // Remove 't' from minute part
                String minPart = parts[1].replace("t", "");
                minuteWidth = minPart.isEmpty() ? 2 : minPart.length();
            }
        } else if (!mod.isEmpty()) {
            // Has modifier but no colon
            if (mod.equals("0")) {
                // "Z0" means variable width hour, and depends on offset:
                // - If offset has non-zero minutes (e.g., +0530): include minutes with colon
                // - If offset has zero minutes (e.g., -0500): no minutes
                if (m == 0) {
                    // No minutes - use no colon, single digit hour
                    hourWidth = 1;
                    minuteWidth = 0;
                } else {
                    // Has minutes - use colon
                    useColon = true;
                    hourWidth = 1;
                }
            } else {
                // Parse digits only
                String digitsOnly = mod.replaceAll("[^0-9]", "");
                if (digitsOnly.length() > 4) {
                    // More than 4 digits is an error (D3134)
                    throw new RuntimeEvaluationException("D3134: timezone picture string too long");
                }
                if (digitsOnly.length() >= 2) {
                    hourWidth = 2;
                    if (digitsOnly.length() >= 4) {
                        minuteWidth = 2;
                    } else if (shortFormat) {
                        // Only 2 digits and has 't' - no minutes
                        minuteWidth = 0;
                    }
                } else if (shortFormat) {
                    // Just "t" - no minutes
                    minuteWidth = 0;
                }
            }
        } else {
            // No modifier - default to colon format
            useColon = true;
        }
        
        // If offset is 0:
        // - If 't' modifier: return "Z" (short format for UTC)
        // - If explicit width specified without 't': return explicit "+00:00" format
        if (offset.getTotalSeconds() == 0) {
            if (shortFormat) {
                return "Z";
            }
            // No 't' - format explicitly with width from modifier
            String hourStr = String.format("%0" + hourWidth + "d", h);
            String minStr = String.format("%0" + minuteWidth + "d", m);
            if (useColon) {
                return String.format("+%s:%s", hourStr, minStr);
            } else {
                return String.format("+%s%s", hourStr, minStr);
            }
        }
        
        // Non-zero offset - format it
        String hourStr = (hourWidth == 1) ? String.valueOf(h) : String.format("%0" + hourWidth + "d", h);
        
        if (minuteWidth == 0) {
            // Short format with no minutes
            return String.format("%s%s", totalMins >= 0 ? "+" : "-", hourStr);
        } else {
            String minStr = String.format("%0" + minuteWidth + "d", m);
            if (useColon) {
                return String.format("%s%s:%s", totalMins >= 0 ? "+" : "-", hourStr, minStr);
            } else {
                return String.format("%s%s%s", totalMins >= 0 ? "+" : "-", hourStr, minStr);
            }
        }
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
        if (countUnclosed(picture) > 0) {
            throw new RuntimeEvaluationException(
                    "D3135: Unclosed '[' in picture string");
        }
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
                            "D3135: Unclosed '[' in picture string");
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
        
        // Check for flexible width modifier: *-n (e.g., Y,*-4 means year with min 4 digits)
        boolean flexibleWidth = mod.contains("*");
        int minWidth = width > 0 ? width : (flexibleWidth ? 1 : 0);
        if (flexibleWidth && mod.contains("-")) {
            String[] parts = mod.split("-", -1);
            if (parts.length > 1 && !parts[1].isEmpty()) {
                try {
                    minWidth = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
        }

        switch (d) {
            case 'Y' -> {
                if (flexibleWidth) {
                    // Use appendValue with minWidth, maxWidth, and SignStyle
                    b.appendValue(ChronoField.YEAR, minWidth, 9, java.time.format.SignStyle.NORMAL);
                } else {
                    b.appendValue(ChronoField.YEAR, minWidth > 0 ? minWidth : 4);
                }
            }
            case 'M' -> {
                if (!mod.isEmpty() && (mod.charAt(0) == 'N' || mod.charAt(0) == 'n'))
                    b.appendText(ChronoField.MONTH_OF_YEAR);
                else b.appendValue(ChronoField.MONTH_OF_YEAR, minWidth > 0 ? minWidth : 2);
            }
            case 'D' -> b.appendValue(ChronoField.DAY_OF_MONTH, minWidth > 0 ? minWidth : 2);
            case 'd' -> b.appendValue(ChronoField.DAY_OF_YEAR, minWidth > 0 ? minWidth : 3);
            case 'F' -> b.appendText(ChronoField.DAY_OF_WEEK);
            case 'H' -> b.appendValue(ChronoField.HOUR_OF_DAY, minWidth > 0 ? minWidth : 2);
            case 'h' -> b.appendValue(ChronoField.CLOCK_HOUR_OF_AMPM,
                    mod.startsWith("#") ? 1 : Math.max(1, minWidth));
            case 'm' -> b.appendValue(ChronoField.MINUTE_OF_HOUR, minWidth > 0 ? minWidth : 2);
            case 's' -> b.appendValue(ChronoField.SECOND_OF_MINUTE, minWidth > 0 ? minWidth : 2);
            case 'f' -> b.appendValue(ChronoField.MILLI_OF_SECOND, minWidth > 0 ? minWidth : 3);
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
        if (tz == null || tz.isEmpty() || tz.equals("Z") || tz.equals("UTC"))
            return ZoneOffset.UTC;
        // Handle "0000" as UTC (common variant)
        if (tz.equals("0000") || tz.equals("+0000") || tz.equals("-0000"))
            return ZoneOffset.UTC;
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
    
    private static String toRoman(int n) {
        if (n <= 0 || n > 3_999_999) {
            return String.valueOf(n);
        }
        int[] vals = {1000,900,500,400,100,90,50,40,10,9,5,4,1};
        String[] syms = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < vals.length; k++) {
            while (n >= vals[k]) { sb.append(syms[k]); n -= vals[k]; }
        }
        return sb.toString();
    }
    
    private static String toAlphabetic(int n, boolean uppercase) {
        if (n <= 0) {
            return String.valueOf(n);
        }
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            n--; // Adjust for 0-based
            sb.append((char) ('a' + (n % 26)));
            n /= 26;
        }
        String result = sb.reverse().toString();
        return uppercase ? result.toUpperCase(Locale.ENGLISH) : result;
    }
    
    private static String formatOrdinal(int n) {
        String suffix = switch (n) {
            case 1, 21, 31 -> "st";
            case 2, 22 -> "nd";
            case 3, 23 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }
    
    private static String toWords(int n) {
        if (n <= 0) return String.valueOf(n);
        
        String[] units = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
        String[] teens = {"ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        String[] tens = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        
        StringBuilder sb = new StringBuilder();
        
        if (n >= 1000) {
            int thousands = n / 1000;
            sb.append(units[thousands]).append(" thousand");
            n = n % 1000;
            if (n > 0) sb.append(" and ");
        }
        
        if (n >= 100 && n < 1000) {
            int hundreds = n / 100;
            sb.append(units[hundreds]).append(" hundred");
            n = n % 100;
            if (n > 0) sb.append(" and ");
        }
        
        if (n >= 20) {
            int tensDigit = n / 10;
            sb.append(tens[tensDigit]);
            int ones = n % 10;
            if (ones > 0) {
                sb.append("-").append(units[ones]);
            }
        } else if (n >= 10) {
            sb.append(teens[n - 10]);
        } else if (n > 0) {
            sb.append(units[n]);
        }
        
        return sb.toString();
    }
    
    private static String toWordsOrdinal(int n) {
        if (n <= 0) return String.valueOf(n);
        
        // Special irregular ordinals that don't follow normal pattern
        if (n == 1) return "first";
        if (n == 2) return "second";
        if (n == 5) return "fifth";
        if (n == 8) return "eighth";
        if (n == 9) return "ninth";
        if (n == 12) return "twelfth";
        // For 3, we need special handling via the ordinal word below
        
        // Build ordinal words
        String[] units = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
        String[] unitsOrd = {"", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth"};
        String[] teens = {"ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        String[] tensNames = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        
        StringBuilder sb = new StringBuilder();
        
        if (n >= 1000) {
            int thousands = n / 1000;
            sb.append(units[thousands]).append(" thousand");
            n = n % 1000;
            if (n > 0) sb.append(" and ");
        }
        
        if (n >= 100 && n < 1000) {
            int hundreds = n / 100;
            sb.append(units[hundreds]).append(" hundred");
            n = n % 100;
            if (n > 0) sb.append(" and ");
        }
        
        if (n >= 20) {
            int tensDigit = n / 10;
            sb.append(tensNames[tensDigit]);
            int ones = n % 10;
            if (ones > 0) {
                sb.append("-").append(unitsOrd[ones]);
            } else {
                sb.append("th");
            }
        } else if (n >= 10) {
            sb.append(teens[n - 10]);
        } else if (n > 0) {
            sb.append(unitsOrd[n]);
        }
        
        return sb.toString();
    }
}
