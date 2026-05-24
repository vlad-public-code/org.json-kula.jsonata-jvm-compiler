package org.json_kula.jsonata_jvm.runtime.numeric;

import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParsePosition;
import java.util.*;

/**
 * Implements the picture-based integer formatting and parsing logic for
 * {@code $formatInteger} and {@code $parseInteger}.
 *
 * <p>Supported picture strings: decimal patterns (e.g. {@code #,##0}),
 * {@code w}/{@code W}/{@code Ww} (English words), {@code I}/{@code i} (Roman numerals),
 * {@code A}/{@code a} (alphabetic), and any of the above with the {@code ;o}
 * ordinal modifier.
 */
final class IntegerPicture {

    private IntegerPicture() {}

    // =========================================================================
    // Public entry points
    // =========================================================================

    /**
     * Formats a double that exceeds {@code long} range.
     * Only word pictures ({@code w}, {@code W}, {@code Ww}) are supported; all
     * other pictures throw because they require an exact integer representation.
     */
    static String formatLarge(double n, String pic) throws RuntimeEvaluationException {
        boolean ordinal = pic.endsWith(";o");
        String basePic  = ordinal ? pic.substring(0, pic.length() - 2) : pic;
        return switch (basePic) {
            case "w"  -> EnglishWords.toWordsDouble(n, ordinal);
            case "W"  -> EnglishWords.toWordsDouble(n, ordinal).toUpperCase();
            case "Ww" -> EnglishWords.titleCase(EnglishWords.toWordsDouble(n, ordinal));
            default   -> throw new RuntimeEvaluationException(null,
                    "$formatInteger: value is not representable as an integer: " + n);
        };
    }

    /** Formats {@code n} using the given JSONata integer picture string. */
    static String format(long n, String pic) throws RuntimeEvaluationException {
        boolean ordinal = pic.endsWith(";o");
        String basePic  = ordinal ? pic.substring(0, pic.length() - 2) : pic;

        // Validate characters in the positive sub-picture
        String posPic = basePic.contains(";") ? basePic.substring(0, basePic.indexOf(';')) : basePic;
        for (char c : posPic.toCharArray()) {
            if (!(c == '#' || c == '0' || c == ',' || c == ':'
                    || c == 'w' || c == 'W' || c == 'I' || c == 'i' || c == 'A' || c == 'a'
                    || (c >= '٠' && c <= '٩')   // Arabic-Indic digits
                    || (c >= '０' && c <= '９')   // Full-width digits
                    || (c >= '0'     && c <= '9')))
                throw new RuntimeEvaluationException("D3130",
                        "$formatInteger: picture string contains invalid character '" + c + "'");
        }

        return switch (basePic) {
            case "w"  -> EnglishWords.toWords(n, ordinal);
            case "W"  -> EnglishWords.toWords(n, ordinal).toUpperCase();
            case "Ww" -> EnglishWords.titleCase(EnglishWords.toWords(n, ordinal));
            case "I"  -> toRoman(n).toUpperCase();
            case "i"  -> toRoman(n).toLowerCase();
            case "A"  -> toAlpha(n, true);
            case "a"  -> toAlpha(n, false);
            default   -> ordinal ? formatOrdinal(n, basePic) : formatDecimal(n, basePic);
        };
    }

    /** Parses a string back to a long using the given JSONata integer picture string. */
    static long parse(String s, String pic) throws RuntimeEvaluationException {
        boolean ordinal = pic.endsWith(";o");
        String basePic  = ordinal ? pic.substring(0, pic.length() - 2) : pic;

        // Empty input with Roman picture is treated as 0 (spec edge-case)
        if (s.isEmpty() && (basePic.equals("I") || basePic.equals("i"))) return 0;

        // Validate that the picture has at least one digit placeholder or is a named format
        boolean hasValidFormat = switch (basePic) {
            case "I", "i", "A", "a", "w", "W", "Ww" -> true;
            default -> {
                boolean found = false;
                for (char c : basePic.toCharArray()) {
                    if (c == '0' || Character.isDigit(c)) { found = true; break; }
                }
                yield found;
            }
        };
        if (!hasValidFormat)
            throw new RuntimeEvaluationException("D3130", "$parseInteger: unsupported picture string");

        String input = ordinal ? EnglishWords.stripOrdinalSuffix(s) : s;
        return switch (basePic) {
            case "w", "W", "Ww" -> EnglishWords.parseWords(input);
            case "I", "i"       -> parseRoman(input);
            case "A", "a"       -> parseAlpha(input);
            default             -> parseDecimal(input, basePic);
        };
    }

