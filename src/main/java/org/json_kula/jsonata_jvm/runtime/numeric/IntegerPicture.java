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
public final class IntegerPicture {

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

    /**
     * Formats {@code n} using the given JSONata integer picture string.
     *
     * <p>Analyses the picture then formats against it. The two halves are separate so the
     * date/time picture formatter can analyse once and format each component through the
     * same engine, which is what keeps {@code $formatInteger} and a date component's
     * integer modifiers from drifting apart.
     */
    public static String format(long n, String pic) throws RuntimeEvaluationException {
        return format(n, analyse(pic));
    }

    /**
     * Parses {@code s} back to a number using the given integer picture, or returns
     * {@code NaN} when it does not parse.
     *
     * <p>The reference does <em>no</em> validation: it builds a matcher from the picture
     * and then simply runs the picture's own parse function over the input, so a
     * mismatched input falls out as NaN rather than an error. That is why
     * {@code $parseInteger("MCMXCIV", "0")} is undefined rather than a thrown error, and
     * why {@code $parseInteger("1,234", "0")} is {@code 1} — JavaScript's {@code parseInt}
     * stops at the comma, which the {@code "0"} picture does not name as a separator.
     * Rejecting these instead turned four in five of the probe's parse cases into errors.
     */
    public static double parse(String s, String pic) throws RuntimeEvaluationException {
        Analysis f = analyse(pic);
        boolean upper = f.textCase() == TextCase.UPPER;
        return switch (f.primary()) {
            case LETTERS -> lettersToDecimal(s, upper);
            case ROMAN -> romanToDecimal(upper ? s : s.toUpperCase(java.util.Locale.ROOT));
            case WORDS -> EnglishWords.wordsToNumber(s.toLowerCase(java.util.Locale.ROOT));
            case DECIMAL -> parseDecimalPicture(s, f);
            case SEQUENCE -> throw new RuntimeEvaluationException("D3130",
                    "$parseInteger: unsupported numbering sequence '" + f.token() + "'");
        };
    }

    /** Strips the picture's separators and ordinal suffix, then parses the digits. */
    private static double parseDecimalPicture(String s, Analysis f) {
        String digits = s;
        if (f.ordinal()) {
            // The reference drops the last two characters unconditionally, whether or not
            // they are an ordinal suffix: $parseInteger("007", "1;o") is 0, not 7. Only
            // stripping a genuine suffix reads better but disagrees on every input that
            // does not carry one.
            digits = digits.length() >= 2 ? digits.substring(0, digits.length() - 2) : "";
        }
        if (f.regular()) {
            digits = digits.replace(f.regularCharacter(), "");
        } else {
            for (Separator separator : f.separators()) {
                digits = digits.replace(separator.character(), "");
            }
        }
        if (f.zeroCode() != 0x30) {
            StringBuilder sb = new StringBuilder(digits.length());
            for (int i = 0; i < digits.length(); i++) {
                sb.appendCodePoint(digits.charAt(i) - f.zeroCode() + 0x30);
            }
            digits = sb.toString();
        }
        return jsParseInt(digits);
    }

