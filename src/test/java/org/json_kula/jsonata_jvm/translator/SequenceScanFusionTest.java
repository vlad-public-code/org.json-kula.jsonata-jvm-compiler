package org.json_kula.jsonata_jvm.translator;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.loader.JsonataExpressionLoader;
import org.json_kula.jsonata_jvm.optimizer.Optimizer;
import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SequenceScanFusion}.
 *
 * <p>The pass is an optimisation, so almost every test here is a same-answer test: the fused form
 * must return exactly what the unfused statements returned, including for the awkward inputs —
 * a missing sequence, a single object where an array was expected, elements that are not objects,
 * and a filter that selects nothing or exactly one element.
 *
 * <p>The tests that assert on the <em>generated source</em> are the ones that pin down when the
 * pass must decline: fusing something that would not otherwise have run, or reading a variable
 * that has since been rebound, is a correctness bug that a same-answer test would only catch by
 * luck of the input.
 */
class SequenceScanFusionTest {

    private static final JsonataExpressionLoader LOADER = new JsonataExpressionLoader();
    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

    private static String nextClass() {
        return "FusionGen" + CLASS_COUNTER.incrementAndGet();
    }

    private static String source(String expr) throws Exception {
        return Translator.translate(Optimizer.optimize(Parser.parse(expr)), "test.gen", nextClass());
    }

    private static JsonNode eval(String expr, String json) throws Exception {
        JsonataExpression compiled = LOADER.load(source(expr));
        return compiled.evaluate(JsonNodeTestHelper.parseJson(json));
    }

    /** True when the expression compiled to at least one fused scan. */
    private static boolean fused(String expr) throws Exception {
        return source(expr).contains("private static JsonNode[] __scan");
    }

    /** Compares two JSON values, ignoring the Jackson node subclass a number landed in. */
    private static void assertJsonEquals(JsonNode expected, JsonNode actual) {
        JsonNodeTestHelper.assertJsonEquals(expected, actual, "fused result differs");
    }

    private static final String STAFF = """
            { "e": [
              { "name": "a", "salary": 10, "level": "senior" },
              { "name": "b", "salary": 30, "level": "junior" },
              { "name": "c", "salary": 20, "level": "senior" }
            ] }""";

    // -------------------------------------------------------------------------
    // Same answers
    // -------------------------------------------------------------------------

