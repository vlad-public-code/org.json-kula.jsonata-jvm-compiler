package org.json_kula.jsonata_jvm.runtime.datetime;

import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;
import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

/**
 * Formats a {@link ZonedDateTime} using an XPath/XQuery fn:format-dateTime picture string.
 */
public final class PictureFormatter {

    private PictureFormatter() {}

    public static String format(long millis, String picture, String timezone)
            throws RuntimeEvaluationException {
        ZoneOffset offset = (timezone == null || timezone.isEmpty())
                ? ZoneOffset.UTC
                : TimezoneUtils.parseZoneOffset(timezone);
        ZonedDateTime dt = Instant.ofEpochMilli(millis).atZone(offset);
        return applyPicture(dt, picture);
    }

    // =========================================================================
    // Picture-string application
    // =========================================================================

    private static String applyPicture(ZonedDateTime dt, String picture)
            throws RuntimeEvaluationException {
        checkBrackets(picture);
        StringBuilder sb = new StringBuilder();
        int i = 0, len = picture.length();
        while (i < len) {
            char c = picture.charAt(i);
            if (c == '[' && i + 1 < len && picture.charAt(i + 1) == '[') {
                sb.append('['); i += 2;
            } else if (c == '[') {
                int j = picture.indexOf(']', i + 1);
                if (j < 0) throw unclosed();
                sb.append(formatComponent(dt, picture.substring(i + 1, j)));
                i = j + 1;
            } else if (c == ']' && i + 1 < len && picture.charAt(i + 1) == ']') {
                sb.append(']'); i += 2;
            } else {
                sb.append(c); i++;
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Component formatting
    // =========================================================================

    private static String formatComponent(ZonedDateTime dt, String spec)
            throws RuntimeEvaluationException {
        if (spec.isEmpty()) return "";
        spec = spec.replaceAll("\\s+", "");
        char d = spec.charAt(0);
        String mod = spec.length() > 1 ? spec.substring(1) : "";

        return switch (d) {
            case 'Y' -> formatYear(dt, mod);
            case 'X' -> formatInt(dt.get(WeekFields.ISO.weekBasedYear()), mod, 4);
            case 'W' -> formatInt(dt.get(WeekFields.ISO.weekOfWeekBasedYear()), mod, 2);
            case 'w' -> formatWeekOfMonth(dt, mod);
            case 'x' -> formatWeekOfMonthContext(dt, mod);
            case 'M' -> formatMonth(dt, mod);
            case 'D' -> formatDayOfMonth(dt, mod);
            case 'd' -> formatDayOfYear(dt, mod);
            case 'F' -> formatDayName(dt, mod);
            case 'H' -> formatInt(dt.getHour(), mod, 2);
            case 'h' -> {
                int h = dt.getHour() % 12;
                yield formatInt(h == 0 ? 12 : h, mod, 2);
            }
            case 'C', 'E' -> "ISO";
            case 'm' -> formatInt(dt.getMinute(), mod.isEmpty() ? "01" : mod, 2);
            case 's' -> formatInt(dt.getSecond(), mod.isEmpty() ? "01" : mod, 2);
            case 'f' -> formatMillis(dt.getNano() / 1_000_000, mod);
            case 'P' -> formatAmPm(dt, mod);
            case 'Z' -> formatOffsetZ(dt.getOffset(), mod);
            case 'z' -> formatOffsetName(dt.getOffset());
            default -> throw new RuntimeEvaluationException(null,
                    "Unknown picture-string component: [" + spec + "]");
        };
    }

    // -------------------------------------------------------------------------

    private static String formatYear(ZonedDateTime dt, String mod)
            throws RuntimeEvaluationException {
        if (!mod.isEmpty()) {
            switch (mod) {
                case "N", "n" -> throw new RuntimeEvaluationException("D3133", "Year name component is not supported");
                case "I" -> {
                    return RomanNumerals.toRoman(dt.getYear());
                }
                case "i" -> {
                    return RomanNumerals.toRoman(dt.getYear()).toLowerCase(Locale.ENGLISH);
                }
                case "w", "W" -> {
                    return WordNumbers.toCardinal(dt.getYear());
                }
            }
        }
        return formatInt(dt.getYear(), mod, 4);
    }

    private static String formatMonth(ZonedDateTime dt, String mod) {
        if (!mod.isEmpty() && Character.isLetter(mod.charAt(0))) {
            if (mod.equals("a") || mod.equals("A"))
                return toAlphabetic(dt.getMonthValue(), mod.equals("A"));
            if (mod.charAt(0) == 'n' || mod.charAt(0) == 'N')
                return formatMonthName(dt, mod);
            if (mod.equals("i")) return RomanNumerals.toRoman(dt.getMonthValue()).toLowerCase(Locale.ENGLISH);
            if (mod.equals("I")) return RomanNumerals.toRoman(dt.getMonthValue());
        }
        return formatInt(dt.getMonthValue(), mod, 2);
    }

    private static String formatDayOfMonth(ZonedDateTime dt, String mod) {
        if (!mod.isEmpty()) {
            if (mod.contains("w")) return WordNumbers.toOrdinal(dt.getDayOfMonth());
            if (mod.contains("o")) return formatOrdinalSuffix(dt.getDayOfMonth());
            if (mod.equals("a") || mod.equals("A"))
                return toAlphabetic(dt.getDayOfMonth(), mod.equals("A"));
            // Fix: removed the incorrect n/N branch that delegated to formatDayName (day-of-week).
            // [Dn]/[DN] is not defined in the spec; fall through to numeric.
        }
        return formatInt(dt.getDayOfMonth(), mod, 2);
    }

    private static String formatDayOfYear(ZonedDateTime dt, String mod) {
        if (!mod.isEmpty() && mod.contains("w")) return WordNumbers.toOrdinal(dt.getDayOfYear());
        return formatInt(dt.getDayOfYear(), mod, 3);
    }

    /**
     * Formats the day-of-week name for the {@code [F]} component.
     *
     * <p>Fix: empty modifier now returns title-case (e.g. "Tuesday") as per the XPath spec.
     * Previously it returned all-lowercase.
     */
    private static String formatDayName(ZonedDateTime dt, String mod) {
        if (mod.contains("0") || mod.contains("1"))
            return String.valueOf(dt.getDayOfWeek().getValue());

        if (mod.contains(",")) {
            return abbreviateName(dt.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), mod);
        }

        String name = dt.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        if (mod.isEmpty() || mod.equals("n")) return name.toLowerCase(Locale.ENGLISH);
        if (mod.equals("N")) return name.toUpperCase(Locale.ENGLISH);
        if (mod.startsWith("N") && mod.contains("n"))
            return titleCase(name); // Nn = title case
        if (mod.equals("a") || mod.equals("A")) {
            String abbr = dt.getDayOfWeek().getDisplayName(TextStyle.NARROW, Locale.ENGLISH);
            return mod.equals("a") ? abbr.toLowerCase(Locale.ENGLISH) : abbr;
        }
        return titleCase(name);
    }

    private static String formatMonthName(ZonedDateTime dt, String mod) {
        if (mod.isEmpty() || mod.matches("\\d+.*")) return String.valueOf(dt.getMonthValue());
        if (mod.contains(",")) return abbreviateName(
                dt.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), mod);
        String name = dt.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        if (mod.equals("n") || mod.startsWith("n")) return name.toLowerCase(Locale.ENGLISH);
        if (mod.equals("N")) return name.toUpperCase(Locale.ENGLISH);
        if (mod.startsWith("N") && mod.contains("n")) return titleCase(name);
        if (mod.length() == 1 && Character.isLetter(mod.charAt(0)))
            return dt.getMonth().getDisplayName(TextStyle.NARROW, Locale.ENGLISH);
        return dt.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static String formatAmPm(ZonedDateTime dt, String mod) {
        boolean afternoon = dt.getHour() >= 12;
        if ("N".equals(mod)) return afternoon ? "PM" : "AM";
        return afternoon ? "pm" : "am";
    }

    /** Formats timezone as {@code ±HH:MM} or {@code Z}. */
    private static String formatOffsetZ(ZoneOffset offset, String mod)
            throws RuntimeEvaluationException {
        int totalMins = offset.getTotalSeconds() / 60;
        int h = Math.abs(totalMins) / 60;
        int m = Math.abs(totalMins) % 60;

        int hourWidth = 2, minuteWidth = 2;
        boolean useColon = true;
        boolean shortFormat = mod.contains("t");

        if (!mod.isEmpty()) {
            if (mod.contains(":")) {
                useColon = true;
                String[] parts = mod.split(":");
                hourWidth  = parts[0].isEmpty() ? 2 : parts[0].length();
                String minPart = parts.length > 1 ? parts[1].replace("t","") : "";
                minuteWidth = minPart.isEmpty() ? 2 : minPart.length();
            } else if (mod.equals("0")) {
                // variable-width: omit minutes when they are zero
                if (m == 0) { minuteWidth = 0; hourWidth = 1; useColon = false; }
                else         { useColon = true; hourWidth = 1; }
            } else {
                String digits = mod.replaceAll("[^0-9]", "");
                if (digits.length() > 4)
                    throw new RuntimeEvaluationException("D3134", "timezone picture string too long");
                useColon = false;
                hourWidth = digits.length() >= 2 ? 2 : hourWidth;
                if (digits.length() >= 4) minuteWidth = 2;
                else if (shortFormat) minuteWidth = 0;
            }
        }

        // Fix: the UTC path previously called String.format("%00d", m) when minuteWidth==0,
        // causing an IllegalFormatFlagsException. Now we correctly omit the minute part.
        if (offset.getTotalSeconds() == 0 && shortFormat) return "Z";

        String hourStr = (hourWidth == 1) ? String.valueOf(h)
                : String.format("%0" + hourWidth + "d", h);
        String sign = totalMins >= 0 ? "+" : "-";

        if (minuteWidth == 0) {
            return sign + hourStr;
        }
        String minStr = String.format("%0" + minuteWidth + "d", m);
        return useColon ? sign + hourStr + ":" + minStr : sign + hourStr + minStr;
    }

    private static String formatOffsetName(ZoneOffset offset) {
        if (offset.getTotalSeconds() == 0) return "GMT";
        int totalMins = offset.getTotalSeconds() / 60;
        int h = Math.abs(totalMins) / 60;
        int m = Math.abs(totalMins) % 60;
        return String.format("GMT%s%02d:%02d", totalMins >= 0 ? "+" : "-", h, m);
    }

    // =========================================================================
    // Week-of-month helpers (unchanged logic)
    // =========================================================================

    private static String formatWeekOfMonth(ZonedDateTime dt, String mod) {
        LocalDate date = dt.toLocalDate();
        LocalDate monday = date;
        while (monday.getDayOfWeek() != DayOfWeek.MONDAY) monday = monday.minusDays(1);
        int inCurrent = 0;
        for (int i = 0; i < 7; i++)
            if (monday.plusDays(i).getMonthValue() == date.getMonthValue()) inCurrent++;
        if (inCurrent <= 3 && date.getDayOfMonth() >= 28) return "1";
        if (monday.getMonthValue() != date.getMonthValue() && date.getDayOfMonth() <= 4) return "5";
        return formatInt((int) Math.ceil((double) date.getDayOfMonth() / 7), mod, 1);
    }

    private static String formatWeekOfMonthContext(ZonedDateTime dt, String mod) {
        LocalDate date = dt.toLocalDate();
        LocalDate monday = date;
        while (monday.getDayOfWeek() != DayOfWeek.MONDAY) monday = monday.minusDays(1);
        LocalDate firstOfMonth = date.withDayOfMonth(1);
        int prev = 0, curr = 0, next = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            if (day.isBefore(firstOfMonth))           prev++;
            else if (day.getMonthValue() == date.getMonthValue()) curr++;
            else                                      next++;
        }
        Month ctx = (prev > curr && prev > next) ? date.minusMonths(1).getMonth()
                : (next > curr && next > prev)   ? date.plusMonths(1).getMonth()
                : date.getMonth();
        if (!mod.isEmpty() && mod.contains("N"))
            return mod.startsWith("n") ? ctx.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ENGLISH)
                    : ctx.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return formatMonthName(dt, mod);
    }

