package org.json_kula.jsonata_jvm.runtime.datetime;

import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;
import org.json_kula.jsonata_jvm.runtime.numeric.IntegerPicture;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Formats an instant using an XPath F&amp;O {@code fn:format-dateTime} picture string.
 *
 * <p>The governing rule is F&amp;O §9.8.4.3: <em>every</em> integer-valued component —
 * {@code YMDdFWwXxHhms}, plus {@code f} and the timezone offset — is rendered by the same
 * integer formatter {@code $formatInteger} uses. This implementation therefore delegates
 * to {@link IntegerPicture}, and that single fact is what makes {@code [Ya]},
 * {@code [YA]}, {@code [YWw]}, {@code [Di]}, {@code [Mw]}, {@code [DW]} and the rest work
 * without a line of per-component code.
 *
 * <p>It previously hand-wrote each component with its own modifier handling, and the two
 * engines drifted: most modifiers silently fell back to plain decimal or threw, and the
 * package carried duplicate {@code RomanNumerals} and {@code WordNumbers} helpers that
 * were less complete than the numeric package's own. Routing through one engine is what
 * keeps them from drifting again.
 */
public final class PictureFormatter {

    private PictureFormatter() {}

    private static final String[] DAY_NAMES = {
        "", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    };
    private static final String[] MONTH_NAMES = {
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    };

    private static final long MILLIS_IN_A_DAY = 24L * 60 * 60 * 1000;

    /** F&amp;O §9.8.4.1 default presentation modifier, per component specifier. */
    private static String defaultPresentation(char component) {
        return switch (component) {
            case 'Y', 'M', 'D', 'd', 'W', 'w', 'X', 'x', 'H', 'h', 'f' -> "1";
            case 'F', 'P', 'C', 'E' -> "n";
            case 'm', 's' -> "01";
            case 'Z', 'z' -> "01:01";
            default -> null;
        };
    }

    // =========================================================================
    // Picture analysis
    // =========================================================================

    /** One piece of an analysed picture: either literal text or a variable marker. */
    private sealed interface Part {}

    private record Literal(String text) implements Part {}

    /**
     * A variable marker. {@code names} is non-null when the presentation modifier asks
     * for a name rather than a number; otherwise {@code integerFormat} drives it.
     */
    private record Marker(char component, String presentation1, String presentation2,
                          Integer widthMin, Integer widthMax,
                          IntegerPicture.TextCase names,
                          IntegerPicture.Analysis integerFormat,
                          int yearDigits) implements Part {}

    /** Analysed date/time pictures, memoized; see {@link org.json_kula.jsonata_jvm.runtime.PictureCache}. */
    private static final org.json_kula.jsonata_jvm.runtime.PictureCache<List<Part>> CACHE =
            new org.json_kula.jsonata_jvm.runtime.PictureCache<>(512, picture -> {
                try {
                    return List.copyOf(analyseUncached(picture));
                } catch (RuntimeEvaluationException e) {
                    throw new org.json_kula.jsonata_jvm.runtime.PictureCache.AnalysisFailure(e);
                }
            });

    private static List<Part> analyse(String picture) throws RuntimeEvaluationException {
        try {
            return CACHE.get(picture);
        } catch (org.json_kula.jsonata_jvm.runtime.PictureCache.AnalysisFailure e) {
            throw e.unwrap();
        }
    }

    private static List<Part> analyseUncached(String picture) throws RuntimeEvaluationException {
        List<Part> parts = new ArrayList<>();
        int start = 0, pos = 0;
        int length = picture.length();

        while (pos < length) {
            if (picture.charAt(pos) == '[') {
                if (pos + 1 < length && picture.charAt(pos + 1) == '[') {
                    addLiteral(parts, picture, start, pos);
                    parts.add(new Literal("["));
                    pos += 2;
                    start = pos;
                    continue;
                }
                addLiteral(parts, picture, start, pos);
                start = pos;
                pos = picture.indexOf(']', start);
                if (pos < 0) {
                    throw new RuntimeEvaluationException("D3135",
                            "No closing bracket in date/time picture string");
                }
                parts.add(marker(picture.substring(start + 1, pos)));
                start = pos + 1;
            }
            pos++;
        }
        addLiteral(parts, picture, start, length);
        return parts;
    }

    private static void addLiteral(List<Part> parts, String picture, int start, int end) {
        if (end > start) parts.add(new Literal(picture.substring(start, end).replace("]]", "]")));
    }