    // =========================================================================
    // Decimal picture formatting
    // =========================================================================

    private static String formatDecimal(long n, String pic) throws RuntimeEvaluationException {
        // Check for Unicode digit placeholders (Arabic-Indic, Full-width)
        boolean hasArabicIndic = false;
        boolean hasFullWidth   = false;
        boolean hasAsciiDigit  = false;
        char unicodeZero = 0;

        for (char c : pic.toCharArray()) {
            if (c >= '٠' && c <= '٩') { hasArabicIndic = true; unicodeZero = '٠'; break; }
            if (c >= '０' && c <= '９') { hasFullWidth   = true; unicodeZero = '０'; break; }
            if (c == '0') hasAsciiDigit = true;
        }
        if ((hasArabicIndic || hasFullWidth) && hasAsciiDigit)
            throw new RuntimeEvaluationException("D3131",
                    "$formatInteger: picture string contains mixed digit groups");
        if (hasArabicIndic && hasFullWidth)
            throw new RuntimeEvaluationException("D3131",
                    "$formatInteger: picture string contains mixed digit groups");

        if (hasArabicIndic || hasFullWidth) {
            // Format with the ASCII picture then translate digits
            char unicodeDigit = (char) (unicodeZero + 1); // any digit in the family
            String ascii = formatDecimal(n, pic.replace(unicodeDigit, '0').replace(unicodeZero, '0'));
            StringBuilder sb = new StringBuilder(ascii.length());
            for (char c : ascii.toCharArray()) {
                sb.append((c >= '0' && c <= '9') ? (char) (unicodeZero + (c - '0')) : c);
            }
            return sb.toString();
        }

        // Determine whether custom grouping logic is needed
        boolean needsCustom = false;

        // Custom separator ':' always needs special handling
        for (char c : pic.toCharArray()) {
            if (c == ':') { needsCustom = true; break; }
        }

        if (!needsCustom && pic.contains(",")) {
            // Count all separators; custom handling only for 2+ separators with regular group sizes
            int sepCount = 0;
            for (char c : pic.toCharArray()) if (c == ',' || c == ':') sepCount++;
            if (sepCount >= 2) {
                List<Integer> groups = new ArrayList<>();
                int cur = 0;
                for (char c : pic.toCharArray()) {
                    if (c == ',' || c == ':' || c == ';') {
                        if (cur > 0) { groups.add(cur); cur = 0; }
                    } else if (c == '#' || c == '0') cur++;
                }
                if (cur > 0) groups.add(cur);
                if (groups.size() >= 2 && groups.get(0).equals(groups.get(1))) needsCustom = true;
            }
        }

        if (needsCustom) {
            // Handle the sign separately so grouping logic only sees digits
            boolean negative = n < 0;
            // Use string arithmetic to avoid Math.abs(Long.MIN_VALUE) overflow
            String plain = negative ? Long.toString(n).substring(1) : Long.toString(n);
            String formatted = applyCustomGrouping(plain, pic);
            return negative ? "-" + formatted : formatted;
        }

        // Standard case — DecimalFormat handles sign correctly for long values
        StringBuilder pat = new StringBuilder();
        for (char c : pic.toCharArray()) {
            if (c == ';') break;
            if (c == '#' || c == '0' || c == ',') pat.append(c);
        }
        if (pat.isEmpty()) pat.append('0');

        DecimalFormatSymbols dfs = new DecimalFormatSymbols(Locale.ROOT);
        DecimalFormat df = new DecimalFormat(pat.toString(), dfs);
        df.setRoundingMode(RoundingMode.HALF_EVEN);
        return df.format(n);
    }

