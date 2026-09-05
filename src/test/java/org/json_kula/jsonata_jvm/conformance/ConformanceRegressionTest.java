package org.json_kula.jsonata_jvm.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance cases found by differential testing against the reference interpreter.
 *
 * <p>Every expectation here was produced by running the expression through {@code jsonata}
 * 2.2.2 and cross-checked against the sibling jsonata2js port, which agreed with the
 * reference on all 763 divergences a 3,132-case probe found. The official 1,281-file
 * acceptance suite covers none of them, which is why they regressed unnoticed.
 *
 * <p>Each nested class names the root cause it pins, so a future refactor that reverts the
 * behaviour fails with an explanation rather than a bare value mismatch. See
 * {@code docs/design/CONFORMANCE-REVIEW.md}.
 */
class ConformanceRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    private static JsonNode eval(String expression) throws Exception {
        return eval(expression, NullNode.instance);
    }

    private static JsonNode eval(String expression, JsonNode input) throws Exception {
        JsonataExpression compiled = FACTORY.compile(expression);
        return compiled.evaluate(input);
    }

    private static String text(String expression) throws Exception {
        return eval(expression).textValue();
    }

    private static String json(String expression) throws Exception {
        return MAPPER.writeValueAsString(eval(expression));
    }

    private static String errorCode(String expression) {
        JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                () -> eval(expression));
        return e.getErrorCode();
    }

    private static JsonNode data() throws Exception {
        return MAPPER.readTree("""
                {"a":1,"b":[1,2,3],"c":{"d":4},"e":[{"f":1},{"f":2}],"g":[[{"h":1}]]}""");
    }

    // =====================================================================
    @Nested
    class NumberRendering {
        /**
         * {@code $string} of a number is ECMA-262 {@code Number::toString} of the double,
         * after a non-integer is rounded to 15 significant digits. The previous
         * hand-rolled notation rules diverged on subnormals, on the 1e21 exponential
         * switch, on integers beyond 2^53, and on the 1e-7 lower switch — its
         * {@code "0.0000"}-prefix test put {@code 0.00001} into scientific notation.
         */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$string(1e21)                        | 1e+21",
            "$string(1e20)                        | 100000000000000000000",
            "$string(1e-6)                        | 0.000001",
            "$string(1e-7)                        | 1e-7",
            "$string(1e-320)                      | 1e-320",
            "$string(5e-324)                      | 5e-324",
            "$string(0.00001)                     | 0.00001",
            "$string(1e16)                        | 10000000000000000",
            "$string(1/3)                         | 0.333333333333333",
            "$string($power(2,70))                | 1.1805916207174113e+21",
            "$string(12345678901234567890)        | 12345678901234567000",
        })
        void ecmaNumberToString(String expression, String expected) throws Exception {
            assertEquals(expected, text(expression));
        }

        /**
         * The integral fast path is bounded at 2^53, not at the 1e21 notation boundary:
         * above 2^53 a double's exact integer value has more digits than its shortest
         * round-tripping form, so widening it would silently break this case.
         */
        @Test
        void integersBeyondTwoToThe53UseShortestRoundTrip() throws Exception {
            assertEquals("12345678901234567000", text("$string(12345678901234567890)"));
            assertEquals("9007199254740994", text("$string($power(2,53)+2)"));
        }

        /**
         * {@code $floor}/{@code $ceil} used a raw {@code (long)} cast, which saturates at
         * {@code Long.MAX_VALUE}, so {@code $floor(1e21)} became 9223372036854775807.
         */
        @Test
        void floorAndCeilStayDoublesBeyondLongRange() throws Exception {
            assertEquals("1e+21", text("$string($floor(1e21))"));
            assertEquals("100000000000000000000", text("$string($ceil(1e20))"));
            assertEquals("12345678901234567000", text("$string($floor(12345678901234567890))"));
        }
    }

    // =====================================================================
    @Nested
    class ConcatOptimizerRewrite {
        /**
         * The optimizer rewrote {@code x & ""} to {@code x}. That is only the identity
         * when {@code x} is already a string: {@code &} stringifies, so the rewrite
         * returned the unconverted operand for every other type.
         */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "0 & \"\"           | \"0\"",
            "true & \"\"        | \"true\"",
            "\"\" & 5           | \"5\"",
            "[1,2] & \"\"       | \"[1,2]\"",
        })
        void emptyStringConcatStillStringifies(String expression, String expected) throws Exception {
            assertEquals(expected, json(expression));
        }
    }

    // =====================================================================
    @Nested
    class FormatNumber {
        /**
         * {@code $formatNumber} was a {@code java.text.DecimalFormat}-shaped pipeline
         * written against the XSLT 2.0 picture grammar; it is now a port of XPath 3.1
         * F&amp;O {@code fn:format-number}. The symptoms below — no leading-zero elision,
         * a trailing decimal separator never suppressed, an exponent search that scaled
         * to the wrong mantissa — were one structural mismatch, not separate rules.
         */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$formatNumber(0, '#')                | 0",
            "$formatNumber(1, '#.##')             | 1.0",
            "$formatNumber(0.5, '#.##')           | .5",
            "$formatNumber(1234.5678, '#0.0#e0')  | 1.23e3",
            "$formatNumber(1000000, '0.0e0')      | 10.0e5",
            "$formatNumber(-5, '0;POS0')          | POS5",
        })
        void foAlgorithm(String expression, String expected) throws Exception {
            assertEquals(expected, text(expression));
        }

        /**
         * The algorithm is defined over ECMAScript numeric primitives and inherits their
         * artefacts: {@code toFixed} hands off to the plain number rendering at 1e21, so
         * the exponent leaks into what is supposed to be fixed-point output.
         */
        @Test
        void inheritsToFixedHandoffAt1e21() throws Exception {
            assertEquals("1e+21.00", text("$formatNumber(1e21, '0.00')"));
        }

        /**
         * Irregular grouping positions are spliced with JavaScript's {@code slice}, whose
         * negative-index behaviour drops digits. Clamping instead would look tidier and
         * disagree with the reference on every short value.
         */
        @Test
        void irregularGroupingReproducesSliceSemantics() throws Exception {
            assertEquals("12,35,", text("$formatNumber(1234.5678, '#,##,##0')"));
        }

        /** The scaling is read from the sub-picture actually chosen, not the positive one. */
        @Test
        void percentOverrideFromOptions() throws Exception {
            assertEquals("50.0p", text("$formatNumber(0.5, '0.0p', {\"percent\":\"p\"})"));
        }
    }

    // =====================================================================
    @Nested
    class FormatInteger {
        /**
         * The sign is stripped, the magnitude formatted and {@code "-"} prepended — for
         * every primary format. Sign used to participate in the padding, so {@code -7}
         * with {@code "01"} gave {@code "-7"}, and the roman and alphabetic pictures
         * rejected negatives outright.
         */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$formatInteger(-7, '01')     | -07",
            "$formatInteger(-7, '1;o')    | -7th",
            "$formatInteger(-7, 'I')      | -VII",
            "$formatInteger(-7, 'a')      | -g",
            "$formatInteger(-1, 'w')      | -one",
        })
        void signIsPrependedToTheFormattedMagnitude(String expression, String expected)
                throws Exception {
            assertEquals(expected, text(expression));
        }

        /** The reference floors; a {@code (long)} cast truncated toward zero. */
        @Test
        void floorsRatherThanTruncates() throws Exception {
            assertEquals("-13", text("$formatInteger(-12.6, '0')"));
        }

        /** Zero yields no letters at all, rather than an error. */
        @Test
        void alphabeticZeroIsEmpty() throws Exception {
            assertEquals("", text("$formatInteger(0, 'a')"));
        }

        /** Above 3,999 the reference simply repeats M; there is no upper bound. */
        @Test
        void romanBeyond3999Repeats() throws Exception {
            assertEquals("mmmm", text("$formatInteger(4000, 'i')"));
        }

        /**
         * A picture with no mandatory digit is a "numbering sequence", which the spec
         * leaves implementation-defined and the reference does not support.
         */
        @Test
        void optionalDigitsOnlyIsD3130() {
            assertEquals("D3130", errorCode("$formatInteger(1, '#')"));
        }

        /**
         * The comma placement is the reference's own, not "and" everywhere: a magnitude
         * or hundreds boundary takes {@code ", "} and a smaller remainder {@code " and "}.
         */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$formatInteger(1970, 'w')   | one thousand, nine hundred and seventy",
            "$formatInteger(1970, 'Ww')  | One Thousand, Nine Hundred and Seventy",
        })
        void wordSeparators(String expression, String expected) throws Exception {
            assertEquals(expected, text(expression));
        }
    }

    // =====================================================================
    @Nested
    class ParseInteger {
        /**
         * {@code $parseInteger} does no validation at all: the reference builds a matcher
         * from the picture and runs its parse function over whatever it is given, so a
         * mismatched input falls out as undefined rather than an error. Rejecting these
         * turned four in five of the probe's parse cases into errors.
         */
        @Test
        void unparseableInputIsUndefined() throws Exception {
            assertTrue(eval("$parseInteger('MCMXCIV', '0')").isMissingNode());
            assertTrue(eval("$parseInteger('IZI', 'I')").isMissingNode());
            assertTrue(eval("$parseInteger('', 'w')").isMissingNode());
        }

        /** JavaScript's parseInt stops at the comma, which "0" does not name a separator. */
        @Test
        void stopsAtAnUnnamedSeparator() throws Exception {
            assertEquals(1, eval("$parseInteger('1,234', '0')").intValue());
        }

        /** An ordinal picture drops the last two characters whether or not they are a suffix. */
        @Test
        void ordinalStripIsUnconditional() throws Exception {
            assertEquals(0, eval("$parseInteger('007', '1;o')").intValue());
            assertEquals(12, eval("$parseInteger('twelfth', 'w;o')").intValue());
        }
    }

    // =====================================================================
    @Nested
    class DateTimePictureComponents {
        /**
         * F&amp;O §9.8.4.3 routes every integer-valued component through the same integer
         * formatter {@code $formatInteger} uses. This port hand-wrote a partial modifier
         * set per component instead, so these silently fell back to decimal or threw.
         */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$fromMillis(0, '[Ya]')   | bwt",
            "$fromMillis(0, '[YA]')   | BWT",
            "$fromMillis(0, '[Mw]')   | one",
            "$fromMillis(0, '[DW]')   | ONE",
            "$fromMillis(0, '[Di]')   | i",
            "$fromMillis(1521801216617, '[D1o]') | 23rd",
        })
        void componentModifiers(String expression, String expected) throws Exception {
            assertEquals(expected, text(expression));
        }

        @Test
        void yearInWordsUsesTheIntegerFormatter() throws Exception {
            assertEquals("One Thousand, Nine Hundred and Seventy", text("$fromMillis(0, '[YWw]')"));
        }

        /**
         * {@code [f]} is not a decimal fraction: the raw millisecond value goes through
         * the integer path, so the width pads and never scales.
         */
        @Test
        void fractionalSecondsAreAnInteger() throws Exception {
            assertEquals("0001", text("$fromMillis(1, '[f0001]')"));
            assertEquals("150", text("$fromMillis(1510067557150, '[f01]')"));
        }

        /**
         * A month's first week is the Monday-based week containing its first Thursday, so
         * 1970-01-01 is week 1. The previous day-of-month/7 rule reported week 5.
         */
        @Test
        void weekInMonthUsesTheFirstThursdayRule() throws Exception {
            assertEquals("1", text("$fromMillis(0, '[w]')"));
        }

        /** {@code [z]} is {@code [Z]} with a GMT prefix, and it honours its picture. */
        @Test
        void gmtOffsetHonoursItsPicture() throws Exception {
            assertEquals("GMT+00:00", text("$fromMillis(0, '[z]')"));
            assertEquals("GMT+0000", text("$fromMillis(0, '[z0000]')"));
        }

        /** A year width maximum assigns the digit count rather than raising it. */
        @Test
        void yearWidthMaximumTruncates() throws Exception {
            assertEquals("23/03/18",
                    text("$fromMillis(1521801216617, '[D#1,2]/[M1,2]/[Y0001,2-2]')"));
        }

        /** Pre-1970 instants format like any other. */
        @Test
        void preEpochInstants() throws Exception {
            assertEquals("1900-01-01", text("$fromMillis(-2208988800000, '[Y0001]-[M01]-[D01]')"));
        }
    }

    // =====================================================================
    @Nested
    class RegexCursor {
        /**
         * One shared cursor drives {@code $match}, {@code $split}, {@code $replace} and
         * {@code $contains}. The D1004 guard fires only on a <em>produced subsequent</em>
         * empty match, so a first empty match is legal while a second one is not.
         *
         * <p>Previously only {@code $replace} raised D1004, and it raised it on the first
         * empty match; {@code $match} and {@code $split} advanced a byte at a time.
         * Joni also re-anchors {@code ^} at the subject start, so {@code $match("abc", /^/)}
         * never advanced and built matches until the heap was exhausted.
         */
        @Test
        void firstEmptyMatchIsLegal() throws Exception {
            assertEquals("{\"match\":\"\",\"index\":3,\"groups\":[]}", json("$match('abc', /$/)"));
        }

        @Test
        void subsequentEmptyMatchIsD1004() {
            assertEquals("D1004", errorCode("$match('abc', /^/)"));
            assertEquals("D1004", errorCode("$match('Hello World', /x*/)"));
            assertEquals("D1004", errorCode("$split('abc', /x*/)"));
        }

        /** A sequence collapses: one match is the bare object, none is undefined. */
        @Test
        void singleMatchCollapses() throws Exception {
            assertEquals("{\"match\":\"b\",\"index\":1,\"groups\":[]}", json("$match('abc', /b/)"));
            assertTrue(eval("$match('abc', /z/)").isMissingNode());
        }

        /** A capture group that did not participate is null, not an empty string. */
        @Test
        void nonParticipatingGroupIsNull() throws Exception {
            assertEquals("{\"match\":\"a\",\"index\":0,\"groups\":[\"a\",null]}",
                    json("$match('a.c', /(a)(b)?/)"));
        }

        /**
         * The replacer receives the raw matcher-closure object — match, start, end,
         * groups and next — not the remapped shape {@code $match} publishes.
         */
        @Test
        void replacerReceivesTheMatcherClosure() throws Exception {
            assertEquals("a[\"match\",\"start\",\"end\",\"groups\",\"next\"]c",
                    text("$replace('abc', /b/, function($m) { $string($keys($m)) })"));
        }

        /**
         * {@code $0} is recognised before any digit parsing, so {@code "$01"} is the whole
         * match followed by a literal 1 — not group 1.
         */
        @Test
        void dollarZeroBindsBeforeDigits() throws Exception {
            assertEquals("a1b1c1123", text("$replace('abc123', /([a-z])(\\d)?/, '$01')"));
        }

        /** $split's regex form compares its limit as a number rather than truncating it. */
        @Test
        void regexSplitLimitIsNumeric() throws Exception {
            assertEquals("[\"\",\"\"]", json("$split('aaa', /a/, 1.5)"));
        }

        /** A string separator instead truncates, because the reference slices the array. */
        @Test
        void stringSplitLimitTruncates() throws Exception {
            assertEquals("[\"a\",\"b\"]", json("$split('a, b, c, d', ', ', 2.5)"));
        }
    }

    // =====================================================================
    @Nested
    class RegexDialect {
        /**
         * Joni is Oniguruma, and its option names do not mean what the JavaScript flag
         * letters of the same name mean: {@code SINGLELINE} is JavaScript's default and
         * {@code MULTILINE} is JavaScript's {@code /s}. Mapping {@code m} onto
         * {@code MULTILINE} got it wrong twice — every pattern behaved as if it carried
         * {@code /m}, and an explicit {@code m} turned on dot-matches-newline instead.
         */
        @Test
        void anchorsBindTheWholeStringWithoutM() throws Exception {
            assertFalse(eval("$contains('a\\nb', /^b/)").booleanValue());
            assertTrue(eval("$contains('a\\nb', /^b/m)").booleanValue());
        }

        @Test
        void mDoesNotMakeDotSpanNewlines() throws Exception {
            assertFalse(eval("$contains('a\\nb', /a.b/m)").booleanValue());
        }

        /**
         * Oniguruma's {@code $} under SINGLELINE still matches before a trailing newline
         * (Ruby's {@code \\Z}); ECMAScript anchors only at the very end.
         */
        @Test
        void dollarDoesNotAllowATrailingNewline() throws Exception {
            assertEquals("{\"match\":\"\",\"index\":5,\"groups\":[]}", json("$match('tail\\n', /$/)"));
        }

        /**
         * ECMAScript's {@code .} excludes four line terminators where Oniguruma's excludes
         * only {@code \\n}, and its {@code /m} anchors break on all four as well.
         */
        @Test
        void lineTerminatorSetIsEcmaScripts() throws Exception {
            assertTrue(eval("$contains('a\\rb', /^b/m)").booleanValue());
            assertEquals(7, eval("$match('123-456', /./)").size());
        }
    }

    // =====================================================================
    @Nested
    class Codecs {
        /**
         * base64 is {@code btoa}/{@code atob}: every UTF-16 code unit contributes its low
         * byte, and decoding reverses it with the same charset. Encoding as latin-1 but
         * decoding as UTF-8 made the pair non-invertible.
         */
        @Test
        void base64IsSelfInverse() throws Exception {
            assertEquals("6Q==", text("$base64encode('é')"));
            assertEquals("é", text("$base64decode($base64encode('é'))"));
        }

        /** Decoding is lenient, like Node's {@code Buffer.from(s, "base64")}. */
        @Test
        void base64DecodeIsLenient() throws Exception {
            assertEquals("", text("$base64decode('!!!!')"));
            assertEquals("a", text("$base64decode('YQ===')"));
        }

        /**
         * {@code encodeURIComponent} leaves {@code ! * ' ( )} alone, though RFC 3986
         * lists them as reserved.
         */
        @Test
        void urlComponentKeepsJavaScriptsUnreservedSet() throws Exception {
            assertEquals("Hello%2C%20World!", text("$encodeUrlComponent('Hello, World!')"));
        }
    }

    // =====================================================================
    @Nested
    class SequencesAndObjects {
        /** {@code $reverse}'s signature is {@code <a:a>}, so a scalar is array-wrapped. */
        @Test
        void reverseWrapsAScalar() throws Exception {
            assertEquals("[1]", json("$reverse(1)"));
        }

        /**
         * The default comparator is never invoked for a single element, so a one-element
         * array of anything sorts to itself.
         */
        @Test
        void sortOfOneElementNeedsNoComparator() throws Exception {
            assertEquals("[{\"a\":1}]", json("$sort([{\"a\":1}])"));
        }

        /**
         * A mix the default comparator cannot order is D3070 — that belongs to
         * {@code $sort}; T2007/T2008 belong to the {@code ^(key)} order-by operator.
         */
        @Test
        void sortOfAMixedArrayIsD3070() {
            assertEquals("D3070", errorCode("$sort([1,\"a\"])"));
        }

        /** {@code $clone} was not registered at all — it raised T1006. */
        @Test
        void cloneIsRegistered() throws Exception {
            assertEquals("{\"a\":[1,2]}", json("$clone({\"a\":[1,2]})"));
        }

        /** {@code $keys} and {@code $lookup} recurse into nested arrays. */
        @Test
        void keysAndLookupRecurse() throws Exception {
            assertEquals("\"h\"", MAPPER.writeValueAsString(eval("$keys(g)", data())));
            assertEquals("1", MAPPER.writeValueAsString(eval("$lookup(g, \"h\")", data())));
        }

        /** {@code $spread} builds a sequence, and a single-key object collapses. */
        @Test
        void spreadCollapsesASingleKey() throws Exception {
            assertEquals("{\"d\":4}", MAPPER.writeValueAsString(eval("$spread(c)", data())));
        }

        /** An absent sequence reduces to absent regardless of the initial value. */
        @Test
        void reduceOfMissingIsMissing() throws Exception {
            assertTrue(eval("$reduce(nope, function($a,$b){$a+$b}, 5)", data()).isMissingNode());
        }

        /**
         * The translator checks a literal reducer's arity at compile time; a variable
         * holding one, or a built-in, reached the runtime unchecked and was invoked with
         * the packed 4-element tuple.
         */
        @Test
        void reduceValidatesADynamicReducersArity() {
            assertEquals("D3050", errorCode("$reduce([1,2], $sum)"));
        }
    }

    // =====================================================================
    @Nested
    class NumericAndTemporal {
        /**
         * The tie window has to be sound for the ceiling it is paired with. This port
         * paired a 1e15 ceiling with a 1e-9 window, but {@code v * 10^p} is accurate to
         * about half an ulp of the product — 0.125 at 1e15 — so the fast path sailed past
         * genuine ties. It is now the reference's own pairing.
         */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "$round(-36435.03133177965, 10) | -36435.0313317796",
            "$round(2.675, 2)               | 2.68",
            "$round(8.835, 2)               | 8.84",
        })
        void roundHalfEven(String expression, String expected) throws Exception {
            assertEquals(expected, json(expression));
        }

        /**
         * {@code $number}'s grammar is narrower than {@code Double.parseDouble}'s: no
         * leading {@code +}, no bare {@code .5} or trailing {@code 1.}, no surrounding
         * whitespace.
         */
        @ParameterizedTest
        @CsvSource({"$number('1.')", "$number('.5')", "$number('+1')", "$number(' 1 ')"})
        void rejectedNumberForms(String expression) {
            assertEquals("D3030", errorCode(expression));
        }

        @Test
        void radixLiteralsStillParse() throws Exception {
            assertEquals(26, eval("$number('0x1A')").intValue());
        }

        /**
         * The reference does not validate the day against the month's length; it lets the
         * surplus roll over, so 2023-02-29 is 1 March.
         */
        @Test
        void toMillisRollsAnInRangeDayOver() throws Exception {
            assertEquals(1677628800000L, eval("$toMillis('2023-02-29')").longValue());
        }

        /**
         * {@code $now}/{@code $millis} are frozen for a top-level evaluation, and a nested
         * {@code $eval} is part of that evaluation rather than a new one.
         */
        @Test
        void nestedEvalSharesTheOuterClock() throws Exception {
            assertTrue(eval("$eval(\"$millis()\") = $millis()").booleanValue());
        }
    }
}
