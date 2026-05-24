package org.json_kula.jsonata_jvm.runtime.numeric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;
import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Numeric built-in functions for JSONata, delegated from {@link JsonataRuntime}.
 *
 * <p>Implements: {@code $number} (with radix-literal and NaN/Infinity validation),
 * {@code $round} (precision + banker's rounding), {@code $random},
 * {@code $formatBase}, {@code $formatNumber}, {@code $formatInteger},
 * {@code $parseInteger}.
 *
 * <p>Implementation details are split across package-private helpers:
 * <ul>
 *   <li>{@link DecimalPicture} — {@code $formatNumber} picture-string engine
 *   <li>{@link IntegerPicture} — {@code $formatInteger} / {@code $parseInteger}
 *   <li>{@link EnglishWords}   — English word-number conversion
 * </ul>
 *
 * @see <a href="../docs/numeric.md">docs/numeric.md</a>
 */
public final class NumericBuiltins {

    private NumericBuiltins() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    // =========================================================================
    // $number — with 0x / 0o / 0b radix-literal support; NaN guard
    // =========================================================================

    public static JsonNode fn_number(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (arg.isNumber()) {
            double d = arg.doubleValue();
            if (Double.isInfinite(d) || Double.isNaN(d))
                throw new RuntimeEvaluationException("D3030",
                        "$number: value out of range for number type");
            return arg;
        }
        if (arg.isBoolean()) return JsonataRuntime.numNode(arg.booleanValue() ? 1 : 0);
        // null, array, object, lambda, regex → T0410
        if (arg.isNull() || arg.isArray() || arg.isObject()
                || JsonataRuntime.isLambdaToken(arg) || JsonataRuntime.isRegexToken(arg))
            throw new RuntimeEvaluationException("T0410",
                    "$number: argument is not a valid value for $number");
        if (arg.isTextual()) {
            String s = arg.textValue().trim();
            try {
                double d;
                // Support leading minus before radix prefix (e.g. "-0x1A" → -26)
                boolean neg = s.startsWith("-");
                String abs = neg ? s.substring(1) : s;
                if (abs.startsWith("0x") || abs.startsWith("0X"))
                    d = (neg ? -1d : 1d) * Long.parseLong(abs.substring(2), 16);
                else if (abs.startsWith("0o") || abs.startsWith("0O"))
                    d = (neg ? -1d : 1d) * Long.parseLong(abs.substring(2), 8);
                else if (abs.startsWith("0b") || abs.startsWith("0B"))
                    d = (neg ? -1d : 1d) * Long.parseLong(abs.substring(2), 2);
                else
                    d = Double.parseDouble(s);
                if (Double.isInfinite(d) || Double.isNaN(d))
                    throw new RuntimeEvaluationException("D3030",
                            "$number: value out of range for number type");
                return JsonataRuntime.numNode(d);
            } catch (NumberFormatException e) {
                throw new RuntimeEvaluationException("D3030",
                        "$number: unable to cast value to a number: " + s);
            }
        }
        throw new RuntimeEvaluationException("D3030", "$number: unable to cast value to a number");
    }

    // =========================================================================
    // $round — precision + half-to-even (banker's rounding)
    // =========================================================================

    public static JsonNode fn_round(JsonNode number, JsonNode precision) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number)) return JsonataRuntime.MISSING;
        double v = JsonataRuntime.toNumber(number);
        if (Double.isNaN(v) || Double.isInfinite(v)) return NF.numberNode(v);
        int p = JsonataRuntime.missing(precision) ? 0 : (int) JsonataRuntime.toNumber(precision);
        BigDecimal bd = new BigDecimal(Double.toString(v)).setScale(p, RoundingMode.HALF_EVEN);
        return JsonataRuntime.numNode(bd.doubleValue());
    }

    // =========================================================================
    // $random — ThreadLocalRandom avoids contention under parallel evaluation
    // =========================================================================

    public static JsonNode fn_random() {
        return NF.numberNode(ThreadLocalRandom.current().nextDouble());
    }

    // =========================================================================
    // $formatBase
    // =========================================================================

    public static JsonNode fn_formatBase(JsonNode number, JsonNode radix) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number)) return JsonataRuntime.MISSING;
        long n = Math.round(JsonataRuntime.toNumber(number));
        int r = JsonataRuntime.missing(radix) ? 10 : (int) JsonataRuntime.toNumber(radix);
        if (r < 2 || r > 36)
            throw new RuntimeEvaluationException("D3100",
                    "$formatBase: radix must be between 2 and 36");
        return NF.textNode(Long.toString(n, r));
    }

    // =========================================================================
    // $formatNumber
    // =========================================================================

    public static JsonNode fn_formatNumber(JsonNode number, JsonNode picture, JsonNode options)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number) || JsonataRuntime.missing(picture))
            return JsonataRuntime.MISSING;

        double v = JsonataRuntime.toNumber(number);
        String pic = JsonataRuntime.toText(picture);

        char decimalSep  = optChar(options, "decimal-separator",  '.');
        char groupSep    = optChar(options, "grouping-separator", ',');
        char exponentSep = optChar(options, "exponent-separator", 'e');
        String percent   = optStr(options,  "percent",            "%");
        String perMille  = optStr(options,  "per-mille",          "‰");
        char zeroDigit   = optChar(options, "zero-digit",         '0');
        char digitChar   = optChar(options, "digit",              '#');
        char patternSep  = optChar(options, "pattern-separator",  ';');
        String minusSign = optStr(options,  "minus-sign",         "-");

        int sepIdx  = pic.indexOf(patternSep);
        String posPic = sepIdx >= 0 ? pic.substring(0, sepIdx) : pic;
        String negPic = sepIdx >= 0 ? pic.substring(sepIdx + 1) : null;
        if (negPic != null && negPic.indexOf(patternSep) >= 0)
            throw new RuntimeEvaluationException("D3080",
                    "$formatNumber: the picture string must not contain more than one instance of the pattern separator");

        boolean hasPercent  = posPic.contains(percent);
        boolean hasPerMille = posPic.contains(perMille);
        boolean isNeg = v < 0;
        double work = hasPercent  ? Math.abs(v) * 100
                    : hasPerMille ? Math.abs(v) * 1000
                    : Math.abs(v);

        String activePic = (isNeg && negPic != null) ? negPic : posPic;
        String result = DecimalPicture.format(work, activePic,
                decimalSep, groupSep, exponentSep,
                percent, perMille, zeroDigit, digitChar);

        if (isNeg && negPic == null) result = minusSign + result;
        return NF.textNode(result);
    }

    // =========================================================================
    // $formatInteger
    // =========================================================================

    public static JsonNode fn_formatInteger(JsonNode number, JsonNode picture)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(number) || JsonataRuntime.missing(picture))
            return JsonataRuntime.MISSING;
        double numDouble = JsonataRuntime.toNumber(number);
        if (Double.isInfinite(numDouble) || Double.isNaN(numDouble))
            throw new RuntimeEvaluationException(null,
                    "$formatInteger: value is not representable as an integer: " + numDouble);
        String pic = JsonataRuntime.toText(picture);
        // Numbers beyond long range are only representable via word pictures.
        if (numDouble > Long.MAX_VALUE || numDouble < Long.MIN_VALUE)
            return NF.textNode(IntegerPicture.formatLarge(numDouble, pic));
        long n = (long) numDouble;
        return NF.textNode(IntegerPicture.format(n, pic));
    }

    // =========================================================================
    // $parseInteger
    // =========================================================================

    public static JsonNode fn_parseInteger(JsonNode string, JsonNode picture)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(string) || JsonataRuntime.missing(picture))
            return JsonataRuntime.MISSING;
        String s   = JsonataRuntime.toText(string);
        String pic = JsonataRuntime.toText(picture);
        return JsonataRuntime.numNode(IntegerPicture.parse(s, pic));
    }

    // =========================================================================
    // Option-extraction helpers (for $formatNumber options object)
    // =========================================================================

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
