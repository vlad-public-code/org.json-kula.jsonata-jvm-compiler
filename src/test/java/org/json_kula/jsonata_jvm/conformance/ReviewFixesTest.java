package org.json_kula.jsonata_jvm.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the findings of the 2026-09-18 code review (issues J-1 … J-22,
 * P-1 … P-10, M-1 … M-4).
 *
 * <p><b>Every expected value in this file was produced by running the expression through the
 * reference implementation</b> (jsonata 2.2.2, {@code c:\vlad-projects\js\jsonata}), not by
 * reading the current Java behaviour. Each nested class names the issue it pins so that a
 * regression fails with an explanation rather than a bare value mismatch.
 */
class ReviewFixesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    private static JsonNode eval(String expression) throws Exception {
        return eval(expression, NullNode.instance);
    }

    private static JsonNode eval(String expression, String inputJson) throws Exception {
        return eval(expression, MAPPER.readTree(inputJson));
    }

    private static JsonNode eval(String expression, JsonNode input) throws Exception {
        JsonataExpression compiled = FACTORY.compile(expression);
        return compiled.evaluate(input);
    }

    /** The result serialised as JSON, or {@code "**undefined**"} when nothing was produced. */
    private static String json(String expression) throws Exception {
        return json(expression, NullNode.instance);
    }

    private static String json(String expression, String inputJson) throws Exception {
        return json(expression, MAPPER.readTree(inputJson));
    }

    private static String json(String expression, JsonNode input) throws Exception {
        JsonNode result = eval(expression, input);
        return result == null || result.isMissingNode() ? "**undefined**"
                : MAPPER.writeValueAsString(result);
    }

    private static String errorCode(String expression) {
        return errorCode(expression, NullNode.instance);
    }

    private static String errorCode(String expression, JsonNode input) {
        JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                () -> eval(expression, input));
        return e.getErrorCode();
    }

    // =====================================================================================
    // J-1 — the tail-call sentinel must never leave the trampoline
    // =====================================================================================

    /**
     * A lambda body is compiled in tail position, and a user function call in tail position is
     * emitted as {@code fn_apply_tco}, which returns a sentinel for the trampoline to unwind.
     * Everything that merely *contains* the returned expression — a constructor element, an
     * object value, the left side of {@code ?:} / {@code ??}, a path head, a range bound — is
     * not a tail position, and used to leak the sentinel into the result as data.
     */
    @Nested
    class TailCallSentinel {

        @Test
        void objectConstructorValue() throws Exception {
            assertEquals("{\"v\":6}", json(
                    "( $double := function($x){$x*2};"
                    + " $wrap := function($n){ {\"v\": $double($n)} }; $wrap(3) )"));
        }

        @Test
        void arrayConstructorElement() throws Exception {
            assertEquals("[6]", json(
                    "( $double := function($x){$x*2};"
                    + " $wrap := function($n){ [$double($n)] }; $wrap(3) )"));
        }

        @Test
        void pathHead() throws Exception {
            assertEquals("3", json(
                    "( $mk := function($x){ {\"y\":$x} };"
                    + " $wrap := function($n){ $mk($n).y }; $wrap(3) )"));
        }

        @Test
        void elvisLeftOperand() throws Exception {
            assertEquals("\"fallback\"", json(
                    "( $z := function($x){0};"
                    + " $wrap := function($n){ $z($n) ?: \"fallback\" }; $wrap(3) )"));
        }

        @Test
        void coalesceLeftOperand() throws Exception {
            assertEquals("\"fallback\"", json(
                    "( $z := function($x){$x.nope};"
                    + " $wrap := function($n){ $z($n) ?? \"fallback\" }; $wrap(3) )"));
        }

        @Test
        void rangeBound() throws Exception {
            assertEquals("[1,2,3,4]", json(
                    "( $d := function($x){$x*2};"
                    + " $w := function($n){ [1..$d($n)] }; $w(2) )"));
        }

        /** Tail-call optimisation itself must still work: this recurses 50,000 deep. */
        @Test
        void deepTailRecursionStillOptimised() throws Exception {
            assertEquals("50000", json(
                    "( $f := function($i, $acc){ $i = 0 ? $acc : $f($i - 1, $acc + 1) };"
                    + " $f(50000, 0) )"));
        }
    }

    // =====================================================================================
    // J-2 — the optimizer must not drop the flags carried on rebuilt AST records
    // =====================================================================================

    /**
     * {@code Lambda.signature}, {@code PredicateExpr.stage}, {@code FunctionCall.isVariable}
     * and {@code ArrayConstructor.pathHead} reset to their defaults whenever the optimizer
     * rebuilt a node with the short convenience constructor — which it did for any node with a
     * constant-folded child.
     */
    @Nested
    class OptimizerPreservesRecordFlags {

        @Test
        void lambdaSignatureSurvivesAFoldInTheBody() {
            // Without the signature, $f(5) silently returned "5a" instead of type-checking.
            assertEquals("T0410", errorCode("( $f := function($x)<s>{ $x & (\"\" & \"a\") }; $f(5) )"));
        }

        @Test
        void predicateStageSurvivesAFoldInThePredicate() throws Exception {
            assertEquals("[2,2]", json("objs.[1,2][$ > (1+0)]", "{\"objs\":[{},{}]}"));
        }

        @Test
        void functionCallIsVariableSurvivesAFoldInAnArgument() throws Exception {
            assertEquals("20", json("( $o := {\"g\": function($v){$v*10}}; $o.g(1+1) )"));
        }

        // ArrayConstructor.pathHead is covered by the AST round-trip test in OptimizerTest
        // (optimize_rebuiltNodes_keepTheirFlags) — every shape that distinguishes it also
        // needs a fold inside the constructor to trigger the rebuild.
    }

    // =====================================================================================
    // J-3 — `false ? x` is undefined, not JSON null
    // =====================================================================================

    @Nested
    class ConditionalWithoutElseIsUndefined {

        @Test
        void inArrayConstructor() throws Exception {
            assertEquals("[1,2]", json("[1, false ? 1, 2]"));
        }

        @Test
        void inObjectConstructor() throws Exception {
            assertEquals("{}", json("{\"a\": false ? 1}"));
        }

        @Test
        void nullConditionInArrayConstructor() throws Exception {
            assertEquals("[1,2]", json("[1, null ? 1, 2]"));
        }

        @Test
        void twoBranchFoldStillHappens() throws Exception {
            assertEquals("\"no\"", json("false ? \"yes\" : \"no\""));
        }
    }

    // =====================================================================================
    // J-4 — arithmetic / boolean identity folds are unsound
    // =====================================================================================

    @Nested
    class NoUnsoundIdentityFolds {

        @Test
        void missingTimesZeroStaysMissing() throws Exception {
            assertEquals("**undefined**", json("nope * 0", "{}"));
        }

        @Test
        void missingPlusZeroStaysMissing() throws Exception {
            assertEquals("**undefined**", json("nope + 0", "{}"));
        }

        @Test
        void stringPlusZeroIsATypeError() {
            assertEquals("T2001", errorCode("\"a\" + 0"));
        }

        @Test
        void andWithFalseOnTheRightStillEvaluatesTheLeft() {
            assertEquals("D3137", errorCode("$error(\"x\") and false"));
        }

        @Test
        void orWithTrueOnTheRightStillEvaluatesTheLeft() {
            assertEquals("D3137", errorCode("$error(\"x\") or true"));
        }

        @Test
        void doubleNegationOfAStringIsATypeError() {
            assertEquals("D1002", errorCode("-(-\"a\")"));
        }

        @Test
        void leftLiteralShortCircuitIsStillSound() throws Exception {
            // The reference never evaluates the right operand of `false and X` / `true or X`.
            assertEquals("false", json("false and $error(\"x\")"));
            assertEquals("true", json("true or $error(\"x\")"));
        }
    }

    // =====================================================================================
    // J-5 — a constant fold must never produce Infinity or NaN
    // =====================================================================================

    @Nested
    class NoNonFiniteConstantFolds {

        @Test
        void overflowingAdditionCompiles() throws Exception {
            // Used to fail compilation with "cannot find symbol Infin" from `number(Infinity)`.
            assertEquals("true", json("1e308 + 1e308 > 1"));
        }

        @Test
        void stringOfAnOverflowIsD3001() {
            assertEquals("D3001", errorCode("$string(1e308 + 1e308)"));
        }
    }

    // =====================================================================================
    // J-6 / J-15 — group-by after a context binding
    // =====================================================================================

    @Nested
    class GroupByAfterContextBinding {

        @Test
        void boundVariableIsVisibleInTheKeyExpression() throws Exception {
            // Used to fail compilation: "cannot find symbol: variable $i".
            assertEquals("{\"a\":1,\"b\":2}", json("items@$i{$i.k: $i.v}",
                    "{\"items\":[{\"k\":\"a\",\"v\":1},{\"k\":\"b\",\"v\":2}]}"));
        }

        /** J-15: the merge of per-iteration objects must not mutate the input document. */
        @Test
        void mergingGroupsDoesNotMutateTheInput() throws Exception {
            String source = "{\"items\":[{\"v\":[1,2]},{\"v\":[3]}]}";
            JsonNode input = MAPPER.readTree(source);
            assertEquals("{\"k\":[1,2,3]}", json("items@$i{\"k\": $i.v}", input));
            assertEquals(MAPPER.readTree(source), input, "the input document was modified in place");
        }
    }

    // =====================================================================================
    // J-7 — a descending order-by is a stable sort on the inverted comparison
    // =====================================================================================

    @Nested
    class DescendingSortIsStable {

        @Test
        void tiesKeepTheirInputOrder() throws Exception {
            assertEquals("[\"x\",\"y\"]", json("items^(>a).n",
                    "{\"items\":[{\"a\":1,\"n\":\"x\"},{\"a\":1,\"n\":\"y\"}]}"));
        }

        @Test
        void aLowerPriorityKeyKeepsItsOwnDirection() throws Exception {
            assertEquals("[5,1,2]", json("items^(>a, b).b",
                    "{\"items\":[{\"a\":1,\"b\":1},{\"a\":1,\"b\":2},{\"a\":2,\"b\":5}]}"));
        }

        @Test
        void descendingStillOrdersByTheKey() throws Exception {
            assertEquals("[{\"a\":2,\"n\":\"z\"},{\"a\":1,\"n\":\"x\"},{\"a\":1,\"n\":\"y\"}]",
                    json("items^(>a)",
                            "{\"items\":[{\"a\":1,\"n\":\"x\"},{\"a\":1,\"n\":\"y\"},{\"a\":2,\"n\":\"z\"}]}"));
        }

        @Test
        void anAbsentKeyStillSortsLastWhenDescending() throws Exception {
            assertEquals("[2,1,null]", json("items^(>a).n",
                    "{\"items\":[{\"n\":1,\"a\":1},{\"n\":null},{\"n\":2,\"a\":2}]}"));
        }
    }

    // =====================================================================================
    // J-8 / J-9 — $string of a container
    // =====================================================================================

    @Nested
    class ContainerSerialisation {

        @Test
        void numbersUseEcmaScriptRendering() throws Exception {
            assertEquals("\"{\\\"a\\\":0.3}\"", json("$string({\"a\": 0.1 + 0.2})"));
            assertEquals("\"{\\\"a\\\":1e+21}\"", json("$string({\"a\": 1e21})"));
            assertEquals("\"[1e+21,0.3]\"", json("$string([1e21, 0.1+0.2])"));
        }

        @Test
        void concatenationOfAContainerUsesTheSameRendering() throws Exception {
            assertEquals("\"x{\\\"a\\\":0.3}\"", json("\"x\" & {\"a\": 0.1+0.2}"));
        }

        @Test
        void prettyPrintDoesNotRewriteStringValues() throws Exception {
            assertEquals("{\n  \"a\": \"x : y\"\n}",
                    eval("$string({\"a\": \"x : y\"}, true)").textValue());
        }

        @Test
        void prettyPrintMatchesJsonStringifyWithTwoSpaces() throws Exception {
            assertEquals("{\n  \"a\": []\n}", eval("$string({\"a\": []}, true)").textValue());
            assertEquals("{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": {\n    \"c\": {}\n  }\n}",
                    eval("$string({\"a\": [1,2], \"b\": {\"c\": {}}}, true)").textValue());
        }

        @Test
        void nonFiniteInsideAContainerIsD1001() {
            assertEquals("D1001", errorCode("$string({\"a\": 1e308 * 10})"));
        }
    }
}