    private static Marker marker(String rawMarker) throws RuntimeEvaluationException {
        // Whitespace inside a variable marker is insignificant.
        String marker = rawMarker.replaceAll("\\s+", "");
        if (marker.isEmpty()) {
            throw new RuntimeEvaluationException("D3132", "Empty date/time component specifier");
        }
        char component = marker.charAt(0);

        // §9.8.4.2 The width modifier is recognised by a comma.
        Integer widthMin = null, widthMax = null;
        String presentationModifier;
        int comma = marker.lastIndexOf(',');
        if (comma >= 0) {
            String widthModifier = marker.substring(comma + 1);
            int dash = widthModifier.indexOf('-');
            String min = dash < 0 ? widthModifier : widthModifier.substring(0, dash);
            String max = dash < 0 ? null : widthModifier.substring(dash + 1);
            widthMin = parseWidth(min);
            widthMax = parseWidth(max);
            presentationModifier = marker.substring(1, comma);
        } else {
            presentationModifier = marker.substring(1);
        }

        String presentation1;
        String presentation2 = null;
        if (presentationModifier.length() == 1) {
            presentation1 = presentationModifier;
        } else if (presentationModifier.length() > 1) {
            char last = presentationModifier.charAt(presentationModifier.length() - 1);
            if ("atco".indexOf(last) >= 0) {
                presentation2 = String.valueOf(last);
                presentation1 = presentationModifier.substring(0, presentationModifier.length() - 1);
            } else {
                presentation1 = presentationModifier;
            }
        } else {
            presentation1 = defaultPresentation(component);
        }
        if (presentation1 == null) {
            throw new RuntimeEvaluationException("D3132",
                    "Unknown date/time component specifier: " + component);
        }

        IntegerPicture.TextCase names = null;
        if (presentation1.charAt(0) == 'n') {
            names = IntegerPicture.TextCase.LOWER;
        } else if (presentation1.charAt(0) == 'N') {
            names = presentation1.length() > 1 && presentation1.charAt(1) == 'n'
                    ? IntegerPicture.TextCase.TITLE
                    : IntegerPicture.TextCase.UPPER;
        }

        IntegerPicture.Analysis integerFormat = null;
        int yearDigits = -1;

        if (names == null && "YMDdFWwXxHhmsf".indexOf(component) >= 0) {
            String integerPattern = presentation1;
            if (presentation2 != null) integerPattern += ";" + presentation2;
            integerFormat = IntegerPicture.analyse(integerPattern);
            if (widthMin != null) {
                integerFormat = IntegerPicture.withMandatoryDigits(integerFormat, widthMin);
            }
            if (component == 'Y') {
                // §9.8.4.4: the year is truncated to its low-order digits, where the
                // digit count comes from the width modifier if there is one and from the
                // picture's own digit count otherwise.
                if (widthMax != null) {
                    yearDigits = widthMax;
                    integerFormat = IntegerPicture.setMandatoryDigits(integerFormat, yearDigits);
                } else {
                    int w = integerFormat.mandatoryDigits() + integerFormat.optionalDigits();
                    if (w >= 2) yearDigits = w;
                }
            }
        }
        if (component == 'Z' || component == 'z') {
            integerFormat = IntegerPicture.analyse(presentation1);
        }

        return new Marker(component, presentation1, presentation2,
                widthMin, widthMax, names, integerFormat, yearDigits);
    }