    /**
     * Applies custom grouping separators to a string of digits (no sign).
     * Supports both ',' and ':' as separator characters.
     */
    private static String applyCustomGrouping(String plain, String pic) {
        // Find first separator and count digit groups after it
        int firstSepIdx = -1;
        char firstSep = ',';
        int leadingPlaceholders = 0;
        int preSepDigits = 0;

        for (int i = 0; i < pic.length(); i++) {
            char c = pic.charAt(i);
            if (c == ';') break;
            if (c == ':' || c == ',') {
                firstSepIdx = i;
                firstSep = c;
                leadingPlaceholders = preSepDigits;
                break;
            }
            if (c == '#' || c == '0') preSepDigits++;
        }

        if (firstSepIdx < 0) return plain; // no separator found

        // Count digit groups after the first separator (right-to-left)
        List<Integer> rightGroups = new ArrayList<>();
        int cnt = 0;
        for (int i = pic.length() - 1; i > firstSepIdx; i--) {
            char c = pic.charAt(i);
            if (c == '#' || c == '0') {
                cnt++;
            } else if (c == ',' || c == ':') {
                if (cnt > 0) { rightGroups.add(cnt); cnt = 0; }
            }
        }
        if (cnt > 0) rightGroups.add(cnt);
        Collections.reverse(rightGroups);

        int fixedSize = 0;
        for (int g : rightGroups) fixedSize += g;

        int leadingDigits = Math.max(leadingPlaceholders, plain.length() - fixedSize);
        leadingDigits = Math.min(leadingDigits, plain.length());

        StringBuilder result = new StringBuilder();
        result.append(plain, 0, leadingDigits);
        int pos = leadingDigits;
        for (int gi = 0; gi < rightGroups.size() && pos < plain.length(); gi++) {
            int size = rightGroups.get(gi);
            result.append(gi == 0 ? firstSep : ',');
            result.append(plain, pos, Math.min(pos + size, plain.length()));
            pos += size;
        }
        if (pos < plain.length()) {
            result.append(',');
            result.append(plain.substring(pos));
        }
        return result.toString();
    }

    private static String formatOrdinal(long n, String pic) throws RuntimeEvaluationException {
        return formatDecimal(n, pic) + ordinalSuffix(n);
    }

    /**
     * Returns the ordinal suffix (st, nd, rd, th) for {@code n}.
     * Uses absolute value of the modulus so negative numbers work correctly
     * (e.g., -1 → "st" not "th").
     */
    static String ordinalSuffix(long n) {
        int lastTwo = (int) Math.abs(n % 100);
        int lastOne = (int) Math.abs(n % 10);
        if (lastTwo >= 11 && lastTwo <= 13) return "th";
        return switch (lastOne) {
            case 1  -> "st";
            case 2  -> "nd";
            case 3  -> "rd";
            default -> "th";
        };
    }

    // =========================================================================
    // Roman numerals (extended: 1 – 3,999,999)
    // =========================================================================

    // The standard IVXLCDM table extended by repetition (standard algorithm).
    private static final int[]    ROMAN_VALS = {
        1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1
    };
    private static final String[] ROMAN_SYMS = {
        "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"
    };

