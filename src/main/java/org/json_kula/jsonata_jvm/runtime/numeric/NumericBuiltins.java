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

    /**
     * The reference's decimal grammar for {@code $number}, verbatim:
     * an optional sign, mandatory integer digits, an optional fraction that must have
     * digits, and an optional exponent.
     */
    private static final java.util.regex.Pattern DECIMAL_LITERAL =
            java.util.regex.Pattern.compile("-?[0-9]+(\\.[0-9]+)?([Ee][-+]?[0-9]+)?");

    /**
     * Radix-prefixed integer literals. The reference's own regex is unanchored in the
     * middle of its alternation, so it also matches embedded garbage like
     * {@code "x0o17y"} and returns NaN; this anchors the whole string and raises D3030
     * instead. Recorded as a deliberate divergence.
     */
    private static final java.util.regex.Pattern RADIX_LITERAL =
            java.util.regex.Pattern.compile("-?(0[xX][0-9A-Fa-f]+|0[oO][0-7]+|0[bB][01]+)");

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
            String s = arg.textValue();
            // The accepted grammar is narrower than Double.parseDouble's: no leading "+",
            // no bare ".5" or trailing "1.", and no surrounding whitespace. Reproducing
            // the reference's own regex is the point — parseDouble accepted all of those
            // and silently returned a number where the reference raises D3030.
            if (DECIMAL_LITERAL.matcher(s).matches()) {
                double d = Double.parseDouble(s);
                if (Double.isInfinite(d) || Double.isNaN(d)) {
                    throw new RuntimeEvaluationException("D3030",
                            "$number: value out of range for number type");
                }
                return JsonataRuntime.numNode(d);
            }
            String trimmed = s.trim();
            if (RADIX_LITERAL.matcher(trimmed).matches()) {
                boolean negative = trimmed.startsWith("-");
                String magnitude = negative ? trimmed.substring(1) : trimmed;
                int radix = switch (magnitude.charAt(1)) {
                    case 'x', 'X' -> 16;
                    case 'o', 'O' -> 8;
                    default -> 2;
                };
                double d = Long.parseLong(magnitude.substring(2), radix);
                return JsonataRuntime.numNode(negative ? -d : d);
            }
            throw new RuntimeEvaluationException("D3030",
                    "$number: unable to cast value to a number: " + s);
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
        // Clamped, not cast. `(int) 1e15` saturates to Integer.MAX_VALUE, and shifting a decimal
        // exponent by that produced the literal string "Infinity" for the next parse to choke on.
        // Beyond +-400 the arithmetic is already saturated -- 10^400 is infinite and 10^-400 is
        // zero -- so clamping there is exact as well as safe.
        double requested = JsonataRuntime.missing(precision) ? 0 : JsonataRuntime.toNumber(precision);
        int p = (int) Math.max(-400, Math.min(400, requested));
        double rounded = roundHalfEven(v, p);
        // The reference computes `value * 10^precision`, so a precision that overflows the
        // product yields undefined -- and it depends on the value, not on the precision alone:
        // $round(1, 308) is 1, $round(1e15, 308) is undefined.
        if (!Double.isFinite(rounded)) return JsonataRuntime.MISSING;
        return JsonataRuntime.numNode(rounded);
    }

    /**
     * Rounds half-to-even at {@code p} decimal places — {@code $round}'s algorithm,
     * shared with {@code $formatNumber} so the two can never round differently.
     *
     * <p>A port of the reference's own routine, including its fast path, because the
     * pairing of ceiling and tie window is what makes the fast path sound and the two
     * halves cannot be chosen independently. The previous version paired a 1e15 ceiling
     * with a 1e-9 window: {@code v * 10^p} is accurate to about half an ulp <em>of the
     * product</em>, and at 1e15 an ulp is 0.125 — eight orders of magnitude wider than
     * the window — so the fast path sailed past genuine ties and answered confidently
     * with the wrong value ({@code $round(-36435.03133177965, 10)} among them.)
     */
    public static double roundHalfEven(double v, int p) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;

        if (p >= 1 && p <= 15) {
            double scaled = v * POWERS_OF_TEN[p];
            if (scaled > -1e9 && scaled < 1e9) {
                double fraction = scaled - Math.floor(scaled);
                if (fraction > 0.500001 || fraction < 0.499999) {
                    double rounded = Math.round(scaled) / POWERS_OF_TEN[p];
                    return rounded == 0 ? 0 : rounded;      // JSON has no -0
                }
            }
        }

        double value = p != 0 ? shiftDecimalExponent(v, p) : v;
        // JavaScript's Math.round is floor(x + 0.5) — half toward +infinity — and the
        // half-to-even correction is applied afterwards, so both have to be reproduced.
        double result = Math.floor(value + 0.5);
        double diff = result - value;
        if (Math.abs(diff) == 0.5 && Math.abs(result % 2) == 1) {
            result = result - 1;
        }
        if (p != 0) result = shiftDecimalExponent(result, -p);
        return result == 0 ? 0 : result;
    }

    /**
     * Shifts {@code value}'s decimal exponent by {@code by} through the number's own
     * decimal string rather than by multiplying.
     *
     * <p>Multiplication introduces float noise the rounding then sees: {@code 8.835 * 100}
     * is {@code 883.4999999999999}, but {@code 8.835e2} is exactly {@code 883.5}, which is
     * what half-to-even has to be shown.
     */
    private static double shiftDecimalExponent(double value, int by) {
        // A shift can overflow to infinity, and "Infinity" is not a number literal any parser
        // will take back. Propagate it instead; fn_round turns a non-finite result into
        // undefined, which is what the reference's own overflow produces.
        if (!Double.isFinite(value)) return value;
        String s = JsonataRuntime.renderNumberRaw(value);
        int e = s.indexOf('e');
        if (e < 0) return Double.parseDouble(s + "e" + by);
        return Double.parseDouble(s.substring(0, e) + "e" + (Integer.parseInt(s.substring(e + 1)) + by));
    }

    private static final double[] POWERS_OF_TEN = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7,
        1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15
    };

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

        // The defaults, overridden entry-by-entry by the caller's options object. The
        // whole map is handed to the engine: fn:format-number is defined over these
        // properties rather than over a fixed set of separator characters.
        // The shared defaults are used as-is unless the call supplies an options object,
        // so the common call allocates no map at all.
        java.util.Map<String, String> properties = DecimalPicture.DEFAULTS;
        if (!JsonataRuntime.missing(options) && options.isObject() && !options.isEmpty()) {
            properties = new java.util.LinkedHashMap<>(DecimalPicture.DEFAULTS);
            java.util.Map<String, String> overridden = properties;
            options.properties().forEach(e -> overridden.put(e.getKey(), e.getValue().asText()));
        }

        return NF.textNode(DecimalPicture.format(v, pic, properties));
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
        // Math.floor, not a (long) truncation: the reference floors, so -12.6 formats as
        // -13. A cast truncates toward zero and gave -12.
        long n = (long) Math.floor(numDouble);
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
        // An input the picture cannot parse yields NaN, which is JSONata's "undefined".
        double parsed = IntegerPicture.parse(s, pic);
        return Double.isNaN(parsed) ? JsonataRuntime.MISSING : JsonataRuntime.numNode(parsed);
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
