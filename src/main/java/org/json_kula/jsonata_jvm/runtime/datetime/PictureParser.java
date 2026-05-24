package org.json_kula.jsonata_jvm.runtime.datetime;

import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

/**
 * Parses a timestamp string using an XPath/XQuery picture string and returns
 * milliseconds since the Unix epoch.
 */
public final class PictureParser {

    private PictureParser() {}

    // Static constants — not recompiled on every call (fix: were inline Pattern.compile calls).
    private static final Pattern GMT_PATTERN  =
            Pattern.compile("GMT([+-])(\\d{1,2})(?::(\\d{2}))?");
    private static final Pattern BARE_OFFSET  =
            Pattern.compile(" ([+-])(\\d{2})(\\d{2})$");
    private static final Pattern ORDINAL_TAIL =
            Pattern.compile("(\\d)(st|nd|rd|th)");
    private static final Pattern LOWERCASE_ROMAN =
            Pattern.compile("\\b([ivxlcdm]+)\\b");
    private static final String[] MONTH_NAMES = {
            "january","february","march","april","may","june",
            "july","august","september","october","november","december"
    };

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * @return epoch millis, or {@link Long#MIN_VALUE} when the input does not match the picture.
     */
    public static long parse(String timestamp, String picture) throws RuntimeEvaluationException {
        PictureFormatter.checkBrackets(picture);

        // Determine whether day words will be pre-converted to numbers so we can configure
        // the DateTimeFormatter accordingly.
        boolean dayWordsConverted = computeDayWordsConverted(picture);

        String processed = preprocess(timestamp, picture, dayWordsConverted);
        java.time.format.DateTimeFormatter fmt = buildFormatter(picture, dayWordsConverted);

        try {
            TemporalAccessor ta = fmt.parse(processed);

            // Special path for [dwo]/[dwwo]: day-of-year words converted to a numeric day-of-year
            String lowerPic = picture.toLowerCase();
            if ((lowerPic.contains("[dwo]") || lowerPic.contains("[dwwo]"))
                    && ta.isSupported(ChronoField.DAY_OF_YEAR)) {
                int year      = ta.isSupported(ChronoField.YEAR) ? ta.get(ChronoField.YEAR) : 1;
                int dayOfYear = ta.get(ChronoField.DAY_OF_YEAR);
                return LocalDate.ofYearDay(year, dayOfYear)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }

            // Try to resolve a full instant directly
            try { return Instant.from(ta).toEpochMilli(); } catch (DateTimeException ignored) {}
            try { return ZonedDateTime.from(ta).toInstant().toEpochMilli(); } catch (DateTimeException ignored) {}

            // Fall back to manual reconstruction from individual fields
            return reconstructMillis(ta, picture);

        } catch (DateTimeParseException e) {
            return Long.MIN_VALUE;
        }
    }

    // =========================================================================
    // Manual reconstruction (when Instant.from / ZonedDateTime.from fail)
    // =========================================================================

    private static long reconstructMillis(TemporalAccessor ta, String picture)
            throws RuntimeEvaluationException {

        boolean hasIsoWeekYear = picture.contains("[X]") || picture.contains("[x]");
        boolean hasIsoWeek     = picture.contains("[W]");
        if (hasIsoWeekYear || hasIsoWeek)
            throw new RuntimeEvaluationException("D3136", "Date/time underspecified");

        boolean hasHours   = picture.contains("[h") || picture.contains("[H]");
        boolean hasMinutes = picture.contains("[m]");
        boolean hasSeconds = picture.contains("[s]");
        if ((hasMinutes || hasSeconds) && !hasHours)
            throw new RuntimeEvaluationException("D3136", "Date/time underspecified");

        boolean hasYear      = picture.contains("[Y");
        boolean hasMonth     = picture.matches(".*\\[M(?!m)[^\\]]*].*");
        boolean hasDayMonth  = picture.contains("[D]");
        boolean hasDayYear   = picture.toLowerCase().contains("[d]") && !picture.contains("[D]");
        if (hasYear && hasDayMonth && !hasMonth && !hasDayYear)
            throw new RuntimeEvaluationException("D3136", "Date/time underspecified");

        boolean pictureHasDate = (picture.contains("[Y") && picture.contains("]"))
                || (picture.contains("[M") && !picture.contains("[m") && !picture.contains("[MA]"))
                || picture.toLowerCase().contains("[d")
                || picture.contains("[F]");
        String lp = picture.toLowerCase();
        boolean pictureHasTime = lp.contains("[h") || lp.contains("[m]") || lp.contains("[s]");

        int hour   = ta.isSupported(ChronoField.HOUR_OF_DAY)     ? ta.get(ChronoField.HOUR_OF_DAY)     : 0;
        int minute = ta.isSupported(ChronoField.MINUTE_OF_HOUR)  ? ta.get(ChronoField.MINUTE_OF_HOUR)  : 0;
        int second = ta.isSupported(ChronoField.SECOND_OF_MINUTE)? ta.get(ChronoField.SECOND_OF_MINUTE): 0;
        int millis = ta.isSupported(ChronoField.MILLI_OF_SECOND) ? ta.get(ChronoField.MILLI_OF_SECOND) : 0;

        // Fix: extract parsed timezone offset instead of always assuming UTC.
        // appendZoneOrOffsetId() stores the zone via TemporalQueries, not as OFFSET_SECONDS field.
        ZoneOffset zone;
        try {
            zone = ZoneOffset.from(ta);
        } catch (DateTimeException ignored) {
            zone = ZoneOffset.UTC;
        }

        if (!pictureHasDate) {
            if (!pictureHasTime) return Long.MIN_VALUE;
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            return LocalDateTime.of(today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                    hour, minute, second, millis * 1_000_000).toInstant(zone).toEpochMilli();
        }

        int year      = ta.isSupported(ChronoField.YEAR)         ? ta.get(ChronoField.YEAR)         : 1;
        int month     = ta.isSupported(ChronoField.MONTH_OF_YEAR)? ta.get(ChronoField.MONTH_OF_YEAR): 1;
        int dayOfMonth= ta.isSupported(ChronoField.DAY_OF_MONTH) ? ta.get(ChronoField.DAY_OF_MONTH) : 1;
        int dayOfYear = ta.isSupported(ChronoField.DAY_OF_YEAR)  ? ta.get(ChronoField.DAY_OF_YEAR)  : -1;

        LocalDate date = (dayOfYear > 0) ? LocalDate.ofYearDay(year, dayOfYear)
                : LocalDate.of(year, month, dayOfMonth);

        return date.atTime(hour, minute, second, millis * 1_000_000).toInstant(zone).toEpochMilli();
    }

    // =========================================================================
    // Preprocessing: convert non-numeric representations to numbers
    // =========================================================================

    private static String preprocess(String timestamp, String picture, boolean dayWordsConverted) {
        String result = timestamp;
        result = stripGmt(result);
        result = normalizeOffset(result);
        if (picture.contains("[D") && picture.contains("o"))
            result = ORDINAL_TAIL.matcher(result).replaceAll("$1");
        if (picture.contains("[Mi]"))
            result = convertRomanMonth(result);
        if (picture.contains("[MA]") && !picture.contains("[M01]"))
            result = convertMonthLetters(result);

        boolean needsDayWords = needsDayWordConversion(picture);
        if (needsDayWords)
            result = convertDayWords(result, picture);

        if (hasYearWordsOrRoman(picture))
            result = convertYearPart(result, picture, needsDayWords);

        // Single-letter day placeholder (non-DW context)
        if (!picture.contains("[DW]")) {
            String[] parts = result.split("\\s+");
            if (parts.length >= 1 && parts[0].length() <= 2 && !parts[0].matches("\\d+")) {
                String up = parts[0].toUpperCase();
                if (!isMonthAbbrev(up)) {
                    int n = letterToDay(parts[0]);
                    if (n > 0) { parts[0] = String.valueOf(n); result = String.join(" ", parts); }
                }
            }
        }
        return result;
    }

    private static String stripGmt(String s) {
        if (!s.contains("GMT")) return s;
        Matcher m = GMT_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String sign  = m.group(1);
            String hours = m.group(2).length() == 1 ? "0" + m.group(2) : m.group(2);
            String mins  = m.group(3) != null ? m.group(3) : "00";
            m.appendReplacement(sb, Matcher.quoteReplacement(sign + hours + ":" + mins));
        }
        m.appendTail(sb);
        String r = sb.toString();
        r = r.replace(" GMT", "").replace("GMT", "+00:00");
        return r;
    }

