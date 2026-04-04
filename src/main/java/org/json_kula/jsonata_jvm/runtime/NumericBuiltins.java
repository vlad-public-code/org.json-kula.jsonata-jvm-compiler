package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

/**
 * Numeric built-in functions for JSONata.
 *
 * <p>Implements: {@code $number} (with radix literal support), {@code $round}
 * (with precision and half-to-even rounding), {@code $random}, {@code $formatBase},
 * {@code $formatNumber}, {@code $formatInteger}, {@code $parseInteger}.
 *
 * <p>All methods are package-private static helpers delegated from
 * {@link JsonataRuntime}.
 */
final class NumericBuiltins {

    private NumericBuiltins() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    // =========================================================================
    // $number — with 0x / 0o / 0b radix-literal support
    // =========================================================================

    static JsonNode fn_number(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (arg.isNumber())  return arg;
        if (arg.isBoolean()) return JsonataRuntime.numNode(arg.booleanValue() ? 1 : 0);
        if (arg.isNull())
            throw new RuntimeEvaluationException("$number: cannot convert null to a number");
        if (arg.isTextual()) {
            String s = arg.textValue().trim();
            try {
                if (s.startsWith("0x") || s.startsWith("0X"))
                    return JsonataRuntime.numNode(Long.parseLong(s.substring(2), 16));
                if (s.startsWith("0o") || s.startsWith("0O"))
                    return JsonataRuntime.numNode(Long.parseLong(s.substring(2), 8));
                if (s.startsWith("0b") || s.startsWith("0B"))
                    return JsonataRuntime.numNode(Long.parseLong(s.substring(2), 2));
                return JsonataRuntime.numNode(Double.parseDouble(s));
            } catch (NumberFormatException e) {
                throw new RuntimeEvaluationException(
                        "D3030: $number: unable to cast value to a number: " + s);
            }
        }
        throw new RuntimeEvaluationException("D3030: $number: unable to cast value to a number");
    }

    // =========================================================================
    // $round — precision + half-to-even (banker's rounding)
    // =========================================================================