    // =========================================================================
    // Number formatting helpers
    // =========================================================================

    /**
     * Formats {@code value} according to a picture modifier.
     *
     * <ul>
     *   <li>Empty / "1" / "#…" — no leading zeros</li>
     *   <li>"01"/"001"/… — zero-padded to that many digits</li>
     *   <li>"9,999,*" — thousands-separator format</li>
     * </ul>
     */
    static String formatInt(int value, String mod, int defaultWidth) {
        if (mod.isEmpty() || mod.equals("1") || (mod.startsWith("#") && !mod.contains(",")))
            return String.valueOf(value);

        boolean useThousandsSep = mod.contains(",");
        int minWidth = defaultWidth;
        int maxWidth = Integer.MAX_VALUE;
        boolean hasZerosInMinPart = false;

        if (useThousandsSep) {
            String[] parts = mod.split(",");
            if (parts.length > 0 && !parts[0].isEmpty()) {
                String minPart = parts[0];
                for (int i = 0; i < minPart.length(); i++) {
                    if (minPart.charAt(i) == '0' && (i == 0 || minPart.charAt(i-1) != '#')) {
                        hasZerosInMinPart = true; break;
                    }
                }
                if (hasZerosInMinPart) {
                    int w = (int) minPart.chars().filter(Character::isDigit).count();
                    if (w > 0) minWidth = w;
                }
            }
            if (parts.length > 1 && !parts[1].isEmpty()) {
                try { maxWidth = Integer.parseInt(parts[1].replaceAll("[^0-9].*", "")); }
                catch (NumberFormatException ignored) {}
            }
        } else {
            int w = (int) mod.chars().filter(Character::isDigit).count();
            if (w > 0) minWidth = w;
            hasZerosInMinPart = mod.contains("0");
        }

        boolean noMinWidth = mod.startsWith("9");
        String formatted = noMinWidth ? String.valueOf(value)
                : String.format("%0" + minWidth + "d", value);

        // Truncate from the left to maxWidth digits when:
        //   (a) explicit min-max format, e.g. [Y,2-4], OR
        //   (b) no zeros in the min-part, e.g. [Y,2] or [Y1,2] — meaning "last N digits"
        if (useThousandsSep && maxWidth < Integer.MAX_VALUE && formatted.length() > maxWidth) {
            String[] parts = mod.split(",");
            boolean isMinMax      = parts.length > 1 && parts[1].contains("-");
            boolean noZerosInMin  = !hasZerosInMinPart;
            if (isMinMax || noZerosInMin) {
                formatted = formatted.substring(formatted.length() - maxWidth);
            }
        }

        // Thousands separator only when explicitly requested with *
        if (useThousandsSep && mod.contains("*")) {
            StringBuilder sb = new StringBuilder();
            int cnt = 0;
            for (int i = formatted.length() - 1; i >= 0; i--) {
                if (cnt > 0 && cnt % 3 == 0) sb.insert(0, ',');
                sb.insert(0, formatted.charAt(i));
                cnt++;
            }
            return sb.toString();
        }
        return formatted;
    }