    /**
     * JavaScript {@code parseInt}: skips leading whitespace, takes an optional sign and
     * then as many leading decimal digits as there are, and is NaN if there are none.
     * Trailing junk is ignored rather than rejected.
     */
    private static double jsParseInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        boolean negative = false;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        int digitsStart = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == digitsStart) return Double.NaN;
        double value = Double.parseDouble(s.substring(digitsStart, i));
        return negative ? -value : value;
    }

    /**
     * Spreadsheet-style letters back to a number. Deliberately unvalidated, like the
     * reference: a character outside the alphabet contributes its offset rather than
     * raising.
     */
    private static double lettersToDecimal(String letters, boolean upper) {
        int base = upper ? 'A' : 'a';
        double decimal = 0;
        for (int i = 0; i < letters.length(); i++) {
            decimal += (letters.charAt(letters.length() - i - 1) - base + 1) * Math.pow(26, i);
        }
        return decimal;
    }

    /** Roman numerals back to a number; NaN if any character is not a numeral. */
    static double romanToDecimal(String roman) {
        double decimal = 0;
        int max = 1;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int value = switch (roman.charAt(i)) {
                case 'M' -> 1000;
                case 'D' -> 500;
                case 'C' -> 100;
                case 'L' -> 50;
                case 'X' -> 10;
                case 'V' -> 5;
                case 'I' -> 1;
                default -> -1;
            };
            if (value < 0) return Double.NaN;
            if (value < max) {
                decimal -= value;
            } else {
                max = value;
                decimal += value;
            }
        }
        return decimal;
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
     * Converts a non-negative {@code n} to a Roman numeral string.
     *
     * <p>Zero is the empty string and there is no upper bound: above 3,999 the reference
     * simply repeats {@code M}, so a million is a million-over-thousand Ms. Rejecting
     * large values instead made every {@code [YI]} date picture past the year 3999 throw.
     * The sign is handled by {@link #format(long, Analysis)}, which passes a magnitude.
     */
    static String toRoman(long n) throws RuntimeEvaluationException {
        if (n <= 0) return "";
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

    /**
     * Converts a 1-based {@code n} to a spreadsheet-style alphabetic label.
     *
     * <p>Zero yields the empty string rather than an error — the reference's loop simply
     * does not execute — and the sign never reaches here.
     */
    static String toAlpha(long n, boolean upper) throws RuntimeEvaluationException {
        if (n <= 0) return "";
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

    // =========================================================================
    // Picture analysis — port of jsonata datetime.js `analyseIntegerPicture`
    // =========================================================================

    /** Primary format selected by the picture's body. */
    public enum Primary { DECIMAL, LETTERS, ROMAN, WORDS, SEQUENCE }

    /** Letter case implied by the picture's body. */
    public enum TextCase { UPPER, LOWER, TITLE }

    /** One grouping separator, at a position counted from the right. */
    public record Separator(int position, String character) {}

    /**
     * An analysed integer picture. Immutable on purpose: analyses are shared between
     * {@code $formatInteger} and the date/time picture formatter, and a formatter that
     * mutated one would corrupt every later use of the same picture.
     */
    public record Analysis(Primary primary, TextCase textCase, boolean ordinal,
                    int zeroCode, int mandatoryDigits, int optionalDigits,
                    boolean regular, int regularPosition, String regularCharacter,
                    List<Separator> separators, String token) {}

    /**
     * Unicode decimal digit families. A picture may draw its digits from any one of
     * them; mixing two is D3131.
     */
    private static final int[] DECIMAL_GROUPS = {
        0x30, 0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6,
        0x0C66, 0x0CE6, 0x0D66, 0x0DE6, 0x0E50, 0x0ED0, 0x0F20, 0x1040, 0x1090, 0x17E0,
        0x1810, 0x1946, 0x19D0, 0x1A80, 0x1A90, 0x1B50, 0x1BB0, 0x1C40, 0x1C50, 0xA620,
        0xA8D0, 0xA900, 0xA9D0, 0xA9F0, 0xAA50, 0xABF0, 0xFF10
    };

    /**
     * Analyses an integer picture string into the descriptor {@link #format(long, Analysis)}
     * consumes.
     *
     * <p>A picture body that contains no decimal digit at all is a <em>numbering
     * sequence</em>, which the spec leaves implementation-defined and neither this
     * implementation nor the reference supports — hence {@code "#"} and {@code "###"},
     * which have optional digits but no mandatory one, are D3130 rather than plain
     * decimal output.
     */
    /**
     * Analysed pictures, memoized. A picture is a compile-time literal at almost every
     * call site, so this is a per-call parse saved rather than a cache miss deferred.
     */
    private static final org.json_kula.jsonata_jvm.runtime.PictureCache<Analysis> CACHE =
            new org.json_kula.jsonata_jvm.runtime.PictureCache<>(512, picture -> {
                try {
                    return analyseUncached(picture);
                } catch (RuntimeEvaluationException e) {
                    throw new org.json_kula.jsonata_jvm.runtime.PictureCache.AnalysisFailure(e);
                }
            });

    public static Analysis analyse(String picture) throws RuntimeEvaluationException {
        try {
            return CACHE.get(picture);
        } catch (org.json_kula.jsonata_jvm.runtime.PictureCache.AnalysisFailure e) {
            throw e.unwrap();
        }
    }

    private static Analysis analyseUncached(String picture) throws RuntimeEvaluationException {
        Primary primary = Primary.DECIMAL;
        TextCase textCase = TextCase.LOWER;
        boolean ordinal = false;

        String primaryFormat;
        int semicolon = picture.lastIndexOf(';');
        if (semicolon < 0) {
            primaryFormat = picture;
        } else {
            primaryFormat = picture.substring(0, semicolon);
            String modifier = picture.substring(semicolon + 1);
            if (!modifier.isEmpty() && modifier.charAt(0) == 'o') ordinal = true;
        }

        switch (primaryFormat) {
            case "A": return new Analysis(Primary.LETTERS, TextCase.UPPER, ordinal,
                    0, 0, 0, false, 0, null, List.of(), null);
            case "a": return new Analysis(Primary.LETTERS, TextCase.LOWER, ordinal,
                    0, 0, 0, false, 0, null, List.of(), null);
            case "I": return new Analysis(Primary.ROMAN, TextCase.UPPER, ordinal,
                    0, 0, 0, false, 0, null, List.of(), null);
            case "i": return new Analysis(Primary.ROMAN, TextCase.LOWER, ordinal,
                    0, 0, 0, false, 0, null, List.of(), null);
            case "W": return new Analysis(Primary.WORDS, TextCase.UPPER, ordinal,
                    0, 0, 0, false, 0, null, List.of(), null);
            case "Ww": return new Analysis(Primary.WORDS, TextCase.TITLE, ordinal,
                    0, 0, 0, false, 0, null, List.of(), null);
            case "w": return new Analysis(Primary.WORDS, TextCase.LOWER, ordinal,
                    0, 0, 0, false, 0, null, List.of(), null);
            default: break;
        }

        // Decimal-digit pattern. The picture is walked right-to-left so a separator's
        // position is its distance from the low-order end.
        int zeroCode = -1;
        int mandatoryDigits = 0;
        int optionalDigits = 0;
        int separatorPosition = 0;
        List<Separator> separators = new ArrayList<>();

        int[] codePoints = primaryFormat.codePoints().toArray();
        for (int i = codePoints.length - 1; i >= 0; i--) {
            int codePoint = codePoints[i];
            boolean digit = false;
            for (int group : DECIMAL_GROUPS) {
                if (codePoint >= group && codePoint <= group + 9) {
                    digit = true;
                    mandatoryDigits++;
                    separatorPosition++;
                    if (zeroCode < 0) {
                        zeroCode = group;
                    } else if (group != zeroCode) {
                        throw new RuntimeEvaluationException("D3131",
                                "$formatInteger: picture string mixes decimal digit families");
                    }
                    break;
                }
            }
            if (digit) continue;
            if (codePoint == '#') {
                separatorPosition++;
                optionalDigits++;
            } else {
                separators.add(new Separator(separatorPosition,
                        new String(Character.toChars(codePoint))));
            }
        }

        if (mandatoryDigits == 0) {
            return new Analysis(Primary.SEQUENCE, textCase, ordinal,
                    0, 0, 0, false, 0, null, List.of(), primaryFormat);
        }

        int regular = regularRepeat(separators);
        if (regular > 0) {
            return new Analysis(Primary.DECIMAL, textCase, ordinal, zeroCode,
                    mandatoryDigits, optionalDigits, true, regular,
                    separators.get(0).character(), List.of(), null);
        }
        return new Analysis(Primary.DECIMAL, textCase, ordinal, zeroCode,
                mandatoryDigits, optionalDigits, false, 0, null,
                List.copyOf(separators), null);
    }

    /**
     * Returns the repeat interval if the grouping separators are "regular" — same
     * character, equally spaced — and 0 otherwise. Regularity means the separator can be
     * applied by a simple modulo walk instead of at recorded positions, which is what
     * lets {@code "#,##0"} group a number of any length.
     */
    private static int regularRepeat(List<Separator> separators) {
        if (separators.isEmpty()) return 0;
        String character = separators.get(0).character();
        for (Separator s : separators) {
            if (!s.character().equals(character)) return 0;
        }
        int factor = separators.get(0).position();
        for (Separator s : separators) factor = gcd(factor, s.position());
        for (int index = 1; index <= separators.size(); index++) {
            int wanted = index * factor;
            boolean found = false;
            for (Separator s : separators) {
                if (s.position() == wanted) { found = true; break; }
            }
            if (!found) return 0;
        }
        return factor;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    // =========================================================================
    // Formatting — port of jsonata datetime.js `_formatInteger`
    // =========================================================================

    /**
     * Formats {@code value} against an already-analysed picture.
     *
     * <p>The sign is stripped first and re-attached last, for <em>every</em> primary
     * format. That is the whole of the negative-number handling and it is why
     * {@code $formatInteger(-7, "01")} is {@code "-07"} rather than {@code "-7"} and why
     * a Roman or alphabetic picture formats {@code -7} rather than rejecting it: the
     * conversions only ever see a magnitude.
     */
    public static String format(long value, Analysis f) throws RuntimeEvaluationException {
        boolean negative = value < 0;
        long magnitude = Math.abs(value);
        String formatted;

        switch (f.primary()) {
            case LETTERS -> formatted = toAlpha(magnitude, f.textCase() == TextCase.UPPER);
            case ROMAN -> {
                formatted = toRoman(magnitude);
                formatted = f.textCase() == TextCase.UPPER
                        ? formatted.toUpperCase() : formatted.toLowerCase();
            }
            case WORDS -> {
                formatted = EnglishWords.numberToWords(magnitude, f.ordinal());
                if (f.textCase() == TextCase.UPPER) formatted = formatted.toUpperCase();
                else if (f.textCase() == TextCase.LOWER) formatted = formatted.toLowerCase();
            }
            case SEQUENCE -> throw new RuntimeEvaluationException("D3130",
                    "$formatInteger: unsupported numbering sequence '" + f.token() + "'");
            case DECIMAL -> formatted = formatDecimal(magnitude, f);
            default -> throw new IllegalStateException();
        }

        return negative ? "-" + formatted : formatted;
    }

    /** Formats a non-negative magnitude against a decimal-digit pattern. */
    private static String formatDecimal(long magnitude, Analysis f) {
        StringBuilder sb = new StringBuilder(Long.toString(magnitude));
        int padLength = f.mandatoryDigits() - sb.length();
        if (padLength > 0) sb.insert(0, "0".repeat(padLength));

        String formatted = sb.toString();
        if (f.zeroCode() != 0x30) {
            StringBuilder translated = new StringBuilder(formatted.length());
            for (int i = 0; i < formatted.length(); i++) {
                translated.appendCodePoint(formatted.charAt(i) + f.zeroCode() - 0x30);
            }
            formatted = translated.toString();
        }

        if (f.regular()) {
            int n = (formatted.length() - 1) / f.regularPosition();
            for (int i = n; i > 0; i--) {
                int pos = formatted.length() - i * f.regularPosition();
                formatted = formatted.substring(0, pos) + f.regularCharacter() + formatted.substring(pos);
            }
        } else {
            // Irregular separators are applied at recorded positions, working from the
            // widest inwards. The reference does this with JavaScript's substr(), whose
            // negative-argument behaviour is load-bearing rather than incidental: a
            // separator positioned beyond the number's width makes substr(0, negative)
            // return "" and substr(negative) count back from the end, so digits are
            // dropped — $formatInteger(3999, "#,##,##0") really is ",9" in the reference.
            // Skipping those positions instead would silently disagree on every short
            // value, so jsSubstr reproduces the semantics rather than sanitising them.
            List<Separator> reversed = new ArrayList<>(f.separators());
            Collections.reverse(reversed);
            for (Separator separator : reversed) {
                int pos = formatted.length() - separator.position();
                formatted = jsSubstr(formatted, 0, pos)
                        + separator.character()
                        + jsSubstr(formatted, pos);
            }
        }

        if (f.ordinal()) {
            char lastDigit = formatted.charAt(formatted.length() - 1);
            String suffix = switch (lastDigit) {
                case '1' -> "st";
                case '2' -> "nd";
                case '3' -> "rd";
                default -> "th";
            };
            // 11th, 12th, 13th — and, following the reference, any number whose
            // penultimate character is '1'.
            if (formatted.length() > 1 && formatted.charAt(formatted.length() - 2) == '1') {
                suffix = "th";
            }
            formatted = formatted + suffix;
        }
        return formatted;
    }


    /** JavaScript {@code String.prototype.substr(start)}. */
    private static String jsSubstr(String s, int start) {
        if (start < 0) start = Math.max(s.length() + start, 0);
        if (start >= s.length()) return "";
        return s.substring(start);
    }

    /** JavaScript {@code String.prototype.substr(start, length)}. */
    private static String jsSubstr(String s, int start, int length) {
        if (start < 0) start = Math.max(s.length() + start, 0);
        if (length <= 0 || start >= s.length()) return "";
        return s.substring(start, Math.min(start + length, s.length()));
    }

    /**
     * Returns a copy with {@code mandatoryDigits} raised to {@code min}.
     *
     * <p>A date/time width modifier ({@code [Y,4]}) widens the marker's integer format.
     * The reference assigns into the analysis in place; here the analysis is an immutable
     * record precisely so a marker cannot corrupt a shared or cached one.
     */
    public static Analysis withMandatoryDigits(Analysis f, int min) {
        if (f.mandatoryDigits() >= min) return f;
        return setMandatoryDigits(f, min);
    }

    /**
     * Returns a copy with {@code mandatoryDigits} set to {@code digits}, raising or
     * lowering it.
     *
     * <p>Distinct from {@link #withMandatoryDigits} on purpose: a width <em>minimum</em>
     * only raises the count, but §9.8.4.4's year truncation assigns it outright, so
     * {@code [Y0001,2-2]} yields two digits rather than the picture's four.
     */
    public static Analysis setMandatoryDigits(Analysis f, int digits) {
        return new Analysis(f.primary(), f.textCase(), f.ordinal(), f.zeroCode(),
                digits, f.optionalDigits(), f.regular(), f.regularPosition(),
                f.regularCharacter(), f.separators(), f.token());
    }
}