    static JsonNode fn_round(JsonNode number, JsonNode precision) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number)) return JsonataRuntime.MISSING;
        double v = JsonataRuntime.toNumber(number);
        if (Double.isNaN(v) || Double.isInfinite(v)) return NF.numberNode(v);
        int p = JsonataRuntime.missing(precision) ? 0 : (int) JsonataRuntime.toNumber(precision);
        // Use Double.toString to avoid binary-fraction noise before rounding
        BigDecimal bd = new BigDecimal(Double.toString(v)).setScale(p, RoundingMode.HALF_EVEN);
        return JsonataRuntime.numNode(bd.doubleValue());
    }

    // =========================================================================
    // $random
    // =========================================================================

    static JsonNode fn_random() {
        return NF.numberNode(Math.random());
    }

    // =========================================================================
    // $formatBase
    // =========================================================================

    static JsonNode fn_formatBase(JsonNode number, JsonNode radix) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number)) return JsonataRuntime.MISSING;
        long n = (long) JsonataRuntime.toNumber(number);
        int r = JsonataRuntime.missing(radix) ? 10 : (int) JsonataRuntime.toNumber(radix);
        if (r < 2 || r > 36)
            throw new RuntimeEvaluationException("$formatBase: radix must be between 2 and 36");
        return NF.textNode(Long.toString(n, r));
    }

    // =========================================================================
    // $formatNumber
    // =========================================================================

    static JsonNode fn_formatNumber(JsonNode number, JsonNode picture, JsonNode options)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number) || JsonataRuntime.missing(picture))
            return JsonataRuntime.MISSING;

        double v = JsonataRuntime.toNumber(number);
        String pic = JsonataRuntime.toText(picture);

        // -- Parse options -------------------------------------------------
        char decimalSep  = optChar(options, "decimal-separator",  '.');
        char groupSep    = optChar(options, "grouping-separator", ',');
        char exponentSep = optChar(options, "exponent-separator", 'e');
        String percent   = optStr(options,  "percent",            "%");
        String perMille  = optStr(options,  "per-mille",          "\u2030"); // ‰
        char zeroDigit   = optChar(options, "zero-digit",         '0');
        char digitChar   = optChar(options, "digit",              '#');
        char patternSep  = optChar(options, "pattern-separator",  ';');
        String minusSign = optStr(options,  "minus-sign",         "-");

        // -- Split positive / negative sub-pictures ------------------------
        int sepIdx = pic.indexOf(patternSep);
        String posPic = sepIdx >= 0 ? pic.substring(0, sepIdx) : pic;
        String negPic = sepIdx >= 0 ? pic.substring(sepIdx + 1) : null;

        // -- Detect percent / per-mille from the positive sub-picture ------
        boolean hasPercent  = posPic.contains(percent);
        boolean hasPerMille = posPic.contains(perMille);

        boolean isNeg = v < 0;
        double  work  = hasPercent  ? Math.abs(v) * 100
                      : hasPerMille ? Math.abs(v) * 1000
                      : Math.abs(v);

        String activePic = (isNeg && negPic != null) ? negPic : posPic;

        String result = formatPicture(work, activePic,
                decimalSep, groupSep, exponentSep,
                percent, perMille, zeroDigit, digitChar);

        if (isNeg && negPic == null) result = minusSign + result;
        return NF.textNode(result);
    }

    // -----------------------------------------------------------------------
    // Core picture formatter (single sub-picture, value is non-negative)
    // -----------------------------------------------------------------------

    private static String formatPicture(double v, String pic,
            char decimalSep, char groupSep, char exponentSep,
            String percent, String perMille,
            char zeroDigit, char digitChar) throws RuntimeEvaluationException {

        // --- Walk the picture: separate prefix, core specs, suffix --------
        StringBuilder prefix  = new StringBuilder();
        StringBuilder intSpec = new StringBuilder();   // '#', '0', ','
        StringBuilder fracSpec= new StringBuilder();   // '#', '0'
        StringBuilder expSpec = new StringBuilder();   // '#', '0'
        StringBuilder suffix  = new StringBuilder();

        boolean inCore       = false;
        boolean pastDecimal  = false;
        boolean pastExponent = false;
        boolean inSuffix     = false;

        int i = 0;
        outer:
        while (i < pic.length()) {
            // Check multi-char special strings (percent / per-mille) first
            for (String special : new String[]{ percent, perMille }) {
                if (pic.startsWith(special, i)) {
                    if (!inCore || inSuffix) {
                        (inCore ? suffix : prefix).append(special);
                    } else {
                        inSuffix = true;
                        suffix.append(special);
                    }
                    i += special.length();
                    continue outer;
                }
            }

            char c = pic.charAt(i);
            boolean isMand = isMandatoryDigit(c, zeroDigit);
            boolean isOpt  = (c == digitChar);
            boolean isDec  = (c == decimalSep);
            boolean isGrp  = (c == groupSep);
            boolean isExp  = (c == exponentSep);
            boolean isCore = isMand || isOpt || isDec || isGrp || isExp;

            if (!inCore && isCore) inCore = true;

            if (!inCore) {
                prefix.append(c);
            } else if (inSuffix) {
                suffix.append(c);
            } else if (pastExponent) {
                if (isMand || isOpt) expSpec.append(isMand ? '0' : '#');
                else { inSuffix = true; suffix.append(c); }
            } else if (pastDecimal) {
                if (isMand || isOpt)  fracSpec.append(isMand ? '0' : '#');
                else if (isGrp)       { /* ignore grouping in fraction */ }
                else if (isExp)       pastExponent = true;
                else                  { inSuffix = true; suffix.append(c); }
            } else {
                // integer part
                if (isMand || isOpt)  intSpec.append(isMand ? '0' : '#');
                else if (isGrp)       intSpec.append(',');
                else if (isDec)       pastDecimal = true;
                else if (isExp)       pastExponent = true;
                else                  { inSuffix = true; suffix.append(c); }
            }
            i++;
        }

        // If no digit pattern was found, treat entire picture as prefix
        if (!inCore) {
            // degenerate — format with no pattern
            intSpec.append("0");
        }

        // --- Build Java DecimalFormat pattern -----------------------------
        StringBuilder javaPat = new StringBuilder();
        // Quote prefix/suffix so DecimalFormat treats them as literal
        appendQuoted(javaPat, prefix.toString());
        javaPat.append(intSpec.length() > 0 ? intSpec : "0");
        if (pastDecimal) {
            javaPat.append('.');
            javaPat.append(fracSpec);
        }
        if (pastExponent) {
            javaPat.append('E');
            javaPat.append(expSpec.length() > 0 ? expSpec : "0");
        }
        appendQuoted(javaPat, suffix.toString());

        // --- Format using DecimalFormat -----------------------------------
        DecimalFormatSymbols dfs = new DecimalFormatSymbols(Locale.ROOT);
        dfs.setDecimalSeparator('.');
        dfs.setGroupingSeparator(',');
        dfs.setZeroDigit('0');

        DecimalFormat df = new DecimalFormat(javaPat.toString(), dfs);
        df.setRoundingMode(RoundingMode.HALF_EVEN);

        // Use BigDecimal constructed from the string representation of v so that
        // e.g. 34.555 is treated as exactly 34.555 (not the IEEE 754 34.5549999…)
        java.math.BigDecimal bdVal = new java.math.BigDecimal(Double.toString(v));
        String raw = df.format(bdVal);

        // --- Post-process: apply custom separators and digit family -------
        int digitBase = digitBase(zeroDigit);
        boolean customDigits = (digitBase != '0');
        boolean customDec    = (decimalSep != '.');
        boolean customGrp    = (groupSep   != ',');
        // Java always outputs 'E' for scientific; we always replace it
        StringBuilder sb = new StringBuilder(raw.length());
        for (int j = 0; j < raw.length(); j++) {
            char rc = raw.charAt(j);
            if (rc >= '0' && rc <= '9') {
                sb.append(customDigits ? (char)(rc - '0' + digitBase) : rc);
            } else if (rc == '.' && customDec) {
                sb.append(decimalSep);
            } else if (rc == ',' && customGrp) {
                sb.append(groupSep);
            } else if (rc == 'E') {
                sb.append(exponentSep);
            } else {
                sb.append(rc);
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // $formatInteger
    // =========================================================================

    static JsonNode fn_formatInteger(JsonNode number, JsonNode picture)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number) || JsonataRuntime.missing(picture))
            return JsonataRuntime.MISSING;
        long n = (long) JsonataRuntime.toNumber(number);
        String pic = JsonataRuntime.toText(picture);
        return NF.textNode(formatInteger(n, pic));
    }

    private static String formatInteger(long n, String pic) throws RuntimeEvaluationException {
        return switch (pic) {
            case "w"  -> toWords(n, false, false);
            case "W"  -> toWords(n, false, false).toUpperCase();
            case "Ww" -> titleCase(toWords(n, false, false));
            case "I"  -> toRoman(n).toUpperCase();
            case "i"  -> toRoman(n).toLowerCase();
            case "A"  -> toAlpha(n, true);
            case "a"  -> toAlpha(n, false);
            default   -> formatIntegerDecimal(n, pic);
        };
    }

    /** Format an integer using a decimal-picture pattern (e.g. {@code #,##0}). */
    private static String formatIntegerDecimal(long n, String pic) throws RuntimeEvaluationException {
        // Build a DecimalFormat pattern from the picture (integer-only)
        // Detect grouping separator (',') and digit placeholders
        StringBuilder javaPat = new StringBuilder();
        for (char c : pic.toCharArray()) {
            if (c == '#' || c == '0' || c == ',') javaPat.append(c);
            // ignore other chars in the pattern for now
        }
        if (javaPat.length() == 0) javaPat.append("0");
        DecimalFormatSymbols dfs = new DecimalFormatSymbols(Locale.ROOT);
        DecimalFormat df = new DecimalFormat(javaPat.toString(), dfs);
        df.setRoundingMode(RoundingMode.HALF_EVEN);
        return df.format(n);
    }

    // =========================================================================
    // $parseInteger
    // =========================================================================

    static JsonNode fn_parseInteger(JsonNode string, JsonNode picture)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(string) || JsonataRuntime.missing(picture))
            return JsonataRuntime.MISSING;
        String s   = JsonataRuntime.toText(string);
        String pic = JsonataRuntime.toText(picture);
        return JsonataRuntime.numNode(parseInteger(s, pic));
    }

    private static long parseInteger(String s, String pic) throws RuntimeEvaluationException {
        return switch (pic) {
            case "w", "W", "Ww" -> parseWords(s);
            case "I", "i"       -> parseRoman(s);
            case "A", "a"       -> parseAlpha(s);
            default             -> parseIntegerDecimal(s, pic);
        };
    }

    /** Strip grouping separators from {@code s} and parse as a long. */
    private static long parseIntegerDecimal(String s, String pic) throws RuntimeEvaluationException {
        // Determine grouping separator used in the picture (default ',')
        char grpSep = ',';
        for (char c : pic.toCharArray()) {
            if (c != '#' && c != '0') { grpSep = c; break; }
        }
        String stripped = s.replace(String.valueOf(grpSep), "");
        try {
            return Long.parseLong(stripped.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeEvaluationException(
                    "$parseInteger: cannot parse \"" + s + "\" with picture \"" + pic + "\"");
        }
    }

    // =========================================================================
    // English number words
    // =========================================================================

    private static final String[] ONES = {
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    };
    private static final String[] TENS = {
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    };
    private static final long[]   MAGNITUDES = { 1_000_000_000L, 1_000_000L, 1_000L };
    private static final String[] MAG_WORDS  = { "billion", "million", "thousand" };

    private static String toWords(long n, boolean unused1, boolean unused2)
            throws RuntimeEvaluationException {
        if (n == 0) return "zero";
        if (n < 0)  return "minus " + toWords(-n, false, false);
        return wordsBelow(n);
    }

    private static String wordsBelow(long n) {
        if (n == 0) return "";
        if (n < 20) return ONES[(int) n];
        if (n < 100) {
            String t = TENS[(int) (n / 10)];
            return n % 10 == 0 ? t : t + "-" + ONES[(int) (n % 10)];
        }
        if (n < 1000) {
            String h = ONES[(int) (n / 100)] + " hundred";
            long rest = n % 100;
            return rest == 0 ? h : h + " and " + wordsBelow(rest);
        }
        for (int m = 0; m < MAGNITUDES.length; m++) {
            if (n >= MAGNITUDES[m]) {
                long hi   = n / MAGNITUDES[m];
                long rest = n % MAGNITUDES[m];
                String s  = wordsBelow(hi) + " " + MAG_WORDS[m];
                return rest == 0 ? s : s + ", " + wordsBelow(rest);
            }
        }
        return String.valueOf(n); // fallback (> billions)
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : s.toCharArray()) {
            sb.append(cap ? Character.toUpperCase(c) : c);
            cap = (c == ' ');
        }
        return sb.toString();
    }

    // =========================================================================
    // Parse English words → long
    // =========================================================================

    private static final Map<String, Long> WORD_VALUES;
    static {
        Map<String, Long> m = new HashMap<>();
        String[] o = {"zero","one","two","three","four","five","six","seven","eight","nine",
                       "ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen",
                       "seventeen","eighteen","nineteen"};
        for (int j = 0; j < o.length; j++) m.put(o[j], (long) j);
        String[] t = {"twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"};
        for (int j = 0; j < t.length; j++) m.put(t[j], (long) (j + 2) * 10);
        m.put("hundred",   100L);
        m.put("thousand",  1_000L);
        m.put("million",   1_000_000L);
        m.put("billion",   1_000_000_000L);
        m.put("minus", null); // handled specially
        WORD_VALUES = Collections.unmodifiableMap(m);
    }

    private static long parseWords(String s) throws RuntimeEvaluationException {
        s = s.toLowerCase().replaceAll("[,\\-]", " ").trim();
        String[] tokens = s.split("\\s+");

        boolean negative = false;
        long total = 0;
        long current = 0;

        for (String tok : tokens) {
            if (tok.isEmpty() || tok.equals("and")) continue;
            if (tok.equals("minus")) { negative = true; continue; }

            if (!WORD_VALUES.containsKey(tok))
                throw new RuntimeEvaluationException(
                        "$parseInteger: unrecognised word token \"" + tok + "\"");
            long val = WORD_VALUES.get(tok);

            if (val == 100L) {
                // e.g. "three hundred" → current *= 100
                current = (current == 0 ? 1 : current) * 100;
            } else if (val >= 1000L) {
                // e.g. "thousand" → flush current into total
                current = (current == 0 ? 1 : current) * val;
                total += current;
                current = 0;
            } else {
                current += val;
            }
        }
        long result = total + current;
        return negative ? -result : result;
    }

    // =========================================================================
    // Roman numerals
    // =========================================================================

    private static final int[]    ROMAN_VALS  = {1000,900,500,400,100,90,50,40,10,9,5,4,1};
    private static final String[] ROMAN_SYMS  = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};

    private static String toRoman(long n) throws RuntimeEvaluationException {
        if (n <= 0 || n > 3_999_999)
            throw new RuntimeEvaluationException(
                    "$formatInteger: Roman numerals are only supported for 1–3,999,999");
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < ROMAN_VALS.length; k++) {
            while (n >= ROMAN_VALS[k]) { sb.append(ROMAN_SYMS[k]); n -= ROMAN_VALS[k]; }
        }
        return sb.toString();
    }

    private static long parseRoman(String s) throws RuntimeEvaluationException {
        s = s.toUpperCase().trim();
        Map<Character, Integer> v = Map.of(
                'I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100, 'D', 500, 'M', 1000);
        long result = 0; int prev = 0;
        for (int k = s.length() - 1; k >= 0; k--) {
            int cv = v.getOrDefault(s.charAt(k), -1);
            if (cv < 0) throw new RuntimeEvaluationException(
                    "$parseInteger: invalid Roman numeral character '" + s.charAt(k) + "'");
            result += (cv < prev) ? -cv : cv;
            prev = cv;
        }
        return result;
    }

    // =========================================================================
    // Alphabetic (A, B … Z, AA, AB …)
    // =========================================================================

    private static String toAlpha(long n, boolean upper) throws RuntimeEvaluationException {
        if (n <= 0)
            throw new RuntimeEvaluationException(
                    "$formatInteger: alphabetic format requires a positive integer");
        char base = upper ? 'A' : 'a';
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            n--;                              // make 0-indexed
            sb.insert(0, (char) (base + n % 26));
            n /= 26;
        }
        return sb.toString();
    }

    private static long parseAlpha(String s) throws RuntimeEvaluationException {
        s = s.toUpperCase().trim();
        long result = 0;
        for (char c : s.toCharArray()) {
            if (c < 'A' || c > 'Z')
                throw new RuntimeEvaluationException(
                        "$parseInteger: invalid alphabetic character '" + c + "'");
            result = result * 26 + (c - 'A' + 1);
        }
        return result;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns true if {@code c} is a mandatory-digit placeholder in the picture
     *  (any character in the same Unicode decimal digit family as {@code zeroDigit}). */
    private static boolean isMandatoryDigit(char c, char zeroDigit) {
        int base = digitBase(zeroDigit);
        return c >= base && c <= base + 9;
    }

    /**
     * Returns the codepoint of the "zero" character for the digit family that
     * {@code zeroDigit} belongs to.
     *
     * <p>For regular {@code '0'} (U+0030) the family base is {@code '0'} (value 48).
     * For ① (U+2460, numeric value 1) the family base is U+2460 − 1 = U+245F.
     * Digit {@code d} then maps to {@code (char)(d + base)}.
     */
    private static int digitBase(char zeroDigit) {
        int v = Character.getNumericValue(zeroDigit);
        if (v < 0 || v > 9) return zeroDigit; // treat zeroDigit itself as zero
        return zeroDigit - v;
    }

    /** Appends {@code s} to {@code sb}, wrapping in DecimalFormat single-quote escaping. */
    private static void appendQuoted(StringBuilder sb, String s) {
        if (s.isEmpty()) return;
        sb.append('\'').append(s.replace("'", "''")).append('\'');
    }

    private static char optChar(JsonNode opts, String key, char def) {
        if (opts == null || opts.isMissingNode() || !opts.isObject()) return def;
        JsonNode v = opts.get(key);
        if (v == null || !v.isTextual() || v.textValue().isEmpty()) return def;
        return v.textValue().charAt(0);
    }

    private static String optStr(JsonNode opts, String key, String def) {
        if (opts == null || opts.isMissingNode() || !opts.isObject()) return def;
        JsonNode v = opts.get(key);
        if (v == null || !v.isTextual()) return def;
        return v.textValue();
    }
}
