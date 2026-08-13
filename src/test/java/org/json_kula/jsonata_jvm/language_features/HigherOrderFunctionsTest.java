package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the higher-order functions documented at
 * https://docs.jsonata.org/higher-order-functions
 *
 * <ul>
 *   <li>{@code $map} — single-param and three-param (value, index, array)</li>
 *   <li>{@code $filter} — single-param and three-param (value, index, array)</li>
 *   <li>{@code $reduce} — two-param accumulator function, with and without initial value</li>
 *   <li>{@code $single} — exactly one match, throws on zero or multiple matches</li>
 *   <li>{@code $sift} — one-param (value only) and two-param (value, key)</li>
 * </ul>
 */
class HigherOrderFunctionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonataExpressionFactory FACTORY;

    @BeforeAll
    static void setup() {
        FACTORY = new JsonataExpressionFactory();
    }

    private JsonNode eval(String expression) throws Exception {
        return FACTORY.compile(expression).evaluate(EMPTY_OBJECT);
    }

    private JsonNode eval(String expression, String json) throws Exception {
        return FACTORY.compile(expression).evaluate(MAPPER.readTree(json));
    }

    // =========================================================================
    // $map
    // =========================================================================

    @Test
    void map_singleParam_doublesEachElement() throws Exception {
        JsonNode result = eval("$map([1, 2, 3], function($v){ $v * 2 })");
        assertTrue(result.isArray());
        assertEquals(3, result.size());
        assertEquals(2,  result.get(0).intValue());
        assertEquals(4,  result.get(1).intValue());
        assertEquals(6,  result.get(2).intValue());
    }

    @Test
    void map_singleParam_singletonUnwrapped() throws Exception {
        // JSONata $map with a single-element input returns the single result unwrapped
        // (sequence semantics — a one-element sequence collapses to a scalar).
        JsonNode result = eval("$map([2], function($v){ $v * 3 })");
        assertTrue(result.isNumber());
        assertEquals(6, result.intValue());
    }

    @Test
    void map_threeParams_indexAndArrayAvailable() throws Exception {
        // From the docs: build "Item N of M: v" strings.
        // $map([1,2,3,4,5], function($v,$i,$a){ 'Item ' & ($i+1) & ' of ' & $count($a) & ': ' & $v })
        JsonNode result = eval(
                "$map([1,2,3,4,5], function($v, $i, $a){ 'Item ' & ($i+1) & ' of ' & $count($a) & ': ' & $v })");
        assertTrue(result.isArray());
        assertEquals(5, result.size());
        assertEquals("Item 1 of 5: 1", result.get(0).textValue());
        assertEquals("Item 3 of 5: 3", result.get(2).textValue());
        assertEquals("Item 5 of 5: 5", result.get(4).textValue());
    }

    @Test
    void map_twoParams_indexAvailable() throws Exception {
        // Lambda with (value, index) — index should be 0-based.
        JsonNode result = eval("$map([10, 20, 30], function($v, $i){ $i & ':' & $v })");
        assertTrue(result.isArray());
        assertEquals("0:10", result.get(0).textValue());
        assertEquals("1:20", result.get(1).textValue());
        assertEquals("2:30", result.get(2).textValue());
    }

    @Test
    void map_fieldNavigation_extractsNames() throws Exception {
        String json = "{\"products\":[{\"name\":\"Widget\",\"price\":5},"
                + "{\"name\":\"Gadget\",\"price\":10}]}";
        JsonNode result = eval("$map(products, function($v){ $v.name })", json);
        assertTrue(result.isArray());
        assertEquals("Widget", result.get(0).textValue());
        assertEquals("Gadget", result.get(1).textValue());
    }

    // =========================================================================
    // $filter
    // =========================================================================

    @Test
    void filter_singleParam_returnsMatchingElements() throws Exception {
        JsonNode result = eval("$filter([1, 2, 3, 4, 5], function($v){ $v > 3 })");
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals(4, result.get(0).intValue());
        assertEquals(5, result.get(1).intValue());
    }

    @Test
    void filter_singleParam_noMatchReturnsMissing() throws Exception {
        JsonNode result = eval("$filter([1, 2, 3], function($v){ $v > 10 })");
        // JSONata: empty result → undefined → null at top level
        assertTrue(result.isMissingNode(), "filter with no matches should return null/undefined");
    }

    @Test
    void filter_singleParam_singleMatchReturnsScalar() throws Exception {
        // JSONata unwraps single-element sequences.
        JsonNode result = eval("$filter([1, 2, 3], function($v){ $v = 2 })");
        assertTrue(result.isNumber());
        assertEquals(2, result.intValue());
    }

    @Test
    void filter_threeParams_indexAvailableForPredicate() throws Exception {
        // Keep only elements whose value is greater than twice their index.
        // [1,2,3,4,5]: 1>0✓, 2>2✗, 3>4✗, 4>6✗, 5>8✗  → [1]
        JsonNode result = eval(
                "$filter([1, 2, 3, 4, 5], function($v, $i, $a){ $v > $i * 2 })");
        assertTrue(result.isNumber(), "single match should be unwrapped to scalar");
        assertEquals(1, result.intValue());
    }

    @Test
    void filter_twoParams_indexUsedInPredicate() throws Exception {
        // Keep elements at even indices (0, 2, 4): values 10, 30, 50.
        JsonNode result = eval("$filter([10, 20, 30, 40, 50], function($v, $i){ $i % 2 = 0 })");
        assertTrue(result.isArray());
        assertEquals(3, result.size());
        assertEquals(10, result.get(0).intValue());
        assertEquals(30, result.get(1).intValue());
        assertEquals(50, result.get(2).intValue());
    }

    // =========================================================================
    // $reduce
    // =========================================================================

    @Test
    void reduce_sum_rangeOneToFive() throws Exception {
        // $reduce([1..5], function($prev, $curr){ $prev + $curr }) = 15
        JsonNode result = eval("$reduce([1..5], function($prev, $curr){ $prev + $curr })");
        assertEquals(15, result.intValue());
    }

    @Test
    void reduce_product_rangeOneToFive() throws Exception {
        // $reduce([1..5], function($prev, $curr){ $prev * $curr }) = 120
        JsonNode result = eval("$reduce([1..5], function($prev, $curr){ $prev * $curr })");
        assertEquals(120, result.intValue());
    }

    @Test
    void reduce_sum_withInitialValue() throws Exception {
        // $reduce([1, 2, 3], function($a, $b){ $a + $b }, 10) = 16
        JsonNode result = eval("$reduce([1, 2, 3], function($a, $b){ $a + $b }, 10)");
        assertEquals(16, result.intValue());
    }

    @Test
    void reduce_concat_buildsString() throws Exception {
        JsonNode result = eval("$reduce(['a','b','c'], function($acc, $s){ $acc & $s })");
        assertEquals("abc", result.textValue());
    }

    @Test
    void reduce_max_findsMaximum() throws Exception {
        JsonNode result = eval(
                "$reduce([3, 1, 4, 1, 5, 9, 2, 6], function($m, $v){ $v > $m ? $v : $m })");
        assertEquals(9, result.intValue());
    }

    // =========================================================================
    // $single
    // =========================================================================

    @Test
    void single_exactlyOneMatch_returnsElement() throws Exception {
        JsonNode result = eval("$single([1, 2, 3], function($v){ $v = 2 })");
        assertEquals(2, result.intValue());
    }

    @Test
    void single_noMatch_throwsEvaluationException() {
        assertThrows(JsonataEvaluationException.class,
                () -> eval("$single([1, 2, 3], function($v){ $v > 10 })"));
    }

    @Test
    void single_multipleMatches_throwsEvaluationException() {
        assertThrows(JsonataEvaluationException.class,
                () -> eval("$single([1, 2, 3], function($v){ $v > 1 })"));
    }

    @Test
    void single_withObjectArray_returnsMatchingObject() throws Exception {
        String json = "{\"items\":[{\"id\":1,\"name\":\"Alpha\"},{\"id\":2,\"name\":\"Beta\"},{\"id\":3,\"name\":\"Gamma\"}]}";
        JsonNode result = eval("$single(items, function($v){ $v.id = 2 })", json);
        assertTrue(result.isObject());
        assertEquals("Beta", result.get("name").textValue());
    }

    // =========================================================================
    // $sift
    // =========================================================================

    @Test
    void sift_singleParam_valueBasedFilter() throws Exception {
        // $sift({"a": 1, "b": 2, "c": 3}, function($v){ $v > 1 }) → {"b":2, "c":3}
        JsonNode result = eval(
                "$sift({\"a\": 1, \"b\": 2, \"c\": 3}, function($v){ $v > 1 })");
        assertTrue(result.isObject());
        assertFalse(result.has("a"), "key 'a' should be filtered out");
        assertEquals(2, result.get("b").intValue());
        assertEquals(3, result.get("c").intValue());
    }

    @Test
    void sift_twoParams_keyBasedFilter() throws Exception {
        // $sift({"a": 1, "b": 2, "c": 3}, function($v, $k){ $k != "a" }) → {"b":2, "c":3}
        JsonNode result = eval(
                "$sift({\"a\": 1, \"b\": 2, \"c\": 3}, function($v, $k){ $k != \"a\" })");
        assertTrue(result.isObject());
        assertFalse(result.has("a"));
        assertTrue(result.has("b"));
        assertTrue(result.has("c"));
    }

    @Test
    void sift_allMatchPass() throws Exception {
        JsonNode result = eval(
                "$sift({\"x\": 10, \"y\": 20}, function($v){ $v > 0 })");
        assertTrue(result.isObject());
        assertEquals(2, result.size());
    }

    @Test
    void sift_noneMatchReturnsUndefined() throws Exception {
        JsonNode result = eval(
                "$sift({\"a\": 1, \"b\": 2}, function($v){ $v > 100 })");
        // JSONata returns undefined (null/MISSING) when no key passes the predicate,
        // so that empty-sift results are excluded from sequences (see case001).
        assertTrue(result == null || result.isNull() || result.isMissingNode());
    }

    @Test
    void sift_onDataFromJson() throws Exception {
        String json = "{\"account\":{\"active\":true,\"balance\":500},"
                + "\"metadata\":{\"active\":false,\"balance\":0}}";
        // Keep only sub-objects whose "active" flag is true.
        JsonNode result = eval(
                "$sift($, function($v){ $v.active = true })", json);
        assertTrue(result.isObject());
        assertTrue(result.has("account"));
        assertFalse(result.has("metadata"));
    }

    // =========================================================================
    // Multi-parameter callbacks whose body is a block
    // =========================================================================
    // A block body is emitted as a private helper method, and a multi-parameter
    // callback is emitted as an unpacking helper method; the two together used to
    // produce Java that referenced __root outside its scope.

    @Test
    void map_multiParamLambda_withBlockBody() throws Exception {
        JsonNode result = eval("$map([1, 2], function($v, $i){ ($c := 10; $v * $c + $i) })");
        assertTrue(result.isArray());
        assertEquals(10, result.get(0).intValue());
        assertEquals(21, result.get(1).intValue());
    }

    @Test
    void filter_multiParamLambda_withBlockBody() throws Exception {
        JsonNode result = eval("$filter([1, 2, 3], function($v, $i){ ($min := 2; $v >= $min) })");
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals(2, result.get(0).intValue());
        assertEquals(3, result.get(1).intValue());
    }

    @Test
    void single_multiParamLambda_withBlockBody() throws Exception {
        JsonNode result = eval("$single([1, 2, 3], function($v, $i){ ($want := 1; $i = $want) })");
        assertEquals(2, result.intValue());
    }

    @Test
    void sift_multiParamLambda_withBlockBody() throws Exception {
        JsonNode result = eval("$sift({\"a\": 1, \"b\": 2}, function($v, $k){ ($keep := \"b\"; $k = $keep) })");
        assertTrue(result.isObject());
        assertFalse(result.has("a"));
        assertEquals(2, result.get("b").intValue());
    }

    @Test
    void each_multiParamLambda_withBlockBody() throws Exception {
        JsonNode result = eval("$each({\"a\": 1}, function($v, $k){ ($sep := \":\"; $k & $sep & $v) })");
        assertEquals("a:1", result.textValue());
    }

    @Test
    void sort_comparatorWithBlockBody() throws Exception {
        JsonNode result = eval("$sort([3, 1, 2], function($a, $b){ ($bias := 0; $a + $bias > $b) })");
        assertTrue(result.isArray());
        assertEquals(1, result.get(0).intValue());
        assertEquals(2, result.get(1).intValue());
        assertEquals(3, result.get(2).intValue());
    }

    @Test
    void map_multiParamLambda_withBlockNestedInConditional() throws Exception {
        JsonNode result = eval("$map([1, 2], function($v, $i){ $i = 0 ? ($x := 100; $x) : $v })");
        assertTrue(result.isArray());
        assertEquals(100, result.get(0).intValue());
        assertEquals(2, result.get(1).intValue());
    }

    // =========================================================================
    // Callbacks supplied as a value rather than written inline
    // =========================================================================
    // A callback that reaches a built-in as a value — a variable holding a function, a partial
    // application, a chain — used to be called with the element alone, so a two-parameter callback
    // silently lost its index or key, and $sort could not tell a comparator from a key function.

    @Test
    void map_namedFunctionWithTwoParams_receivesTheIndex() throws Exception {
        JsonNode result = eval("( $f := function($v, $i){ $i }; $map([10, 20, 30], $f) )");
        assertTrue(result.isArray());
        assertEquals(0, result.get(0).intValue());
        assertEquals(1, result.get(1).intValue());
        assertEquals(2, result.get(2).intValue());
    }

    @Test
    void map_namedFunctionWithOneParam_receivesTheElement() throws Exception {
        JsonNode result = eval("( $f := function($v){ $v * 2 }; $map([1, 2], $f) )");
        assertEquals(2, result.get(0).intValue());
        assertEquals(4, result.get(1).intValue());
    }

    @Test
    void map_namedFunctionWithThreeParams_receivesTheWholeArray() throws Exception {
        JsonNode result = eval("( $f := function($v, $i, $a){ $count($a) }; $map([7, 8], $f) )");
        assertEquals(2, result.get(0).intValue());
        assertEquals(2, result.get(1).intValue());
    }

    @Test
    void filter_namedPredicateWithTwoParams_receivesTheIndex() throws Exception {
        JsonNode result = eval("( $p := function($v, $i){ $i > 0 }; $filter([1, 2, 3], $p) )");
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals(2, result.get(0).intValue());
        assertEquals(3, result.get(1).intValue());
    }

    @Test
    void single_namedPredicateWithTwoParams_receivesTheIndex() throws Exception {
        assertEquals(30, eval("( $p := function($v, $i){ $i = 2 }; $single([10, 20, 30], $p) )").intValue());
    }

    @Test
    void sift_namedPredicateWithTwoParams_receivesTheKey() throws Exception {
        JsonNode result = eval("( $s := function($v, $k){ $k = \"a\" }; $sift({\"a\": 1, \"b\": 2}, $s) )");
        assertTrue(result.isObject());
        assertEquals(1, result.get("a").intValue());
        assertFalse(result.has("b"));
    }

    @Test
    void each_namedFunctionWithTwoParams_receivesTheKey() throws Exception {
        JsonNode result = eval("( $e := function($v, $k){ $k & \"=\" & $v }; $each({\"a\": 1}, $e) )");
        assertEquals("a=1", result.textValue());
    }

    @Test
    void sort_namedComparator_sortsRatherThanKeyingOnABoolean() throws Exception {
        JsonNode result = eval("( $c := function($a, $b){ $a > $b }; $sort([3, 1, 2], $c) )");
        assertTrue(result.isArray());
        assertEquals(1, result.get(0).intValue());
        assertEquals(2, result.get(1).intValue());
        assertEquals(3, result.get(2).intValue());
    }

    @Test
    void sort_namedKeyFunction_stillKeys() throws Exception {
        JsonNode result = eval("( $k := function($v){ -$v }; $sort([1, 3, 2], $k) )");
        assertEquals(3, result.get(0).intValue());
        assertEquals(2, result.get(1).intValue());
        assertEquals(1, result.get(2).intValue());
    }

    @Test
    void builtinAsAValue_isCalledWithOneArgument() throws Exception {
        JsonNode result = eval("( $f := $string; $map([1, 2], $f) )");
        assertEquals("1", result.get(0).textValue());
        assertEquals("2", result.get(1).textValue());
    }

    @Test
    void partialApplicationAsAValue_isCalledWithOneArgument() throws Exception {
        JsonNode result = eval("( $first := $substring(?, 0, 1); $map([\"ab\", \"cd\"], $first) )");
        assertEquals("a", result.get(0).textValue());
        assertEquals("c", result.get(1).textValue());
    }

    @Test
    void chainAsAValue_isCalledWithOneArgument() throws Exception {
        JsonNode result = eval("( $norm := $trim ~> $uppercase; $map([\" a \", \" b \"], $norm) )");
        assertEquals("A", result.get(0).textValue());
        assertEquals("B", result.get(1).textValue());
    }

    @Test
    void map_multiParamLambda_blockBodyReachesTheDocumentRoot() throws Exception {
        // The block helper needs the document root, which the unpacking helper must pass on.
        JsonNode result = eval(
                "$map(items, function($v, $i){ ($p := $$.prefix; $p & $v) })",
                "{\"prefix\": \"x\", \"items\": [\"a\", \"b\"]}");
        assertTrue(result.isArray());
        assertEquals("xa", result.get(0).textValue());
        assertEquals("xb", result.get(1).textValue());
    }
}