    /**
     * Formats milliseconds as a fractional-seconds component.
     *
     * <p>Fix: previously truncated from the left (e.g. 150 ms with width 2 gave "150" instead
     * of "15"). Now scales by dividing: {@code millis / 10^(3 - width)}.
     */
    private static String formatMillis(int millis, String mod) {
        int width = (int) mod.chars().filter(Character::isDigit).count();
        if (width == 0) width = 3;
        // Scale to the requested number of digits by discarding least-significant digits.
        int scaled = millis / (int) Math.pow(10, 3 - width);
        return String.format("%0" + width + "d", scaled);
    }

    private static String formatOrdinalSuffix(int n) {
        String suffix = switch (n) {
            case 1, 21, 31 -> "st";
            case 2, 22     -> "nd";
            case 3, 23     -> "rd";
            default        -> "th";
        };
        return n + suffix;
    }

    private static String toAlphabetic(int n, boolean uppercase) {
        if (n <= 0) return String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        while (n > 0) { n--; sb.append((char)('a' + (n % 26))); n /= 26; }
        String s = sb.reverse().toString();
        return uppercase ? s.toUpperCase(Locale.ENGLISH) : s;
    }

    private static String titleCase(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ENGLISH);
    }

    private static String abbreviateName(String name, String mod) {
        String[] parts = mod.split(",");
        if (parts.length > 1 && parts[1].contains("-")) {
            try {
                int maxLen = Integer.parseInt(parts[1].split("-")[0]);
                String cased = (!parts[0].isEmpty() && parts[0].charAt(0) == 'N') ? titleCase(name)
                        : (parts[0].contains("n") ? name.toLowerCase(Locale.ENGLISH) : name);
                return cased.substring(0, Math.min(maxLen, cased.length()));
            } catch (NumberFormatException ignored) {}
        }
        return name;
    }

    // =========================================================================
    // Bracket validation
    // =========================================================================

    public static void checkBrackets(String picture) throws RuntimeEvaluationException {
        int count = 0;
        for (int k = 0; k < picture.length(); k++) {
            char c = picture.charAt(k);
            if (c == '[' && k + 1 < picture.length() && picture.charAt(k + 1) == '[') { k++; continue; }
            if (c == ']' && k + 1 < picture.length() && picture.charAt(k + 1) == ']') { k++; continue; }
            if (c == '[') count++;
            else if (c == ']') count--;
        }
        if (count != 0) throw unclosed();
    }

    private static RuntimeEvaluationException unclosed() {
        return new RuntimeEvaluationException("D3135", "Unclosed '[' in picture string");
    }
}