    /**
     * Converts {@code n} to a Roman numeral string.
     * Supports 1 through 3,999,999; zero and negatives throw.
     */
    static String toRoman(long n) throws RuntimeEvaluationException {
        if (n == 0) return "";
        if (n < 0 || n > 3_999_999)
            throw new RuntimeEvaluationException(null,
                    "$formatInteger: Roman numerals are only supported for 1–3,999,999");
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < ROMAN_VALS.length; k++) {
            while (n >= ROMAN_VALS[k]) { sb.append(ROMAN_SYMS[k]); n -= ROMAN_VALS[k]; }
        }
        return sb.toString();
    }

    /** Parses a Roman numeral string (case-insensitive) to a long. */
    static long parseRoman(String s) throws RuntimeEvaluationException {
        if (s == null || s.isEmpty()) return 0;
        s = s.toUpperCase().trim();
        Map<Character, Integer> vals = Map.of(
                'I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100, 'D', 500, 'M', 1000);
        long result = 0;
        int prev = 0;
        for (int k = s.length() - 1; k >= 0; k--) {
            char c = s.charAt(k);
            int cv = vals.getOrDefault(c, -1);
            if (cv < 0)
                throw new RuntimeEvaluationException(null,
                        "$parseInteger: invalid Roman numeral character '" + c + "'");
            result += (cv < prev) ? -cv : cv;
            prev = cv;
        }
        return result;
    }

    // =========================================================================
    // Alphabetic (A, B … Z, AA, AB …)
    // =========================================================================

    /** Converts {@code n} (1-based) to an alphabetic label. */
    static String toAlpha(long n, boolean upper) throws RuntimeEvaluationException {
        if (n <= 0)
            throw new RuntimeEvaluationException(null,
                    "$formatInteger: alphabetic format requires a positive integer");
        char base = upper ? 'A' : 'a';
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            n--;
            sb.insert(0, (char) (base + n % 26));
            n /= 26;
        }
        return sb.toString();
    }

    /** Parses an alphabetic label back to a 1-based long. */
    static long parseAlpha(String s) throws RuntimeEvaluationException {
        s = s.toUpperCase().trim();
        long result = 0;
        for (char c : s.toCharArray()) {
            if (c < 'A' || c > 'Z')
                throw new RuntimeEvaluationException(null,
                        "$parseInteger: invalid alphabetic character '" + c + "'");
            result = result * 26 + (c - 'A' + 1);
        }
        return result;
    }

    // =========================================================================
    // Decimal picture parsing
    // =========================================================================

    private static long parseDecimal(String s, String pic) throws RuntimeEvaluationException {
        char zeroDigit = findZeroDigit(pic);
        String normalized = normalizeUnicodeDigits(s, zeroDigit);

        // Strip grouping separators extracted from the picture
        String seps = extractGroupingSeparators(pic);
        String stripped = normalized;
        for (char sep : seps.toCharArray()) {
            stripped = stripped.replace(String.valueOf(sep), "");
        }

        String pattern = convertPictureToPattern(pic);
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setZeroDigit('0');

        DecimalFormat df = new DecimalFormat(pattern, symbols);
        df.setParseBigDecimal(true);
        ParsePosition pos = new ParsePosition(0);
        Number number = df.parse(stripped, pos);

        if (number == null || pos.getIndex() != stripped.length())
            throw new RuntimeEvaluationException(null,
                    "$parseInteger: cannot parse \"" + s + "\" with picture \"" + pic + "\"");
        return number.longValue();
    }

    private static char findZeroDigit(String pic) {
        for (char c : pic.toCharArray()) {
            if (Character.isDigit(c)) {
                int val = Character.getNumericValue(c);
                if (val >= 0 && val <= 9) return (char) (c - val);
            }
        }
        return '0';
    }

    private static String normalizeUnicodeDigits(String input, char zeroDigit) {
        if (zeroDigit == '0') return input;
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            if (Character.isDigit(c)) {
                int val = Character.getNumericValue(c);
                sb.append((val >= 0 && val <= 9) ? (char) ('0' + val) : c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String extractGroupingSeparators(String pic) {
        StringBuilder sb = new StringBuilder();
        for (char c : pic.toCharArray()) {
            if (!Character.isDigit(c) && c != '#' && c != '0') sb.append(c);
        }
        return sb.toString();
    }

    private static String convertPictureToPattern(String pic) {
        StringBuilder sb = new StringBuilder();
        for (char c : pic.toCharArray()) {
            if (Character.isDigit(c)) sb.append('0');
            else if (c == '#') sb.append('#');
            else if (c == '0') sb.append('0');
            // grouping separators intentionally omitted (stripped from input separately)
        }
        return sb.toString();
    }
}
