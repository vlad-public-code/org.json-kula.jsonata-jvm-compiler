package org.json_kula.jsonata_jvm.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.json_kula.jsonata_jvm.JsonataCompilationException;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calling a function from a path step.
 *
 * <p>Two different things are spelled almost the same way, and JSONata resolves them
 * differently:
 *
 * <ul>
 *   <li>{@code a.g(...)} — a bare name — calls the <em>field</em> {@code g} of the step
 *       context. It is never a variable and never a built-in, so {@code $o.count()} is
 *       {@code $o}'s own {@code count}.</li>
 *   <li>{@code a.$fn(...)} calls the variable or built-in {@code $fn}. Whether the step
 *       context is handed to it is decided by the built-in's declared signature: a
 *       parameter marked {@code -} is filled from the context, and a built-in without one
 *       is called with exactly the arguments written.</li>
 * </ul>
 *
 * <p>Every expectation here was produced by running the expression through the reference
 * interpreter (jsonata 2.2.2), not read off the implementation. See
 * {@code docs/design/CONFORMANCE-REVIEW.md}.
 */
class PathStepCallTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    /** An object whose fields include functions, one of them named after a built-in. */
    private static final String OBJ =
            "$o := {\"g\": function(){42}, \"h\": function($x){$x * 2}, "
            + "\"count\": function(){\"field!\"}, \"n\": 5}";

    private static JsonNode eval(String expression) throws Exception {
        return eval(expression, NullNode.instance);
    }

    private static JsonNode eval(String expression, JsonNode input) throws Exception {
        return FACTORY.compile(expression).evaluate(input);
    }

    private static String json(String expression) throws Exception {
        return MAPPER.writeValueAsString(eval(expression));
    }

    private static String json(String expression, JsonNode input) throws Exception {
        return MAPPER.writeValueAsString(eval(expression, input));
    }

    /** The error code, wherever it is raised — the reference does not distinguish phases. */
    private static String errorCode(String expression) {
        Throwable thrown = assertThrows(Throwable.class, () -> eval(expression));
        if (thrown instanceof JsonataEvaluationException e) return e.getErrorCode();
        if (thrown instanceof JsonataCompilationException e) return e.getErrorCode();
        throw new AssertionError("unexpected failure for " + expression, thrown);
    }

    private static JsonNode data() throws Exception {
        return MAPPER.readTree("""
                {"o": {"g": 1}, "arr": [{"n": 1}, {"n": 2}], "s": "hi", "nums": [1, 2, 3]}""");
    }

    // =====================================================================
    @Nested
    class FieldFunctionCall {

        @Test
        void callsTheField() throws Exception {
            assertEquals("42", json("(" + OBJ + "; $o.g())"));
        }

        @Test
        void passesArguments() throws Exception {
            assertEquals("42", json("(" + OBJ + "; $o.h(21))"));
        }

        /** A surplus argument is simply ignored by a zero-parameter function. */
        @Test
        void surplusArgumentIsIgnored() throws Exception {
            assertEquals("42", json("(" + OBJ + "; $o.g(21))"));
        }

        /**
         * The distinction that makes this worth having: a bare name is the context's own
         * field even when a built-in shares the name.
         */
        @Test
        void aBuiltinNameIsStillAField() throws Exception {
            assertEquals("\"field!\"", json("(" + OBJ + "; $o.count())"));
            assertEquals("\"mine\"", json("($o := {\"count\": function(){\"mine\"}}; $o.count())"));
        }

        /** Writing the {@code $} asks for the variable instead, even when a field shadows it. */
        @Test
        void dollarPrefixSelectsTheVariable() throws Exception {
            String bindings = "$g := function(){\"variable\"}; $o := {\"g\": function(){\"field\"}}; ";
            assertEquals("\"field\"", json("(" + bindings + "$o.g())"));
            assertEquals("\"variable\"", json("(" + bindings + "$o.$g())"));
        }

        /** Arguments are evaluated against the step context, with the outer scope visible. */
        @Test
        void argumentsSeeTheStepContext() throws Exception {
            assertEquals("7", json("($o := {\"g\": function($x){$x}, \"v\": 7}; $o.g(v))"));
            assertEquals("14", json("($o := {\"g\": function($x){$x}, \"v\": 7}; $o.g(v * 2))"));
            assertEquals("17",
                    json("($k := 10; $o := {\"g\": function($x){$x}, \"v\": 7}; $o.g(v + $k))"));
        }

        @Test
        void severalArgumentsArePacked() throws Exception {
            assertEquals("10",
                    json("($o := {\"g\": function($x,$y){$x + $y}, \"v\": 7}; $o.g(v, 3))"));
        }

        /**
         * The one-shot nature of the parser flag: a call nested inside the step's own
         * arguments is an ordinary call again, not another field lookup.
         */
        @Test
        void aBuiltinInsideTheArgumentsIsStillABuiltin() throws Exception {
            assertEquals("\"HI\"",
                    json("($o := {\"g\": function($x){$x}, \"v\": \"hi\"}; $o.g($uppercase(v)))"));
            assertEquals("3",
                    json("($o := {\"g\": function($x){$x}}; $o.g($count([1,2,3])))"));
        }

        @Test
        void mapsOverASequence() throws Exception {
            assertEquals("[1,2]",
                    json("($a := [{\"g\": function(){1}}, {\"g\": function(){2}}]; $a.g())"));
            assertEquals("[1,2]",
                    json("($o := {\"a\": [{\"g\": function(){1}},{\"g\": function(){2}}]}; $o.a.g())"));
        }

        @Test
        void chainsAndNests() throws Exception {
            assertEquals("\"deep\"",
                    json("($o := {\"a\": {\"g\": function(){\"deep\"}}}; $o.a.g())"));
            assertEquals("\"deep\"",
                    json("($o := {\"g\": function(){ {\"h\": function(){\"deep\"}} }}; $o.g().h())"));
        }

        @Test
        void receiverMayBeAnyExpression() throws Exception {
            assertEquals("42", json("({\"g\": function(){42}}).g()"));
            assertEquals("9", json("($f := function(){9}; {\"g\": $f}.g())"));
        }

        /** An absent receiver yields an absent result: the callee is never resolved. */
        @Test
        void absentReceiverSkipsTheCall() throws Exception {
            assertTrue(eval("(nothing.g())").isMissingNode());
            assertTrue(eval("($o := {}; $o.nope.g())").isMissingNode());
            assertTrue(eval("($o := {\"x\": []}; $o.x.g())").isMissingNode());
        }

        /**
         * A present-but-unsuitable receiver is <em>not</em> skipped — including a JSON
         * null, which is why this cannot reuse the ordinary step mapping.
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "($o := {\"x\": null}; $o.x.g())",
            "($o := {\"x\": 5}; $o.x.g())",
            "($o := {\"x\": [1,2]}; $o.x.g())",
            "($o := {\"x\": {}}; $o.x.g())",
            "((5).g())",
        })
        void unsuitableReceiverIsT1006(String expression) {
            assertEquals("T1006", errorCode(expression));
        }

        /** A field that is not a function cannot be called, whatever it holds. */
        @Test
        void nonFunctionFieldIsT1006() {
            assertEquals("T1006", errorCode("(" + OBJ + "; $o.n())"));
            assertEquals("T1006", errorCode("(" + OBJ + "; $o.nope())"));
            assertEquals("T1006", errorCode("($o := {\"a\":1}; $o.nope())"));
        }

        /** Every element must resolve, not just the first. */
        @Test
        void everyElementMustResolve() {
            assertEquals("T1006",
                    errorCode("($a := [{\"g\": function(){1}}, {\"n\": 2}]; $a.g())"));
            assertEquals("T1006",
                    errorCode("($a := [{\"g\": function(){1}}, {\"g\": 5}]; $a.g())"));
        }

        /**
         * A missing field whose name is also a built-in reports T1005, which says what the
         * author probably meant, rather than a bare T1006.
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "($o := {\"a\": 1}; $o.count())",
            "($o := {\"a\": 1}; $o.uppercase())",
            "($o := {\"a\": 1}; $o.sum())",
        })
        void missingBuiltinNamedFieldSuggestsTheBuiltin(String expression) {
            assertEquals("T1005", errorCode(expression));
        }

        /**
         * A <em>quoted</em> step is not a field call: the reference invokes the quoted text
         * as a string literal, which is T1006.
         */
        @Test
        void quotedStepIsNotAFieldCall() {
            assertEquals("T1006", errorCode("($o := {\"g\": function(){42}}; $o.\"g\"())"));
            assertEquals("T1006", errorCode("($o := {\"g h\": function(){42}}; $o.\"g h\"())"));
        }

        /** Parenthesising the field reference is a different, already-supported form. */
        @Test
        void parenthesisedFieldReferenceStillWorks() throws Exception {
            assertEquals("42", json("(" + OBJ + "; ($o.g)())"));
        }

        /** The receiver may come from the input document rather than a binding. */
        @Test
        void resolvesAgainstInputData() {
            JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                    () -> eval("o.g()", data()));
            assertEquals("T1006", e.getErrorCode());
        }
    }

    // =====================================================================
    @Nested
    class BuiltinAsAPathStep {

        /**
         * A built-in whose signature has no {@code -} never receives the step context, so
         * writing it as a step calls it with no arguments at all — T0410.
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "[1,2].$count()", "[1,2,3].$sum()", "[3,1,2].$sort()", "[1,2].$reverse()",
            "[1,2].$append(3)", "[1,2].$map(function($v){$v*2})",
            "[1,2].$filter(function($v){$v>1})",
        })
        void withoutAContextSlotTheContextIsNotPassed(String expression) {
            assertEquals("T0410", errorCode(expression));
        }

        @Test
        void withoutAContextSlotOverData() throws Exception {
            for (String expression : new String[] {
                    "nums.$count()", "nums.$sum()", "nums.$reverse()", "nums.$max()",
                    "nums.$sort()", "arr.$count()", "o.$type()" }) {
                JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                        () -> eval(expression, data()), expression);
                assertEquals("T0410", e.getErrorCode(), expression);
            }
        }

        /** With a {@code -} slot the context fills it. */
        @Test
        void withAContextSlotTheContextIsPassed() throws Exception {
            assertEquals("\"a\"", json("{\"a\":1}.$keys()"));
            assertEquals("\"5\"", json("(5).$string()"));
            assertEquals("5", json("(-5).$abs()"));
            assertEquals("3", json("(2.6).$round()"));
        }

        @Test
        void withAContextSlotOverData() throws Exception {
            assertEquals("\"HI\"", json("s.$uppercase()", data()));
            assertEquals("2", json("s.$length()", data()));
            assertEquals("\"g\"", json("o.$keys()", data()));
            assertEquals("true", json("s.$contains(\"h\")", data()));
            assertEquals("[\"1\",\"2\",\"3\"]", json("nums.$string()", data()));
        }

        /**
         * The context slides written arguments along: {@code $substring(1)} becomes
         * {@code $substring(context, 1)}. Getting this wrong used to crash the translator
         * with an IndexOutOfBoundsException rather than produce anything at all.
         */
        @Test
        void contextSlidesWrittenArgumentsAlong() throws Exception {
            assertEquals("\"i\"", json("s.$substring(1)", data()));
            assertEquals("8", json("(2).$power(3)"));
        }

        /**
         * But only when they still fit. A number cannot be a separator, so
         * {@code $split(12345)} is about the argument (T0410), not the context.
         */
        @Test
        void argumentsThatCannotFitPreventSubstitution() {
            assertEquals("T0410", errorCode("$split(12345)"));
        }

        /** {@code $round(2)} rounds 2 — the argument fits the first parameter as written. */
        @Test
        void anArgumentThatFitsTheFirstParameterIsNotSlid() throws Exception {
            assertEquals("2", json("(2.567).$round(2)"));
        }

        /**
         * A context of the wrong type is T0411 — "context value is not compatible" — where
         * a wrong-typed written argument is T0410.
         */
        @Test
        void wrongTypedContextIsT0411() {
            assertEquals("T0411", errorCode("(5).$uppercase()"));
        }

        /** Context substitution is not special to path steps; the input is the context. */
        @Test
        void appliesAtTheTopLevelToo() throws Exception {
            assertEquals("\"HI\"", json("$uppercase()", MAPPER.readTree("\"hi\"")));
            assertEquals("2", json("$length()", MAPPER.readTree("\"hi\"")));
            assertEquals("\"bc\"", json("$substring(1)", MAPPER.readTree("\"abc\"")));
        }

        /**
         * A bare quoted string heading a path is a <em>field name</em>, not a value, so the
         * whole path is undefined and nothing is called.
         */
        @ParameterizedTest
        @ValueSource(strings = {
            "\"abc\".$uppercase()", "\"abc\".$length()", "\"abc\".$substring(1)",
            "\"a,b\".$split(\",\")", "\"abc\".$floor()", "\"str\".g()",
        })
        void quotedStringPathHeadIsAFieldName(String expression) throws Exception {
            assertTrue(eval(expression).isMissingNode(), expression);
        }

        /** The higher-order built-ins keep their own context handling. */
        @Test
        void callbackBuiltinsStillSubstitute() throws Exception {
            assertEquals("\"a\"", json("{\"a\":1}.$each(function($v,$k){$k})"));
        }
    }

    // =====================================================================
    @Nested
    class LiteralPathSteps {

        /**
         * A bare number, boolean or null literal cannot be a path step. A parenthesised
         * one can — {@code (5).g()} resolves the callee and reports T1006 — and a quoted
         * string is a field name, so neither is rejected.
         */
        @ParameterizedTest
        @ValueSource(strings = { "true.g()", "5.g()", "null.g()", "5 . $string()",
            "1521801216617.$fromMillis()" })
        void bareLiteralPathStepIsS0213(String expression) {
            assertEquals("S0213", errorCode(expression));
        }

        @Test
        void parenthesisedLiteralIsAValue() throws Exception {
            assertEquals("T1006", errorCode("(5).g()"));
            assertEquals("\"5\"", json("(5).$string()"));
        }

        @Test
        void arrayAndObjectLiteralsAreValidSteps() {
            assertEquals("T1006", errorCode("[1].g()"));
            assertEquals("T1006", errorCode("{}.g()"));
        }
    }

    // =====================================================================
    @Nested
    class DeferredBuiltinNameError {

        /** A built-in called without its {@code $} is T1005. */
        @ParameterizedTest
        @ValueSource(strings = { "count([1,2])", "uppercase(\"a\")" })
        void bareBuiltinCallIsT1005(String expression) {
            assertEquals("T1005", errorCode(expression));
        }

        /**
         * But it is an <em>evaluation</em> error, not a compile error: the reference raises
         * it only if control reaches the call, so an expression that never takes that
         * branch must still compile and run.
         */
        @Test
        void isRaisedOnlyWhenReached() throws Exception {
            assertEquals("1", json("false ? count([1,2]) : 1"));
            assertEquals("\"safe\"", json("$exists(nope) ? uppercase(\"a\") : \"safe\""));
        }
    }

    // =====================================================================
    @Nested
    class NestedZeroParameterLambdas {

        /**
         * Two zero-parameter lambdas nested in one generated method both declared the same
         * unused parameter name, which does not compile. Surfaced by chaining field calls,
         * whose callees are typically zero-parameter functions.
         */
        @Test
        void compileWithoutNameCollision() throws Exception {
            assertEquals("\"deep\"",
                    json("($o := {\"g\": function(){ {\"h\": function(){\"deep\"}} }}; $o.g().h())"));
            assertEquals("3", json("($f := function(){ function(){3} }; $f()())"));
        }
    }
}
