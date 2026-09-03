package org.json_kula.jsonata_jvm.runtime.numeric;

import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code $formatNumber} — a port of XPath 3.1 F&amp;O {@code fn:format-number}
 * (§4.7.3 validate, §4.7.4 analyse, §4.7.5 bullets 2-14).
 *
 * <p>This replaced a {@code java.text.DecimalFormat}-shaped pipeline written against the
 * XSLT 2.0 §16 picture <em>grammar</em>. That pipeline's visible symptoms — a trailing
 * decimal separator that was never suppressed, no leading-zero elision, an exponent search
 * that scaled to the wrong mantissa — were not independently patchable rules but one
 * structural mismatch, so the algorithm was replaced rather than repaired.
 *
 * <p>The algorithm is defined over ECMAScript numeric primitives and its output depends on
 * them, so {@link #toFixed} and {@link #roundHalfEven} reproduce those rather than
 * substituting Java's near-equivalents. {@code toFixed}'s hand-off to the plain number
 * rendering at 1e21 is the reason {@code $formatNumber(1e21, "0.00")} is
 * {@code "1e+21.00"} — an artefact of the reference operating on the string form, and one
 * this port has to reproduce to agree with it.
 */
public final class DecimalPicture {

    private DecimalPicture() {}

    // Property keys, in the spelling fn:format-number's options map uses.
    private static final String DECIMAL_SEPARATOR = "decimal-separator";
    private static final String GROUPING_SEPARATOR = "grouping-separator";
    private static final String EXPONENT_SEPARATOR = "exponent-separator";
    private static final String MINUS_SIGN = "minus-sign";
    private static final String PERCENT = "percent";
    private static final String PER_MILLE = "per-mille";
    private static final String ZERO_DIGIT = "zero-digit";
    private static final String DIGIT = "digit";
    private static final String PATTERN_SEPARATOR = "pattern-separator";

    /**
     * fn:format-number's default property values (F&amp;O 4.7.2).
     *
     * <p>Shared and immutable, so a call with no options object allocates no map — and so
     * the analysis cache can recognise the default properties by identity and key on the
     * picture text alone.
     */
    public static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("decimal-separator", "."),
            Map.entry("grouping-separator", ","),
            Map.entry("exponent-separator", "e"),
            Map.entry("infinity", "Infinity"),
            Map.entry("minus-sign", "-"),
            Map.entry("NaN", "NaN"),
            Map.entry("percent", "%"),
            Map.entry("per-mille", "‰"),
            Map.entry("zero-digit", "0"),
            Map.entry("digit", "#"),
            Map.entry("pattern-separator", ";"));

    /** The picture split into prefix / active part / suffix, per §4.7.3. */
    private record Parts(String prefix, String suffix, String activePart, String mantissaPart,
                         String exponentPart, String integerPart, String fractionalPart,
                         String subpicture) {}

    /** An analysed sub-picture, per §4.7.4. */
    private record Analysis(List<Integer> integerPartGroupingPositions, int regularGrouping,
                            int minimumIntegerPartSize, int scalingFactor, String prefix,
                            List<Integer> fractionalPartGroupingPositions,
                            int minimumFractionalPartSize, int maximumFractionalPartSize,
                            int minimumExponentSize, String suffix, String picture) {

        Analysis withPrefix(String newPrefix) {
            return new Analysis(integerPartGroupingPositions, regularGrouping,
                    minimumIntegerPartSize, scalingFactor, newPrefix,
                    fractionalPartGroupingPositions, minimumFractionalPartSize,
                    maximumFractionalPartSize, minimumExponentSize, suffix, picture);
        }
    }

    /**
     * Formats {@code value} against {@code picture}.
     *
     * @param properties the effective property map: defaults already overridden by the
     *                   caller's {@code options} argument
     */
    public static String format(double value, String picture, Map<String, String> properties)
            throws RuntimeEvaluationException {

        String decimalSeparator = properties.get(DECIMAL_SEPARATOR);
        String groupingSeparator = properties.get(GROUPING_SEPARATOR);
        String exponentSeparator = properties.get(EXPONENT_SEPARATOR);
        String minusSign = properties.get(MINUS_SIGN);
        String percent = properties.get(PERCENT);
        String perMille = properties.get(PER_MILLE);
        String zeroDigit = properties.get(ZERO_DIGIT);
        String digit = properties.get(DIGIT);
        String patternSeparator = properties.get(PATTERN_SEPARATOR);

        int zeroCharCode = zeroDigit.codePointAt(0);
        List<String> decimalDigitFamily = new ArrayList<>(10);
        for (int i = zeroCharCode; i < zeroCharCode + 10; i++) {
            decimalDigitFamily.add(new String(Character.toChars(i)));
        }

        List<String> activeChars = new ArrayList<>(decimalDigitFamily);
        activeChars.add(decimalSeparator);
        activeChars.add(exponentSeparator);
        activeChars.add(groupingSeparator);
        activeChars.add(digit);
        activeChars.add(patternSeparator);

        List<String> subPictures = splitLiteral(picture, patternSeparator);
        if (subPictures.size() > 2) {
            throw new RuntimeEvaluationException("D3080",
                    "$formatNumber: the picture string must not contain more than one pattern separator");
        }

        List<Analysis> variables = new ArrayList<>(2);
        for (String subPicture : subPictures) {
            variables.add(analysisOf(subPicture, decimalDigitFamily, activeChars, properties));
        }
        if (variables.size() == 1) {
            // With no negative sub-picture, the negative form is the positive one with the
            // minus sign pushed into its prefix.
            variables.add(variables.get(0).withPrefix(minusSign + variables.get(0).prefix()));
        }

        // Bullet 2: choose the sub-picture by sign.
        Analysis pic = value >= 0 ? variables.get(0) : variables.get(1);

        // Bullet 3: percent / per-mille scaling. Scanned from the CHOSEN sub-picture, not
        // from the positive one.
        double adjusted;
        if (pic.picture().contains(percent)) {
            adjusted = value * 100;
        } else if (pic.picture().contains(perMille)) {
            adjusted = value * 1000;
        } else {
            adjusted = value;
        }

        // Bullet 5: split into mantissa and exponent, if the picture has an exponent part.
        double mantissa;
        Integer exponent = null;
        if (pic.minimumExponentSize() == 0) {
            mantissa = adjusted;
        } else {
            double maxMantissa = Math.pow(10, pic.scalingFactor());
            double minMantissa = Math.pow(10, pic.scalingFactor() - 1);
            mantissa = adjusted;
            int e = 0;
            // Zero has exponent zero by definition; without this the loops never end.
            if (mantissa != 0) {
                while (Math.abs(mantissa) < minMantissa) { mantissa *= 10; e -= 1; }
                while (Math.abs(mantissa) > maxMantissa) { mantissa /= 10; e += 1; }
            }
            exponent = e;
        }

        // Bullet 6: round to the maximum fractional size, sharing $round's own algorithm
        // so the two can never round differently.
        double rounded = NumericBuiltins.roundHalfEven(mantissa, pic.maximumFractionalPartSize());

        // Bullet 7: render, then strip leading and trailing zero digits.
        String stringValue = makeString(rounded, pic.maximumFractionalPartSize(),
                decimalDigitFamily, zeroDigit);
        int decimalPos = stringValue.indexOf('.');
        if (decimalPos == -1) {
            stringValue = stringValue + decimalSeparator;
        } else {
            stringValue = stringValue.substring(0, decimalPos) + decimalSeparator
                    + stringValue.substring(decimalPos + 1);
        }
        while (stringValue.startsWith(zeroDigit)) {
            stringValue = stringValue.substring(zeroDigit.length());
        }
        while (stringValue.endsWith(zeroDigit)) {
            stringValue = stringValue.substring(0, stringValue.length() - zeroDigit.length());
        }

        // Bullets 8 & 9: pad to the minimum integer and fractional sizes.
        decimalPos = stringValue.indexOf(decimalSeparator);
        int padLeft = pic.minimumIntegerPartSize() - decimalPos;
        int padRight = pic.minimumFractionalPartSize() - (stringValue.length() - decimalPos - 1);
        if (padLeft > 0) stringValue = zeroDigit.repeat(padLeft) + stringValue;
        if (padRight > 0) stringValue = stringValue + zeroDigit.repeat(padRight);
        decimalPos = stringValue.indexOf(decimalSeparator);

        // Bullet 10: integer-part grouping separators.
        if (pic.regularGrouping() > 0) {
            int groupCount = (decimalPos - 1) / pic.regularGrouping();
            for (int group = 1; group <= groupCount; group++) {
                int at = decimalPos - group * pic.regularGrouping();
                stringValue = stringValue.substring(0, at) + groupingSeparator
                        + stringValue.substring(at);
            }
        } else {
            // Irregular positions can fall outside the rendered number, and the reference
            // splices with JavaScript's slice(), which counts a negative index from the
            // end rather than erroring. That is observable — $formatNumber(1234.5678,
            // "#,##,##0") really is "12,35," in the reference — so jsSlice reproduces it
            // instead of clamping, which would silently disagree on every short value.
            for (int pos : pic.integerPartGroupingPositions()) {
                int at = decimalPos - pos;
                stringValue = jsSlice(stringValue, 0, at) + groupingSeparator
                        + jsSlice(stringValue, at);
                decimalPos++;
            }
        }

        // Bullet 11: fractional-part grouping separators.
        decimalPos = stringValue.indexOf(decimalSeparator);
        for (int pos : pic.fractionalPartGroupingPositions()) {
            int at = pos + decimalPos + 1;
            stringValue = jsSlice(stringValue, 0, at) + groupingSeparator
                    + jsSlice(stringValue, at);
        }

        // Bullet 12: drop a decimal separator the picture did not ask for, or one left
        // dangling at the end.
        decimalPos = stringValue.indexOf(decimalSeparator);
        if (!pic.picture().contains(decimalSeparator) || decimalPos == stringValue.length() - 1) {
            stringValue = stringValue.substring(0, stringValue.length() - 1);
        }

        // Bullet 13: the exponent.
        if (exponent != null) {
            String stringExponent = makeString(exponent, 0, decimalDigitFamily, zeroDigit);
            int expPadLeft = pic.minimumExponentSize() - stringExponent.length();
            if (expPadLeft > 0) stringExponent = zeroDigit.repeat(expPadLeft) + stringExponent;
            stringValue = stringValue + exponentSeparator
                    + (exponent < 0 ? minusSign : "") + stringExponent;
        }

        // Bullet 14.
        return pic.prefix() + stringValue + pic.suffix();
    }

    // =========================================================================
    // §4.7.3 — splitting and validation
    // =========================================================================

    private static Parts splitParts(String subpicture, List<String> activeChars,
                                    String decimalSeparator, String exponentSeparator) {
        String prefix = "";
        for (int i = 0; i < subpicture.length(); i++) {
            String ch = String.valueOf(subpicture.charAt(i));
            if (activeChars.contains(ch) && !ch.equals(exponentSeparator)) {
                prefix = subpicture.substring(0, i);
                break;
            }
        }
        String suffix = "";
        for (int i = subpicture.length() - 1; i >= 0; i--) {
            String ch = String.valueOf(subpicture.charAt(i));
            if (activeChars.contains(ch) && !ch.equals(exponentSeparator)) {
                suffix = subpicture.substring(i + 1);
                break;
            }
        }
        String activePart = subpicture.substring(prefix.length(), subpicture.length() - suffix.length());

        String mantissaPart;
        String exponentPart;
        int exponentPosition = subpicture.indexOf(exponentSeparator, prefix.length());
        if (exponentPosition == -1 || exponentPosition > subpicture.length() - suffix.length()) {
            mantissaPart = activePart;
            exponentPart = null;
        } else {
            mantissaPart = activePart.substring(0, exponentPosition);
            exponentPart = activePart.substring(exponentPosition + 1);
        }

        String integerPart;
        String fractionalPart;
        int decimalPosition = mantissaPart.indexOf(decimalSeparator);
        if (decimalPosition == -1) {
            integerPart = mantissaPart;
            fractionalPart = suffix;
        } else {
            integerPart = mantissaPart.substring(0, decimalPosition);
            fractionalPart = mantissaPart.substring(decimalPosition + 1);
        }

        return new Parts(prefix, suffix, activePart, mantissaPart, exponentPart,
                integerPart, fractionalPart, subpicture);
    }

    private static void validate(Parts parts, List<String> family, List<String> activeChars,
                                 Map<String, String> properties) throws RuntimeEvaluationException {
        String subpicture = parts.subpicture();
        String decimalSeparator = properties.get(DECIMAL_SEPARATOR);
        String groupingSeparator = properties.get(GROUPING_SEPARATOR);
        String percent = properties.get(PERCENT);
        String perMille = properties.get(PER_MILLE);
        String digit = properties.get(DIGIT);

        String error = null;
        int decimalPos = subpicture.indexOf(decimalSeparator);
        if (decimalPos != subpicture.lastIndexOf(decimalSeparator)) error = "D3081";
        if (subpicture.indexOf(percent) != subpicture.lastIndexOf(percent)) error = "D3082";
        if (subpicture.indexOf(perMille) != subpicture.lastIndexOf(perMille)) error = "D3083";
        if (subpicture.contains(percent) && subpicture.contains(perMille)) error = "D3084";

        boolean valid = false;
        for (int i = 0; i < parts.mantissaPart().length(); i++) {
            String ch = String.valueOf(parts.mantissaPart().charAt(i));
            if (family.contains(ch) || ch.equals(digit)) { valid = true; break; }
        }
        if (!valid) error = "D3085";

        for (int i = 0; i < parts.activePart().length(); i++) {
            if (!activeChars.contains(String.valueOf(parts.activePart().charAt(i)))) {
                error = "D3086";
                break;
            }
        }

        if (decimalPos != -1) {
            if (charAtOrEmpty(subpicture, decimalPos - 1).equals(groupingSeparator)
                    || charAtOrEmpty(subpicture, decimalPos + 1).equals(groupingSeparator)) {
                error = "D3087";
            }
        } else if (parts.integerPart().endsWith(groupingSeparator)
                && !parts.integerPart().isEmpty()) {
            error = "D3088";
        }
        if (subpicture.contains(groupingSeparator + groupingSeparator)) error = "D3089";

        int optionalDigitPos = parts.integerPart().indexOf(digit);
        if (optionalDigitPos != -1
                && containsAny(parts.integerPart().substring(0, optionalDigitPos), family)) {
            error = "D3090";
        }
        optionalDigitPos = parts.fractionalPart().lastIndexOf(digit);
        if (optionalDigitPos != -1
                && containsAny(parts.fractionalPart().substring(optionalDigitPos), family)) {
            error = "D3091";
        }

        boolean exponentExists = parts.exponentPart() != null;
        if (exponentExists && !parts.exponentPart().isEmpty()
                && (subpicture.contains(percent) || subpicture.contains(perMille))) {
            error = "D3092";
        }
        if (exponentExists && (parts.exponentPart().isEmpty()
                || !allIn(parts.exponentPart(), family))) {
            error = "D3093";
        }

        if (error != null) {
            throw new RuntimeEvaluationException(error,
                    "$formatNumber: invalid picture string '" + subpicture + "'");
        }
    }

    // =========================================================================
    // §4.7.4 — analysis
    // =========================================================================

    private static Analysis analyse(Parts parts, List<String> family,
                                    String groupingSeparator, String digit) {
        List<Integer> integerPartGroupingPositions =
                groupingPositions(parts.integerPart(), parts.integerPart(), false,
                        family, groupingSeparator, digit);
        int regularGrouping = regular(integerPartGroupingPositions);
        List<Integer> fractionalPartGroupingPositions =
                groupingPositions(parts.fractionalPart(), parts.integerPart(), true,
                        family, groupingSeparator, digit);

        int minimumIntegerPartSize = countIn(parts.integerPart(), family);
        int scalingFactor = minimumIntegerPartSize;

        int minimumFractionalPartSize = countIn(parts.fractionalPart(), family);
        int maximumFractionalPartSize = minimumFractionalPartSize
                + countChar(parts.fractionalPart(), digit);

        boolean exponentPresent = parts.exponentPart() != null;
        if (minimumIntegerPartSize == 0 && maximumFractionalPartSize == 0) {
            if (exponentPresent) {
                minimumFractionalPartSize = 1;
                maximumFractionalPartSize = 1;
            } else {
                minimumIntegerPartSize = 1;
            }
        }
        if (exponentPresent && minimumIntegerPartSize == 0 && parts.integerPart().contains(digit)) {
            minimumIntegerPartSize = 1;
        }
        if (minimumIntegerPartSize == 0 && minimumFractionalPartSize == 0) {
            minimumFractionalPartSize = 1;
        }
        int minimumExponentSize = exponentPresent ? countIn(parts.exponentPart(), family) : 0;

        // Copied to immutable lists: the analysis is cached and shared across calls, so a
        // later mutation of these would corrupt every subsequent use of the same picture.
        return new Analysis(List.copyOf(integerPartGroupingPositions), regularGrouping,
                minimumIntegerPartSize, scalingFactor, parts.prefix(),
                List.copyOf(fractionalPartGroupingPositions), minimumFractionalPartSize,
                maximumFractionalPartSize, minimumExponentSize, parts.suffix(),
                parts.subpicture());
    }

    /**
     * Grouping-separator positions, counted in digit positions.
     *
     * <p>The scan for the <em>next</em> separator runs over the integer part even when
     * measuring the fractional one — the reference does exactly that, and reproducing it
     * is the difference between agreeing and disagreeing on a fractional grouping picture.
     */
    private static List<Integer> groupingPositions(String part, String integerPart, boolean toLeft,
                                                   List<String> family, String groupingSeparator,
                                                   String digit) {
        List<Integer> positions = new ArrayList<>();
        int groupingPosition = part.indexOf(groupingSeparator);
        while (groupingPosition != -1) {
            String measured = toLeft
                    ? part.substring(0, groupingPosition)
                    : part.substring(groupingPosition);
            int count = 0;
            for (int i = 0; i < measured.length(); i++) {
                String ch = String.valueOf(measured.charAt(i));
                if (family.contains(ch) || ch.equals(digit)) count++;
            }
            positions.add(count);
            groupingPosition = integerPart.indexOf(groupingSeparator, groupingPosition + 1);
        }
        return positions;
    }

    private static int regular(List<Integer> indexes) {
        if (indexes.isEmpty()) return 0;
        int factor = indexes.get(0);
        for (int index : indexes) factor = gcd(factor, index);
        if (factor == 0) return 0;
        for (int index = 1; index <= indexes.size(); index++) {
            if (!indexes.contains(index * factor)) return 0;
        }
        return factor;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    // =========================================================================
    // ECMAScript numeric primitives the algorithm is defined over
    // =========================================================================

    /** {@code Math.abs(val).toFixed(dp)}, then mapped into the picture's digit family. */
    private static String makeString(double value, int decimals,
                                     List<String> family, String zeroDigit) {
        String s = toFixed(Math.abs(value), decimals);
        if (!zeroDigit.equals("0")) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                sb.append(c >= '0' && c <= '9' ? family.get(c - '0') : String.valueOf(c));
            }
            return sb.toString();
        }
        return s;
    }

    /**
     * ECMAScript {@code Number.prototype.toFixed} for a non-negative value.
     *
     * <p>Rounds the double's <em>exact</em> value half-away-from-zero — not the shortest
     * decimal's value — which is why {@code BigDecimal(double)} is used rather than
     * {@code BigDecimal.valueOf}. At or above 1e21 the spec hands off to the ordinary
     * number rendering instead of producing fixed-point digits.
     */
    static String toFixed(double value, int decimals) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return "Infinity";
        if (value >= 1e21) return org.json_kula.jsonata_jvm.runtime.JsonataRuntime.renderNumber(value);
        return new java.math.BigDecimal(value)
                .setScale(decimals, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    /** Splits on a literal (non-regex) separator, keeping empty trailing fields. */
    private static List<String> splitLiteral(String s, String separator) {
        List<String> out = new ArrayList<>();
        int start = 0, at;
        while ((at = s.indexOf(separator, start)) >= 0) {
            out.add(s.substring(start, at));
            start = at + separator.length();
        }
        out.add(s.substring(start));
        return out;
    }

    private static String charAtOrEmpty(String s, int index) {
        return index < 0 || index >= s.length() ? "" : String.valueOf(s.charAt(index));
    }

    private static boolean containsAny(String s, List<String> family) {
        for (int i = 0; i < s.length(); i++) {
            if (family.contains(String.valueOf(s.charAt(i)))) return true;
        }
        return false;
    }

    private static boolean allIn(String s, List<String> family) {
        for (int i = 0; i < s.length(); i++) {
            if (!family.contains(String.valueOf(s.charAt(i)))) return false;
        }
        return true;
    }

    private static int countIn(String s, List<String> family) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (family.contains(String.valueOf(s.charAt(i)))) count++;
        }
        return count;
    }

    private static int countChar(String s, String ch) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (String.valueOf(s.charAt(i)).equals(ch)) count++;
        }
        return count;
    }

    /** JavaScript {@code String.prototype.slice(start)} — a negative start counts back. */
    private static String jsSlice(String s, int start) {
        if (start < 0) start = Math.max(s.length() + start, 0);
        if (start >= s.length()) return "";
        return s.substring(start);
    }

    /** JavaScript {@code String.prototype.slice(start, end)}. */
    private static String jsSlice(String s, int start, int end) {
        if (start < 0) start = Math.max(s.length() + start, 0);
        if (end < 0) end = Math.max(s.length() + end, 0);
        start = Math.min(start, s.length());
        end = Math.min(end, s.length());
        if (end <= start) return "";
        return s.substring(start, end);
    }

    /**
     * Analysed sub-pictures, memoized. The analysis depends on the property map as well
     * as the picture text — a different grouping separator gives different positions — so
     * the key carries both; with the default properties that is just the picture with a
     * fixed suffix.
     */
    private static final org.json_kula.jsonata_jvm.runtime.PictureCache<Analysis> CACHE =
            new org.json_kula.jsonata_jvm.runtime.PictureCache<>(512, DecimalPicture::analyseKey);

    private static Analysis analysisOf(String subPicture, List<String> family,
                                       List<String> activeChars, Map<String, String> properties)
            throws RuntimeEvaluationException {
        String key = properties == DEFAULTS ? subPicture
                : subPicture + ' ' + properties.get(DECIMAL_SEPARATOR)
                + ' ' + properties.get(GROUPING_SEPARATOR)
                + ' ' + properties.get(EXPONENT_SEPARATOR)
                + ' ' + properties.get(DIGIT)
                + ' ' + properties.get(ZERO_DIGIT)
                + ' ' + properties.get(PERCENT)
                + ' ' + properties.get(PER_MILLE);
        PENDING.set(new Pending(subPicture, family, activeChars, properties));
        try {
            return CACHE.get(key);
        } catch (org.json_kula.jsonata_jvm.runtime.PictureCache.AnalysisFailure e) {
            throw e.unwrap();
        } finally {
            PENDING.remove();
        }
    }

    /**
     * The inputs the cache loader needs beyond the key. A ThreadLocal rather than a
     * composite key object because the key must stay a plain string for the cache, and
     * the hand-off is strictly within one {@code analysisOf} call.
     */
    private record Pending(String subPicture, List<String> family,
                           List<String> activeChars, Map<String, String> properties) {}

    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    private static Analysis analyseKey(String ignoredKey) {
        Pending pending = PENDING.get();
        try {
            Parts parts = splitParts(pending.subPicture(), pending.activeChars(),
                    pending.properties().get(DECIMAL_SEPARATOR),
                    pending.properties().get(EXPONENT_SEPARATOR));
            validate(parts, pending.family(), pending.activeChars(), pending.properties());
            return analyse(parts, pending.family(),
                    pending.properties().get(GROUPING_SEPARATOR),
                    pending.properties().get(DIGIT));
        } catch (RuntimeEvaluationException e) {
            throw new org.json_kula.jsonata_jvm.runtime.PictureCache.AnalysisFailure(e);
        }
    }
}