    private static String normalizeOffset(String s) {
        // " ±HHMM" → " ±HH:MM" at end of string
        Matcher m = BARE_OFFSET.matcher(s);
        if (m.find()) return m.replaceAll(" $1$2:$3");
        return s;
    }

    private static String convertRomanMonth(String input) {
        String[] parts = input.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(" ");
            String p = parts[i];
            String upper = p.toUpperCase();
            if (RomanNumerals.isValid(upper)) {
                sb.append(RomanNumerals.toArabic(upper));
            } else {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private static String convertMonthLetters(String input) {
        String[] parts = input.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(" ");
            Integer n = monthAbbrevToNumber(parts[i].toUpperCase());
            sb.append(n != null ? String.format("%02d", n) : parts[i]);
        }
        return sb.toString();
    }

    private static boolean needsDayWordConversion(String picture) {
        String lower = picture.toLowerCase();
        boolean hasDWwo = picture.contains("[DWwo]");
        boolean hasDW   = picture.matches(".*\\[DW\\].*");
        boolean hasDwo  = lower.contains("[dwo]") || lower.contains("[dwwo]");
        boolean hasDwLower = lower.matches(".*\\[dw\\].*");
        return hasDWwo || hasDwo || hasDwLower
                || (hasDW && lower.contains("[yw]"));
    }

    private static boolean computeDayWordsConverted(String picture) {
        return picture.contains("[DWwo]")
                || picture.toLowerCase().contains("[dwo")
                || (picture.matches(".*\\[DW\\].*") && picture.toLowerCase().contains("[yw]"));
    }

    private static boolean hasYearWordsOrRoman(String picture) {
        String lower = picture.toLowerCase();
        return lower.contains("[yw]") || lower.contains("[ywo")
                || lower.contains("[yi]") || picture.contains("[YI]")
                || (picture.contains("[Y]") && !picture.contains("[Yw]"));
    }

    /**
     * Converts day-in-words tokens to Arabic digits.
     * Handles "[DWwo]" with optional weekday prefix, "[dwo]" day-of-year in words, etc.
     */
    private static String convertDayWords(String input, String picture) {
        String[] words = input.split("\\s+");
        StringBuilder sb = new StringBuilder();

        boolean hasDWwo = picture.contains("[DWwo]");

        for (int i = 0; i < words.length; i++) {
            String word    = words[i];
            String clean   = word.replace(",","").replace(".","");
            String lower   = clean.toLowerCase();

            // For [DWwo], skip a leading weekday abbreviation
            if (i == 0 && hasDWwo) {
                String[] dayAbbrs = {"mon","tue","wed","thu","fri","sat","sun"};
                boolean isDay = false;
                for (String d : dayAbbrs) {
                    if (lower.equals(d) || lower.startsWith(d + ",") || lower.startsWith(d + ".")) {
                        isDay = true; break;
                    }
                }
                if (isDay) { sb.append(word); continue; }
            }

            // For [DWwo]: convert the first convertible word to a number
            if (hasDWwo && sb.length() > 0) {
                String converted = WordNumbers.wordsToDigits(clean);
                if (!converted.equals(clean)) {
                    sb.append(" ").append(converted);
                    for (int j = i + 1; j < words.length; j++) sb.append(" ").append(words[j]);
                    break;
                }
            }

            // Stop at "day" or "of" keywords
            if (lower.equals("day") || lower.equals("of")) {
                for (int j = i; j < words.length; j++) {
                    if (sb.length() > 0 && !sb.toString().endsWith(" ")) sb.append(" ");
                    sb.append(words[j]);
                }
                break;
            }

            // Stop at a month name (not for [DWwo])
            if (!hasDWwo) {
                boolean isMonth = false;
                for (String m : MONTH_NAMES)
                    if (lower.startsWith(m.substring(0, Math.min(3, m.length())))) { isMonth = true; break; }
                if (isMonth) {
                    for (int j = i; j < words.length; j++) {
                        if (sb.length() > 0 && !sb.toString().endsWith(" ")) sb.append(" ");
                        sb.append(words[j]);
                    }
                    break;
                }
            }

            // Accumulate words and try conversion on the running phrase
            StringBuilder test = new StringBuilder();
            for (int k = 0; k <= i; k++) {
                if (k > 0) test.append(" ");
                test.append(words[k].replace(",","").replace(".",""));
            }
            String converted = WordNumbers.wordsToDigits(test.toString());
            if (!converted.equals(test.toString())) {
                sb = new StringBuilder(converted);
            } else if (clean.matches("\\d+")) {
                if (sb.length() > 0 && !sb.toString().endsWith(" ")) sb.append(" ");
                sb.append(word);
            } else {
                if (sb.length() > 0 && !sb.toString().endsWith(" ")) sb.append(" ");
                sb.append(word);
            }
        }
        return sb.toString().trim();
    }

    /**
     * Converts year-in-words or Roman numeral year to digits in the (already day-converted) string.
     */
    private static String convertYearPart(String input, String picture, boolean dayWordsConverted) {
        String lower = picture.toLowerCase();

        // Roman numeral year [YI] / [Yi]
        if (lower.contains("i]") || picture.contains("[YI]")) {
            input = convertRomanTokens(input);
        }

        boolean hasDwForYear =
                (dayWordsConverted && lower.contains("[dwo]") && picture.contains("[Y]") && !picture.contains("[Yw]"))
                || (dayWordsConverted && lower.contains("[dw]") && lower.contains("[yw]"))
                || (dayWordsConverted && picture.contains("[DW]") && lower.contains("[yw]"));

        if (hasDwForYear) {
            // Input has day already converted; find and convert the year portion
            String[] parts = input.split("\\s+");
            int monthIdx = -1, yearStart = -1;
            for (int i = 0; i < parts.length; i++) {
                String lp = parts[i].toLowerCase();
                for (String m : MONTH_NAMES)
                    if (lp.startsWith(m.substring(0, Math.min(3, m.length())))) { monthIdx = i; break; }
                if (lp.equals("day") && i + 2 < parts.length) { yearStart = i + 2; break; }
                if (monthIdx >= 0) break;
            }

            if (monthIdx >= 0 && monthIdx < parts.length - 1) {
                StringBuilder yearWords = new StringBuilder();
                for (int i = monthIdx + 1; i < parts.length; i++) {
                    if (i > monthIdx + 1) yearWords.append(" ");
                    yearWords.append(parts[i]);
                }
                String convertedYear = WordNumbers.wordsToDigits(yearWords.toString());
                if (!convertedYear.equals(yearWords.toString())) {
                    String monthClean = parts[monthIdx].replace(",", "");
                    boolean hasComma  = parts[monthIdx].contains(",");
                    StringBuilder prefix = new StringBuilder();
                    for (int pi = 1; pi < monthIdx; pi++) {
                        if (pi > 1) prefix.append(" ");
                        prefix.append(parts[pi]).append(" ");
                    }
                    input = parts[0] + " " + prefix + monthClean + (hasComma ? ", " : " ") + convertedYear;
                }
            } else if (yearStart >= 0 && yearStart < parts.length) {
                StringBuilder yearWords = new StringBuilder();
                for (int i = yearStart; i < parts.length; i++) {
                    if (i > yearStart) yearWords.append(" ");
                    yearWords.append(parts[i]);
                }
                String convertedYear = WordNumbers.wordsToDigits(yearWords.toString());
                if (!convertedYear.equals(yearWords.toString())) {
                    StringBuilder nb = new StringBuilder();
                    for (int i = 0; i < yearStart - 1; i++) {
                        if (i > 0) nb.append(" ");
                        nb.append(parts[i]);
                    }
                    nb.append(" ").append(convertedYear);
                    input = nb.toString();
                }
            }
        } else {
            // No day-words in picture — convert the whole string if warranted
            boolean hasDwInPic = lower.contains("[dw]");
            boolean hasYwInPic = lower.contains("[yw]");
            if (!(hasDwInPic && hasYwInPic)) {
                String converted = WordNumbers.wordsToDigits(input);
                if (!converted.equals(input)) input = converted;
            }
        }
        return input;
    }

    /** Converts space-separated tokens that are valid Roman numerals to Arabic digits. */
    private static String convertRomanTokens(String input) {
        String[] parts = input.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(" ");
            String p = parts[i];
            // Fix: use RomanNumerals.isValid() to avoid false positives on common words.
            if (RomanNumerals.isValid(p) || RomanNumerals.isValid(p.toUpperCase())) {
                sb.append(RomanNumerals.toArabic(p.toUpperCase()));
            } else {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private static int letterToDay(String letter) {
        int day = 0;
        String upper = letter.toUpperCase();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c >= 'A' && c <= 'Z') day = day * 26 + (c - 'A' + 1);
        }
        return day;
    }

    // =========================================================================
    // DateTimeFormatter builder
    // =========================================================================

    private static java.time.format.DateTimeFormatter buildFormatter(
            String picture, boolean dayWordsConverted) throws RuntimeEvaluationException {
        PictureFormatter.checkBrackets(picture);
        DateTimeFormatterBuilder b = new DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient();
        int i = 0, len = picture.length();
        while (i < len) {
            char c = picture.charAt(i);
            if (c == '[' && i + 1 < len && picture.charAt(i + 1) == '[') {
                b.appendLiteral('['); i += 2;
            } else if (c == '[') {
                int j = picture.indexOf(']', i + 1);
                if (j < 0) throw new RuntimeEvaluationException("D3135", "Unclosed '[' in picture string");
                appendComponent(b, picture.substring(i + 1, j), dayWordsConverted);
                i = j + 1;
            } else if (c == ']' && i + 1 < len && picture.charAt(i + 1) == ']') {
                b.appendLiteral(']'); i += 2;
            } else {
                int j = i;
                while (j < len && picture.charAt(j) != '[' &&
                       !(picture.charAt(j) == ']' && j + 1 < len && picture.charAt(j + 1) == ']'))
                    j++;
                b.appendLiteral(picture.substring(i, j));
                i = j;
            }
        }
        return b.toFormatter(Locale.ENGLISH);
    }

    private static void appendComponent(DateTimeFormatterBuilder b, String spec,
            boolean dayWordsConverted) throws RuntimeEvaluationException {
        if (spec.isEmpty()) return;
        char d = spec.charAt(0);
        String mod = spec.length() > 1 ? spec.substring(1) : "";
        int width = (int) mod.chars().filter(Character::isDigit).count();

        boolean flex = mod.contains("*");
        int minWidth = width > 0 ? width : (flex ? 1 : 0);
        if (flex && mod.contains("-")) {
            String[] parts = mod.split("-", -1);
            if (parts.length > 1 && !parts[1].isEmpty()) {
                try { minWidth = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
            }
        }

        switch (d) {
            case 'Y' -> {
                if (!mod.isEmpty() && (mod.equals("N") || mod.equals("n")))
                    throw new RuntimeEvaluationException("D3133", "Year name component is not supported");
                // Fix: [Yw]/[YI]/[Yi] — preprocessing always converts to digits, so use appendValue.
                // Previously appendText(YEAR) was used for [Yw], which failed to parse digit strings.
                b.appendValue(ChronoField.YEAR, minWidth > 0 ? minWidth : 1, 9, SignStyle.NORMAL);
            }
            case 'M' -> {
                if (!mod.isEmpty() && (mod.charAt(0) == 'N' || mod.charAt(0) == 'n'))
                    b.appendText(ChronoField.MONTH_OF_YEAR);
                else
                    b.appendValue(ChronoField.MONTH_OF_YEAR, minWidth > 0 ? minWidth : 2);
            }
            case 'D' -> {
                if (dayWordsConverted || !mod.contains("w"))
                    b.appendValue(ChronoField.DAY_OF_MONTH, minWidth > 0 ? minWidth : 2);
                else
                    b.appendText(ChronoField.DAY_OF_MONTH);
            }
            case 'd' -> b.appendValue(ChronoField.DAY_OF_YEAR, minWidth > 0 ? minWidth : 3);
            case 'F' -> b.appendText(ChronoField.DAY_OF_WEEK);
            case 'X' -> b.appendValue(ChronoField.YEAR, minWidth > 0 ? minWidth : 4);
            case 'W' -> b.appendValue(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear(), minWidth > 0 ? minWidth : 2);
            case 'x' -> throw new RuntimeEvaluationException("D3136", "Date/time underspecified");
            case 'w' -> throw new RuntimeEvaluationException("D3136", "Date/time underspecified");
            case 'H' -> b.appendValue(ChronoField.HOUR_OF_DAY, minWidth > 0 ? minWidth : 2);
            case 'h' -> b.appendValue(ChronoField.CLOCK_HOUR_OF_AMPM,
                            mod.startsWith("#") ? 1 : Math.max(1, minWidth));
            case 'm' -> b.appendValue(ChronoField.MINUTE_OF_HOUR, minWidth > 0 ? minWidth : 2);
            case 's' -> b.appendValue(ChronoField.SECOND_OF_MINUTE, minWidth > 0 ? minWidth : 2);
            case 'f' -> {
                // Fix: use appendFraction so that 2-digit [f01] is parsed as 0.xy (not xy ms directly).
                if (width > 0) b.appendFraction(ChronoField.MILLI_OF_SECOND, width, width, false);
                else           b.appendFraction(ChronoField.MILLI_OF_SECOND, 1, 3, false);
            }
            case 'P' -> b.appendText(ChronoField.AMPM_OF_DAY, Map.of(0L, "am", 1L, "pm"));
            case 'Z', 'z' -> b.appendZoneOrOffsetId();
            case 'C', 'E' -> b.appendLiteral("ISO");
            default -> throw new RuntimeEvaluationException(
                    "D3132", "Unknown picture-string component: [" + spec + "]");
        }
    }

    // =========================================================================
    // Static lookup tables
    // =========================================================================

    private static boolean isMonthAbbrev(String upper) {
        return switch (upper) {
            case "C","JA","FE","MA","AP","MY","JN","JL","AU","SE","OC","NO","DE" -> true;
            default -> false;
        };
    }

    private static Integer monthAbbrevToNumber(String abbrev) {
        return switch (abbrev) {
            case "JA","JANUARY"   -> 1;  case "FE","FEBRUARY"  -> 2;
            case "MA","MAR","MARCH" -> 3; case "AP","APRIL"     -> 4;
            case "MY","MAY"       -> 5;  case "JN","JUNE"       -> 6;
            case "JL","JULY"      -> 7;  case "AU","AUGUST"     -> 8;
            case "SE","SEPTEMBER" -> 9;  case "OC","OCTOBER"    -> 10;
            case "NO","NOVEMBER"  -> 11; case "DE","DECEMBER"   -> 12;
            case "C"              -> 3;
            default -> null;
        };
    }
}
