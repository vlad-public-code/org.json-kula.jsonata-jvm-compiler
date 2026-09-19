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

    private static String errorCode(String expression, String inputJson) throws Exception {
        return errorCode(expression, MAPPER.readTree(inputJson));
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

    /**
     * J-10: a call with several arguments packs them into a tuple, and the callee spreads a
     * tuple over its parameters. A plain array passed as the single argument is not a tuple, so
     * it binds whole to the first parameter — before the {@code PackedArgs} marker the callee
     * could not tell the two apart and mis-unpacked every array-first function.
     *
     * <p>The runtime's own higher-order callback tuples carry the same marker, so the cases below
     * also pin that {@code $map}/{@code $reduce}/{@code $sort}/{@code $sift}/{@code $each}/
     * {@code $single}/{@code $filter} still spread their arguments.
     */
    @Nested
    class J10MultiParameterUnpacking {

        @Test
        void singleArrayArgumentBindsWholeToTheFirstParameter() throws Exception {
            assertEquals("ab", eval(
                    "( $f := function($arr, $sep){ $join($arr, $sep) }; $f([\"a\",\"b\"]) )")
                    .textValue());
        }

        @Test
        void twoArgumentsStillSpread() throws Exception {
            assertEquals("a-b", eval(
                    "( $f := function($arr, $sep){ $join($arr, $sep) }; $f([\"a\",\"b\"], \"-\") )")
                    .textValue());
        }

        @Test
        void aSecondParameterIsAbsentWhenOnlyAnArrayWasPassed() throws Exception {
            assertEquals("[1,2]", json("( $f := function($a,$b){ [$a,$b] }; $f([1,2]) )"));
        }

        @Test
        void mapCallbackStillReceivesValueIndexAndArray() throws Exception {
            assertEquals("[[1,0,2],[2,1,2]]",
                    json("$map([1,2], function($v,$i,$a){ [$v,$i,$count($a)] })"));
        }

        @Test
        void reduceCallbackStillReceivesTheAccumulator() throws Exception {
            assertEquals("6", json("$reduce([1,2,3], function($acc,$v){ $acc+$v })"));
        }

        @Test
        void sortComparatorStillReceivesBothOperands() throws Exception {
            assertEquals("[1,2,3]", json("$sort([3,1,2], function($a,$b){ $a > $b })"));
        }

        @Test
        void siftAndEachStillReceiveValueAndKey() throws Exception {
            assertEquals("{\"b\":2}", json("$sift({\"a\":1,\"b\":2}, function($v,$k){ $k=\"b\" })"));
            assertEquals("\"a1\"", json("$each({\"a\":1}, function($v,$k){ $k & $v })"));
        }

        @Test
        void singleAndFilterStillReceiveTheIndex() throws Exception {
            assertEquals("2", json("$single([1,2,3], function($v,$i){ $i=1 })"));
            assertEquals("[2,3]",
                    json("$filter([1,2,3], function($v,$i,$a){ $i > 0 and $count($a)=3 })"));
        }
    }

    /**
     * J-12: {@code seq[expr]} picks index-mode or filter-mode per element, not once for the
     * whole sequence by probing the predicate with an absent context.
     */
    @Nested
    class J12PerElementFilterMode {

        private static final String ARR = "{\"arr\":[{\"a\":0},{\"a\":1},{\"a\":5}]}";

        @Test
        void aNumericResultSelectsByIndexPerElement() throws Exception {
            // Keeps the elements whose `a` equals their own position, not the truthy ones.
            assertEquals("[{\"a\":0},{\"a\":1}]", json("$.arr[a]", ARR));
        }

        @Test
        void aBooleanResultStillFilters() throws Exception {
            assertEquals("[{\"a\":1},{\"a\":5}]", json("$.arr[a>0]", ARR));
        }

        @Test
        void noPositionMatchYieldsNothing() throws Exception {
            assertEquals("**undefined**", json("$.arr[a-1]", ARR));
        }

        @Test
        void aNegativeIndexCountsFromTheEnd() throws Exception {
            assertEquals("{\"a\":0}", json("$.arr[a][0]", ARR));
        }

        @Test
        void aLiteralSubscriptIsStillASubscript() throws Exception {
            assertEquals("{\"a\":0}", json("arr[0]", ARR));
        }
    }

    /** J-16: a negated numeric literal is an operand, so postfix and chain steps follow it. */
    @Nested
    class J16NegativeLiteralKeepsParsing {

        @Test
        void chainStepAfterANegativeLiteral() throws Exception {
            assertEquals(3, eval("-3 ~> $abs").intValue());
        }

        @Test
        void plainNegativeLiteralIsUnchanged() throws Exception {
            assertEquals(-3, eval("-3").intValue());
        }

        @Test
        void arithmeticOnANegativeLiteralIsUnchanged() throws Exception {
            assertEquals(-1, eval("-3 + 2").intValue());
        }
    }

    /** J-18: a radix literal wider than 64 bits is a finite double, not a raw parse failure. */
    @Nested
    class J18WideRadixLiterals {

        @Test
        void aHexLiteralWiderThanALongParses() throws Exception {
            assertEquals(4.722366482869645e21,
                    eval("$number(\"0xFFFFFFFFFFFFFFFFFF\")").doubleValue(), 0.0);
        }

        @Test
        void ordinaryRadixLiteralsAreUnchanged() throws Exception {
            assertEquals(511, eval("$number(\"0o777\")").intValue());
            assertEquals(255, eval("$number(\"0xff\")").intValue());
        }
    }

    /** J-19: `in` compares with strict equality, so a container on the left never matches. */
    @Nested
    class J19InUsesStrictEquality {

        @Test
        void anArrayOnTheLeftNeverMatches() throws Exception {
            assertFalse(eval("[1,2] in [[1,2],[3]]").booleanValue());
        }

        @Test
        void anObjectOnTheLeftNeverMatches() throws Exception {
            assertFalse(eval("{\"a\":1} in [{\"a\":1}]").booleanValue());
        }

        @Test
        void scalarsStillMatchByValue() throws Exception {
            assertTrue(eval("1 in [1,2]").booleanValue());
            assertTrue(eval("\"a\" in [\"a\",\"b\"]").booleanValue());
            assertFalse(eval("3 in [1,2]").booleanValue());
        }
    }

    /**
     * J-21: a deferred error's code and message are emitted with the shared Java string escaper.
     *
     * <p>No expression reaches it with a character that needs escaping today — the message only
     * ever names a built-in — so this pins the behaviour of the path rather than a former crash.
     */
    @Nested
    class J21DeferredErrorEscaping {

        @Test
        void aBuiltinCalledWithoutItsDollarRaisesT1005WhenReached() {
            assertEquals("T1005", errorCode("count([1,2])"));
        }

        @Test
        void andIsNotRaisedWhenControlNeverReachesIt() throws Exception {
            assertEquals(1, eval("false ? count([1,2]) : 1").intValue());
        }
    }

    /**
     * J-17: every built-in is usable as a function value, not the two dozen someone had listed
     * in a hand-maintained wrapper map. The wrapper is derived from the declared signature.
     *
     * <p>Its arity counts required parameters only. The reference instead uses the
     * implementation's full parameter count, which shows up in one corner: a higher-order
     * built-in passes the element index into an optional slot, so the reference's
     * {@code $map([1.234,5.678], $round)} is {@code [1,5.7]} (precision 0 then 1) where this port
     * gives {@code [1,6]}. Matching that would mean feeding the index into {@code $string}'s
     * prettify flag too; required-only arity is the reading that keeps the common cases right.
     */
    @Nested
    class J17BuiltinsAsFunctionValues {

        @Test
        void abs() throws Exception {
            assertEquals("[1.5,2.5]", json("$map([1.5,-2.5], $abs)"));
            assertEquals(3, eval("-3 ~> $abs").intValue());
        }

        @Test
        void floorAndTrim() throws Exception {
            assertEquals("[1,2]", json("$map([1.7,2.2], $floor)"));
            assertEquals("\"x\"", json("$map([\" x \"], $trim)"));
        }

        @Test
        void aTwoParameterBuiltinAsAReducer() throws Exception {
            assertEquals(8, eval("$reduce([2,3], $power)").intValue());
        }

        @Test
        void theBuiltinsThatAlreadyWorkedStillDo() throws Exception {
            assertEquals("[\"1\",\"2\",\"3\"]", json("$map([1,2,3], $string)"));
            assertEquals("[1,2,3]", json("$filter([1,2,3], $boolean)"));
            assertEquals("[[1,0],[2,1]]", json("$map([1,2], $append)"));
        }
    }

    /** J-13: a chain step written as a call is invoked, not composed. */
    @Nested
    class J13ChainCallInvokes {

        @Test
        void callStepOnAFunctionValueInvokes() throws Exception {
            assertEquals("function",
                    eval("( $f := function($x){$x}; $f ~> $type() )").textValue());
            assertEquals("function", eval("$trim ~> $type()").textValue());
        }

        @Test
        void valueStepWithoutParenthesesStillComposes() throws Exception {
            assertEquals("HI", eval("( $f := $trim ~> $uppercase; $f(\"  hi  \") )").textValue());
        }

        @Test
        void ordinaryChainIsUnaffected() throws Exception {
            assertEquals("HI", eval("\"  hi  \" ~> $trim() ~> $uppercase()").textValue());
        }
    }

    /** J-14: the delete clause is evaluated against each matched node, in that node's context. */
    @Nested
    class J14TransformDeletePerMatch {

        @Test
        void deleteClauseSeesTheMatchedNode() throws Exception {
            assertEquals("{\"a\":{\"del\":\"k\"}}",
                    json("$ ~> |a|{}, del|", "{\"a\":{\"k\":1,\"del\":\"k\"}}"));
        }

        @Test
        void arrayOfNamesDeletesEach() throws Exception {
            assertEquals("{\"a\":{\"w\":3}}",
                    json("$ ~> |a|{}, [\"y\",\"z\"]|", "{\"a\":{\"y\":1,\"z\":2,\"w\":3}}"));
        }

        @Test
        void anAbsentDeleteResultIsANoOp() throws Exception {
            assertEquals("{\"a\":{\"y\":1}}",
                    json("$ ~> |a|{}, \"nope\"|", "{\"a\":{\"y\":1}}"));
        }

        @Test
        void aNonStringElementIsT2012() throws Exception {
            assertEquals("T2012", errorCode("$ ~> |a|{}, [1]|", "{\"a\":{\"y\":1}}"));
        }

        @Test
        void aTransformWithoutADeleteClauseStillWorks() throws Exception {
            assertEquals("{\"a\":{\"y\":1,\"x\":2}}",
                    json("$ ~> |a|{\"x\":2}|", "{\"a\":{\"y\":1}}"));
        }
    }

    /**
     * J-20: the comparator sort is the reference's merge sort, so a user comparator that is not
     * a consistent ordering cannot trip TimSort's contract check.
     */
    @Nested
    class J20ComparatorSort {

        @Test
        void anOrdinaryComparatorStillSorts() throws Exception {
            assertEquals("[1,2,3]", json("$sort([3,1,2], function($a,$b){ $a > $b })"));
        }

        @Test
        void equalElementsKeepInputOrder() throws Exception {
            assertEquals("[{\"n\":\"a\",\"p\":1},{\"n\":\"b\",\"p\":1},{\"n\":\"c\",\"p\":0}]",
                    json("$sort($, function($a,$b){ $a.p < $b.p })",
                            "[{\"n\":\"a\",\"p\":1},{\"n\":\"b\",\"p\":1},{\"n\":\"c\",\"p\":0}]"));
        }

        @Test
        void anInconsistentComparatorDoesNotThrow() throws Exception {
            // Always "after": TimSort rejected this as a contract violation with a code-less
            // error. A merge sort simply asks each pair once and returns an order.
            JsonNode r = eval("$sort([1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,"
                    + "23,24,25,26,27,28,29,30,31,32,33], function($a,$b){ true })");
            assertTrue(r.isArray());
            assertEquals(33, r.size());
        }
    }

    /** J-22: three smaller divergences from the reference. */
    @Nested
    class J22MinorDivergences {

        @Test
        void mergeRejectsANonObjectElement() {
            assertEquals("T0412", errorCode("$merge([1, {\"a\":1}])"));
        }

        @Test
        void mergeStillMergesObjects() throws Exception {
            assertEquals("{\"a\":1,\"b\":2}", json("$merge([{\"a\":1}, {\"b\":2}])"));
        }

        @Test
        void lookupRejectsANonStringKey() {
            assertEquals("T0410", errorCode("$lookup({\"a\":1}, 1)"));
        }

        @Test
        void lookupStillLooksUp() throws Exception {
            assertEquals(1, eval("$lookup({\"a\":1}, \"a\")").intValue());
        }

        @Test
        void aComposedFunctionStillHitsTheRecursionLimit() {
            // Composition used to call the two lambdas directly, skipping the depth check.
            assertEquals("U1001", errorCode(
                    "( $f := function($n){ $n = 0 ? 0 : ($g := $f ~> $number; $g($n - 1)) }; $f(2000) )"));
        }

        @Test
        void compositionStillComposes() throws Exception {
            assertEquals("HI", eval("( $f := $trim ~> $uppercase; $f(\"  hi  \") )").textValue());
        }
    }
}