    @Test
    void aggregatesSharingAFieldFuseAndAgree() throws Exception {
        String expr = """
                ( $s := e;
                  { "sum": $sum($s.salary), "avg": $average($s.salary),
                    "max": $max($s.salary), "min": $min($s.salary) } )""";
        assertTrue(fused(expr), "four aggregates over one field should fuse");
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"sum\":60,\"avg\":20,\"max\":30,\"min\":10}"),
                eval(expr, STAFF));
    }

    @Test
    void countsAndFiltersOverOneSequenceFuse() throws Exception {
        String expr = """
                ( $s := e;
                  $seniors := $s[level = "senior"];
                  { "senior": $count($s[level = "senior"]),
                    "junior": $count($s[level = "junior"]),
                    "names": $seniors.name } )""";
        assertTrue(fused(expr));
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"senior\":2,\"junior\":1,\"names\":[\"a\",\"c\"]}"),
                eval(expr, STAFF));
    }

    @Test
    void aggregatesAndCountsMix() throws Exception {
        String expr = """
                ( $s := e;
                  { "total": $sum($s.salary), "top": $max($s.salary),
                    "senior": $count($s[level = "senior"]) } )""";
        assertTrue(fused(expr));
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"total\":60,\"top\":30,\"senior\":2}"),
                eval(expr, STAFF));
    }

    @Test
    void fusedResultFeedsAnEnclosingCall() throws Exception {
        // The scan-shaped call is an argument, not the whole bound value.
        String expr = """
                ( $s := e;
                  { "avg": $round($average($s.salary), 1), "sum": $sum($s.salary),
                    "max": $max($s.salary) } )""";
        assertTrue(fused(expr));
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"avg\":20,\"sum\":60,\"max\":30}"),
                eval(expr, STAFF));
    }

    // -------------------------------------------------------------------------
    // Sequence shapes
    // -------------------------------------------------------------------------

    @Test
    void singleObjectIsScannedAsAOneElementSequence() throws Exception {
        String expr = """
                ( $s := e;
                  { "sum": $sum($s.salary), "max": $max($s.salary),
                    "senior": $count($s[level = "senior"]), "picked": $s[level = "senior"] } )""";
        assertJsonEquals(
                JsonNodeTestHelper.parseJson(
                        "{\"sum\":10,\"max\":10,\"senior\":1,"
                        + "\"picked\":{\"salary\":10,\"level\":\"senior\"}}"),
                eval(expr, "{ \"e\": { \"salary\": 10, \"level\": \"senior\" } }"));
    }

    @Test
    void missingSequenceYieldsWhatEachOperationYieldsAlone() throws Exception {
        // $sum/$max of nothing is undefined and drops its key; $count of nothing is 0.
        String expr = """
                ( $s := nothingHere;
                  { "sum": $sum($s.salary), "max": $max($s.salary),
                    "senior": $count($s[level = "senior"]) } )""";
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"senior\":0}"), eval(expr, "{ \"e\": [] }"));
    }

    @Test
    void scalarElementsContributeNothingToEitherHalf() throws Exception {
        String expr = """
                ( $s := e;
                  { "sum": $sum($s.salary), "max": $max($s.salary),
                    "senior": $count($s[level = "senior"]) } )""";
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"sum\":10,\"max\":10,\"senior\":1}"),
                eval(expr, "{ \"e\": [ 7, \"x\", { \"salary\": 10, \"level\": \"senior\" } ] }"));
    }

    @Test
    void anElementThatIsItselfAnArrayIsNavigatedIntoByBothHalves() throws Exception {
        // Both an aggregate over $s.salary and a predicate on $s apply a field step per element,
        // and a step maps over an array element rather than skipping it. So one read serves both —
        // but only because both navigate this way: gating the aggregates on isObject makes $sum
        // disagree with the $s.salary its own argument spells out.
        String json = """
                { "e": [ { "salary": 10, "level": "senior" },
                         [ { "salary": 3, "level": "senior" } ] ] }""";
        String expr = """
                ( $s := e;
                  { "sum": $sum($s.salary), "min": $min($s.salary), "path": $s.salary,
                    "senior": $count($s[level = "senior"]) } )""";
        assertTrue(fused(expr));
        assertJsonEquals(
                JsonNodeTestHelper.parseJson("{\"sum\":13,\"min\":3,\"path\":[10,3],\"senior\":2}"),
                eval(expr, json));
    }

    @Test
    void filterKeepsSingletonAndEmptySemantics() throws Exception {
        String expr = """
                ( $s := e;
                  $one  := $s[level = "junior"];
                  $none := $s[level = "lead"];
                  $many := $s[level = "senior"];
                  { "one": $one, "oneIsArray": $type($one) = "array",
                    "noneMissing": $exists($none), "many": $count($many) } )""";
        assertTrue(fused(expr));
        assertJsonEquals(JsonNodeTestHelper.parseJson(
                        "{\"one\":{\"name\":\"b\",\"salary\":30,\"level\":\"junior\"},"
                        + "\"oneIsArray\":false,\"noneMissing\":false,\"many\":2}"),
                eval(expr, STAFF));
    }

    @Test
    void fieldHoldingAnArrayIsSummedElementwise() throws Exception {
        String expr = "( $s := e; { \"sum\": $sum($s.v), \"max\": $max($s.v) } )";
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"sum\":10,\"max\":4}"),
                eval(expr, "{ \"e\": [ { \"v\": [1, 2] }, { \"v\": [3, 4] } ] }"));
    }

    // -------------------------------------------------------------------------
    // Error reporting
    // -------------------------------------------------------------------------

    @Test
    void theEarliestBoundAggregateReportsTheError() throws Exception {
        // Unfused, $sum runs first and fails; the fused scan meets $max's bad value at an earlier
        // element but must still report $sum's failure.
        String json = """
                { "e": [ { "a": "bad", "b": 1 }, { "a": 1, "b": "bad" } ] }""";
        JsonataEvaluationException error = assertThrows(JsonataEvaluationException.class,
                () -> eval("( $s := e; { \"x\": $sum($s.b), \"y\": $max($s.a) } )", json));
        assertTrue(error.getMessage().contains("$sum"),
                "expected the $sum failure, got: " + error.getMessage());
    }

    @Test
    void averageReportsItsOwnName() throws Exception {
        String json = "{ \"e\": [ { \"a\": \"bad\" } ] }";
        JsonataEvaluationException error = assertThrows(JsonataEvaluationException.class,
                () -> eval("( $s := e; { \"x\": $average($s.a), \"y\": $sum($s.a) } )", json));
        assertTrue(error.getMessage().contains("$average"),
                "expected the $average failure, got: " + error.getMessage());
    }

    @Test
    void goodDataDoesNotTripTheDeferredErrorCheck() throws Exception {
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"sum\":60,\"max\":30}"),
                eval("( $s := e; { \"sum\": $sum($s.salary), \"max\": $max($s.salary) } )", STAFF));
    }

    // -------------------------------------------------------------------------
    // When the pass must decline
    // -------------------------------------------------------------------------

    @Test
    void aReboundSequenceIsNotFused() throws Exception {
        String expr = """
                ( $s := e;
                  $a := $sum($s.salary);
                  $s := e[level = "senior"];
                  $b := $max($s.salary);
                  $c := $min($s.salary);
                  { "a": $a, "b": $b, "c": $c } )""";
        assertFalse(fused(expr), "a rebound sequence variable must not be scanned once");
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"a\":60,\"b\":20,\"c\":10}"), eval(expr, STAFF));
    }

    @Test
    void operationsInsideAConditionalBranchAreNotHoisted() throws Exception {
        // Hoisting the $sum out of the untaken branch would raise T0412 on data the expression
        // never actually looks at.
        String expr = """
                ( $s := e;
                  $n := $count($s);
                  $n > 100 ? $sum($s.bad) + $max($s.bad) + $min($s.bad) : "skipped" )""";
        assertEquals("skipped",
                eval(expr, "{ \"e\": [ { \"bad\": \"x\" }, { \"bad\": \"y\" } ] }").textValue());
    }

    @Test
    void operationsInsideALambdaAreNotHoisted() throws Exception {
        String expr = """
                ( $s := e;
                  $f := function($ignored) { $sum($s.salary) + $max($s.salary) + $min($s.salary) };
                  $f(1) )""";
        assertEquals(100, eval(expr, STAFF).asInt());
    }

    @Test
    void aShadowedBuiltinIsNotTreatedAsAnAggregate() throws Exception {
        String expr = """
                ( $s := e;
                  $sum := function($x) { 99 };
                  $max := function($x) { 7 };
                  { "a": $sum($s.salary), "b": $max($s.salary), "c": $min($s.salary) } )""";
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"a\":99,\"b\":7,\"c\":10}"), eval(expr, STAFF));
    }

    @Test
    void aLoneOperationIsLeftAlone() throws Exception {
        assertFalse(fused("( $s := e; $sum($s.salary) )"),
                "one operation has nothing to share a pass with");
    }

    @Test
    void twoOperationsOnDifferentFieldsAreLeftAlone() throws Exception {
        // Fusing these would save a loop but no field read — not worth the helper method.
        assertFalse(fused("( $s := e; { \"a\": $sum($s.salary), \"b\": $max($s.other) } )"));
    }

    @Test
    void twoOperationsOnTheSameFieldAreWorthFusing() throws Exception {
        assertTrue(fused("( $s := e; { \"a\": $sum($s.salary), \"b\": $max($s.salary) } )"));
    }

    @Test
    void aSequenceBoundOutsideTheBlockIsLeftAlone() throws Exception {
        // The scan is hoisted within one block; a name this block does not bind is not its to move.
        assertFalse(fused("""
                ( $outer := e;
                  ( $x := 1; { "a": $sum($outer.salary), "b": $max($outer.salary) } ) )"""));
    }

    // -------------------------------------------------------------------------
    // Interaction with the surrounding language
    // -------------------------------------------------------------------------

    @Test
    void fusionSurvivesNestedBlocksOverDifferentSequences() throws Exception {
        String expr = """
                ( $a := e;
                  $inner := ( $b := e[level = "senior"];
                              { "s": $sum($b.salary), "m": $max($b.salary) } );
                  { "outer": $sum($a.salary), "outerMax": $max($a.salary), "inner": $inner } )""";
        assertJsonEquals(JsonNodeTestHelper.parseJson(
                        "{\"outer\":60,\"outerMax\":30,\"inner\":{\"s\":30,\"m\":20}}"),
                eval(expr, STAFF));
    }

    @Test
    void aFusedFilterResultIsUsableAsASequenceItself() throws Exception {
        String expr = """
                ( $s := e;
                  $seniors := $s[level = "senior"];
                  $juniors := $s[level = "junior"];
                  { "sSum": $sum($seniors.salary), "sMax": $max($seniors.salary),
                    "jCount": $count($juniors) } )""";
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"sSum\":30,\"sMax\":20,\"jCount\":1}"),
                eval(expr, STAFF));
    }

    @Test
    void countComparesAgainstEachLiteralType() throws Exception {
        String json = """
                { "e": [ { "s": "x", "n": 1, "b": true,  "z": null },
                         { "s": "y", "n": 2, "b": false, "z": 1 },
                         { "s": "x", "n": 1, "b": true,  "z": null } ] }""";
        String expr = """
                ( $s := e;
                  { "str": $count($s[s = "x"]), "num": $count($s[n = 1]),
                    "bool": $count($s[b = true]), "null": $count($s[z = null]),
                    "again": $count($s[s = "y"]) } )""";
        assertTrue(fused(expr));
        assertJsonEquals(JsonNodeTestHelper.parseJson(
                        "{\"str\":2,\"num\":2,\"bool\":2,\"null\":2,\"again\":1}"),
                eval(expr, json));
    }

    @Test
    void aMissingFieldIsNotCountedAsAMatch() throws Exception {
        String expr = """
                ( $s := e;
                  { "x": $count($s[level = "senior"]), "y": $count($s[level = "junior"]),
                    "sum": $sum($s.salary) } )""";
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"x\":1,\"y\":0,\"sum\":10}"),
                eval(expr, "{ \"e\": [ { \"salary\": 10, \"level\": \"senior\" }, { \"other\": 1 } ] }"));
    }

    // -------------------------------------------------------------------------
    // Predicates beyond field = literal
    // -------------------------------------------------------------------------

    @Test
    void inequalityAndJunctionPredicatesAreAbsorbed() throws Exception {
        String expr = """
                ( $s := e;
                  { "notSenior": $count($s[level != "senior"]),
                    "seniorTen": $count($s[level = "senior" and salary = 10]),
                    "either":    $count($s[level = "junior" or salary = 20]),
                    "nested":    $count($s[(level = "senior" and salary = 10) or level = "junior"]),
                    "picked":    $s[level != "senior"].name } )""";
        assertTrue(fused(expr));
        assertJsonEquals(JsonNodeTestHelper.parseJson(
                        "{\"notSenior\":1,\"seniorTen\":1,\"either\":2,\"nested\":2,\"picked\":\"b\"}"),
                eval(expr, STAFF));
    }

    @Test
    void aJunctionOverTwoFieldsStillReadsEachFieldOnce() throws Exception {
        // The point of the pass: four operations mentioning `level` between them, one read of it.
        String generated = source("""
                ( $s := e;
                  { "a": $count($s[level = "senior" and salary = 10]),
                    "b": $count($s[level = "junior" or salary = 20]),
                    "c": $count($s[level != "senior"]),
                    "d": $sum($s.salary) } )""");
        int scan = generated.indexOf("private static JsonNode[] __scan");
        assertTrue(scan >= 0, "expected a fused scan");
        String body = generated.substring(scan);
        assertEquals(1, countOccurrences(body, "field(__e, \"level\")"));
        assertEquals(1, countOccurrences(body, "field(__e, \"salary\")"));
    }

    @Test
    void orderingPredicatesAreAbsorbed() throws Exception {
        String expr = """
                ( $s := e;
                  { "ge": $count($s[salary >= 20]), "gt": $count($s[salary > 20]),
                    "lt": $count($s[salary < 20]), "le": $count($s[salary <= 20]),
                    "byName": $count($s[level >= "s"]), "picked": $s[salary > 25].name } )""";
        assertTrue(fused(expr));
        assertJsonEquals(JsonNodeTestHelper.parseJson(
                        "{\"ge\":2,\"gt\":1,\"lt\":1,\"le\":2,\"byName\":2,\"picked\":\"b\"}"),
                eval(expr, STAFF));
    }

    @Test
    void anOrderingComparisonUnderAJunctionIsDeclined() throws Exception {
        // Its error would have to be deferred to the read, which a compound predicate cannot
        // express — so that operation stays a pass of its own, and the answer is unchanged.
        String expr = """
                ( $s := e;
                  { "a": $count($s[salary >= 20 and level = "senior"]),
                    "b": $count($s[level = "senior"]), "c": $sum($s.salary) } )""";
        assertJsonEquals(JsonNodeTestHelper.parseJson("{\"a\":1,\"b\":2,\"c\":60}"), eval(expr, STAFF));
    }

    @Test
    void anOrderingErrorIsRaisedWhereTheComparisonWas() throws Exception {
        JsonataEvaluationException error = assertThrows(JsonataEvaluationException.class,
                () -> eval("""
                        ( $s := e;
                          { "a": $count($s[salary >= "x"]), "b": $count($s[level = "senior"]),
                            "c": $sum($s.salary) } )""", STAFF));
        assertTrue(error.getMessage().contains("ordering operator"),
                "expected the ordering failure, got: " + error.getMessage());
    }

    @Test
    void twoOrderingComparisonsKeepTheirOwnOperandSlots() throws Exception {
        String expr = """
                ( $s := e;
                  $byPay   := $count($s[salary >= 20]);
                  $byLevel := $count($s[level >= "s"]);
                  $all     := $sum($s.salary);
                  [$byPay, $byLevel, $all] )""";
        assertTrue(fused(expr));
        assertJsonEquals(JsonNodeTestHelper.parseJson("[2,2,60]"), eval(expr, STAFF));
    }

    @Test
    void theEarliestBoundOrderingComparisonReportsTheError() throws Exception {
        // Both fail, with different errors, and neither fails on the scan's first element: the one
        // bound first is the one that reports, exactly as two separate passes would have.
        String expr = """
                ( $s := e;
                  $a := $count($s[level >= true]);
                  $b := $count($s[salary >= "x"]);
                  $c := $sum($s.salary);
                  [$a, $b, $c] )""";
        assertTrue(fused(expr));
        JsonataEvaluationException error =
                assertThrows(JsonataEvaluationException.class, () -> eval(expr, STAFF));
        assertTrue(error.getMessage().contains("numeric or string values"),
                "expected the T2010 failure of the first comparison, got: " + error.getMessage());
    }

    @Test
    void twoOperationsSharingAFieldThroughAPredicateAreWorthFusing() throws Exception {
        assertTrue(fused("( $s := e; { \"a\": $sum($s.salary), \"b\": $count($s[salary = 10]) } )"));
        assertFalse(fused("( $s := e; { \"a\": $sum($s.salary), \"b\": $count($s[level = \"x\"]) } )"));
    }

    // -------------------------------------------------------------------------
    // An absorbed error does not outrun the statements the scan did not absorb
    // -------------------------------------------------------------------------

    @Test
    void anAbsorbedAggregateErrorDoesNotOutrunAnUnabsorbedStatement() throws Exception {
        // The scan runs at the group's first use, but $sum's failure belongs to the statement that
        // bound it — which is after $error, so $error is what the expression reports.
        String json = "{ \"e\": [ { \"s\": 10 }, { \"s\": \"bad\" } ] }";
        String expr = """
                ( $e := e;
                  $a := $count($e[s = 10]);
                  $z := $error("boom");
                  $b := $sum($e.s);
                  $c := $max($e.s);
                  $b )""";
        assertTrue(fused(expr));
        JsonataEvaluationException error =
                assertThrows(JsonataEvaluationException.class, () -> eval(expr, json));
        assertTrue(error.getMessage().contains("boom"),
                "expected the $error, got: " + error.getMessage());
    }

    @Test
    void anAbsorbedOrderingErrorDoesNotOutrunAnUnabsorbedStatement() throws Exception {
        String expr = """
                ( $s := e;
                  $a := $count($s[level = "senior"]);
                  $z := $error("boom");
                  $b := $count($s[salary >= "x"]);
                  $c := $sum($s.salary);
                  $b )""";
        assertTrue(fused(expr));
        JsonataEvaluationException error =
                assertThrows(JsonataEvaluationException.class, () -> eval(expr, STAFF));
        assertTrue(error.getMessage().contains("boom"),
                "expected the $error, got: " + error.getMessage());
    }

    @Test
    void anAbsorbedAggregateStillFailsAtItsOwnBindingWhenNothingReadsIt() throws Exception {
        String json = "{ \"e\": [ { \"s\": 10 }, { \"s\": \"bad\" } ] }";
        JsonataEvaluationException error = assertThrows(JsonataEvaluationException.class,
                () -> eval("""
                        ( $e := e;
                          $a := $sum($e.s);
                          $b := $count($e[s = 10]);
                          $c := $max($e.s);
                          $b )""", json));
        assertTrue(error.getMessage().contains("$sum"),
                "expected the $sum failure, got: " + error.getMessage());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