    private static Integer parseWidth(String widthModifier) {
        if (widthModifier == null || widthModifier.isEmpty() || widthModifier.equals("*")) return null;
        try {
            return Integer.parseInt(widthModifier);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // =========================================================================
    // Formatting
    // =========================================================================

    public static String format(long millis, String picture, String timezone)
            throws RuntimeEvaluationException {
        int offsetHours = 0;
        int offsetMinutes = 0;
        if (timezone != null && !timezone.isEmpty()) {
            ZoneOffset offset = TimezoneUtils.parseZoneOffset(timezone);
            int totalMinutes = offset.getTotalSeconds() / 60;
            offsetHours = totalMinutes / 60;
            offsetMinutes = totalMinutes % 60;
        }

        long offsetMillis = (60L * offsetHours + offsetMinutes) * 60 * 1000;
        // The offset is folded into the instant and every field is then read in UTC, so
        // the field extraction never has to know about zones.
        ZonedDateTime dt = Instant.ofEpochMilli(millis + offsetMillis).atZone(ZoneOffset.UTC);

        StringBuilder sb = new StringBuilder();
        for (Part part : analyse(picture)) {
            if (part instanceof Literal literal) {
                sb.append(literal.text());
            } else {
                sb.append(formatComponent(dt, (Marker) part, offsetHours, offsetMinutes));
            }
        }
        return sb.toString();
    }

    private static String formatComponent(ZonedDateTime dt, Marker spec,
                                          int offsetHours, int offsetMinutes)
            throws RuntimeEvaluationException {
        char component = spec.component();

        if ("YMDdFWwXxHhms".indexOf(component) >= 0) {
            long value = fragment(dt, component);
            if (component == 'Y' && spec.yearDigits() != -1) {
                value = value % (long) Math.pow(10, spec.yearDigits());
            }
            if (spec.names() != null) {
                String name;
                if (component == 'M' || component == 'x') {
                    name = MONTH_NAMES[(int) value - 1];
                } else if (component == 'F') {
                    name = DAY_NAMES[(int) value];
                } else {
                    throw new RuntimeEvaluationException("D3133",
                            "Name presentation is not supported for component " + component);
                }
                if (spec.names() == IntegerPicture.TextCase.UPPER) {
                    name = name.toUpperCase(Locale.ENGLISH);
                } else if (spec.names() == IntegerPicture.TextCase.LOWER) {
                    name = name.toLowerCase(Locale.ENGLISH);
                }
                if (spec.widthMax() != null && name.length() > spec.widthMax()) {
                    name = name.substring(0, spec.widthMax());
                }
                return name;
            }
            return IntegerPicture.format(value, spec.integerFormat());
        }

        if (component == 'f') {
            // A fractional second has no name, and asking for one leaves no integer picture to
            // fall back on. The block above raises D3133 for every other component that cannot be
            // named; 'f' is formatted here rather than there, so it needs the same guard — without
            // it the missing picture surfaces as a NullPointerException. The reference throws a
            // raw JavaScript TypeError for [fn], which is not a JSONata error either.
            if (spec.names() != null) {
                throw new RuntimeEvaluationException("D3133",
                        "Name presentation is not supported for component " + component);
            }
            // The raw millisecond value goes through the integer path — it is not a
            // scaled decimal fraction, so [f0001] on 1 ms is "0001", not "0010".
            return IntegerPicture.format(dt.getNano() / 1_000_000, spec.integerFormat());
        }

        if (component == 'Z' || component == 'z') {
            return formatOffset(spec, offsetHours, offsetMinutes);
        }

        if (component == 'P') {
            String marker = dt.getHour() >= 12 ? "pm" : "am";
            return spec.names() == IntegerPicture.TextCase.UPPER
                    ? marker.toUpperCase(Locale.ENGLISH) : marker;
        }

        if (component == 'C' || component == 'E') return "ISO";

        throw new RuntimeEvaluationException("D3132",
                "Unknown date/time component specifier: " + component);
    }

    /** F&amp;O §9.8.4.6 timezone formatting. */
    private static String formatOffset(Marker spec, int offsetHours, int offsetMinutes)
            throws RuntimeEvaluationException {
        int offset = offsetHours * 100 + offsetMinutes;
        String value;
        IntegerPicture.Analysis format = spec.integerFormat();

        if (format.regular()) {
            value = IntegerPicture.format(offset, format);
        } else {
            int digits = format.mandatoryDigits();
            if (digits == 1 || digits == 2) {
                value = IntegerPicture.format(offsetHours, format);
                if (offsetMinutes != 0) value += ":" + IntegerPicture.format(offsetMinutes, "00");
            } else if (digits == 3 || digits == 4) {
                value = IntegerPicture.format(offset, format);
            } else {
                throw new RuntimeEvaluationException("D3134",
                        "Timezone picture requires 1-4 digits, got " + digits);
            }
        }

        if (offset >= 0) value = "+" + value;
        if (spec.component() == 'z') value = "GMT" + value;
        if (offset == 0 && "t".equals(spec.presentation2())) value = "Z";
        return value;
    }

    // =========================================================================
    // Date/time fragments (F&O component values)
    // =========================================================================

    private static long fragment(ZonedDateTime dt, char component) {
        return switch (component) {
            case 'Y' -> dt.getYear();
            case 'M' -> dt.getMonthValue();
            case 'D' -> dt.getDayOfMonth();
            case 'd' -> dt.getDayOfYear();
            case 'F' -> dt.getDayOfWeek().getValue();      // ISO 1=Mon .. 7=Sun
            case 'W' -> weekInYear(dt);
            case 'w' -> weekInMonth(dt);
            case 'X' -> weekNumberingYear(dt);
            case 'x' -> weekNumberingMonth(dt);
            case 'H' -> dt.getHour();
            case 'h' -> {
                int hour = dt.getHour() % 12;
                yield hour == 0 ? 12 : hour;
            }
            case 'm' -> dt.getMinute();
            case 's' -> dt.getSecond();
            default -> 0;
        };
    }

    /**
     * Start of the first week of the year or month beginning at {@code year}/{@code month}
     * (1-based month), in epoch millis.
     *
     * <p>ISO 8601 defines the year's first week as the one containing its first Thursday;
     * F&amp;O extends the same rule to a month. So when the 1st falls on a Friday,
     * Saturday or Sunday, week 1 starts on the <em>following</em> Monday. The previous
     * implementation used day-of-month/7 with ad-hoc corrections and put 1970-01-01 in
     * week 5.
     */
    private static long startOfFirstWeek(int year, int month) {
        long first = epochMillis(year, month, 1);
        int dayOfFirst = dayOfWeek(first);
        return dayOfFirst > 4
                ? first + (8 - dayOfFirst) * MILLIS_IN_A_DAY
                : first - (dayOfFirst - 1) * MILLIS_IN_A_DAY;
    }

    private static long epochMillis(int year, int month, int day) {
        return java.time.LocalDate.of(year, month, day).toEpochDay() * MILLIS_IN_A_DAY;
    }

    /** ISO day of week (1=Monday .. 7=Sunday) for an epoch-millis instant. */
    private static int dayOfWeek(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).getDayOfWeek().getValue();
    }

    private static long deltaWeeks(long start, long end) {
        return Math.floorDiv(end - start, MILLIS_IN_A_DAY * 7) + 1;
    }

    private static long weekInYear(ZonedDateTime dt) {
        int year = dt.getYear();
        long startOfWeek1 = startOfFirstWeek(year, 1);
        long today = epochMillis(year, dt.getMonthValue(), dt.getDayOfMonth());
        long week = deltaWeeks(startOfWeek1, today);
        if (week > 52) {
            if (today >= startOfFirstWeek(year + 1, 1)) week = 1;
        } else if (week < 1) {
            week = deltaWeeks(startOfFirstWeek(year - 1, 1), today);
        }
        return week;
    }

    private static long weekInMonth(ZonedDateTime dt) {
        int year = dt.getYear(), month = dt.getMonthValue();
        long startOfWeek1 = startOfFirstWeek(year, month);
        long today = epochMillis(year, month, dt.getDayOfMonth());
        long week = deltaWeeks(startOfWeek1, today);
        if (week > 4) {
            int[] next = nextMonth(year, month);
            if (today >= startOfFirstWeek(next[0], next[1])) week = 1;
        } else if (week < 1) {
            int[] previous = previousMonth(year, month);
            week = deltaWeeks(startOfFirstWeek(previous[0], previous[1]), today);
        }
        return week;
    }

    /**
     * The ISO week-numbering year: a jsonata extension, because 1 January 2005 falls in
     * the 53rd week of 2004 and {@code [W]} reports 53 while {@code [Y]} reports 2005.
     */
    private static long weekNumberingYear(ZonedDateTime dt) {
        int year = dt.getYear();
        long now = dt.toInstant().toEpochMilli();
        if (now < startOfFirstWeek(year, 1)) return year - 1L;
        if (now >= startOfFirstWeek(year + 1, 1)) return year + 1L;
        return year;
    }

    /** The week-numbering month, the {@code [x]} counterpart of {@code [X]}. */
    private static long weekNumberingMonth(ZonedDateTime dt) {
        int year = dt.getYear(), month = dt.getMonthValue();
        long now = dt.toInstant().toEpochMilli();
        int[] next = nextMonth(year, month);
        if (now < startOfFirstWeek(year, month)) return previousMonth(year, month)[1];
        if (now >= startOfFirstWeek(next[0], next[1])) return next[1];
        return month;
    }

    private static int[] nextMonth(int year, int month) {
        return month == 12 ? new int[] { year + 1, 1 } : new int[] { year, month + 1 };
    }

    private static int[] previousMonth(int year, int month) {
        return month == 1 ? new int[] { year - 1, 12 } : new int[] { year, month - 1 };
    }

    // =========================================================================
    // Bracket validation (shared with PictureParser)
    // =========================================================================

    /** Rejects a picture whose square brackets do not balance, ignoring doubled ones. */
    public static void checkBrackets(String picture) throws RuntimeEvaluationException {
        int count = 0;
        for (int k = 0; k < picture.length(); k++) {
            char c = picture.charAt(k);
            if (c == '[' && k + 1 < picture.length() && picture.charAt(k + 1) == '[') { k++; continue; }
            if (c == ']' && k + 1 < picture.length() && picture.charAt(k + 1) == ']') { k++; continue; }
            if (c == '[') count++;
            else if (c == ']') count--;
        }
        if (count != 0) {
            throw new RuntimeEvaluationException("D3135", "Unclosed '[' in picture string");
        }
    }
}
