package org.json_kula.jsonata_jvm.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonNodeTestHelper;
import org.json_kula.jsonata_jvm.JsonataException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressions found by differentially testing against the reference {@code jsonata} interpreter,
 * each pinned to the answer the reference gives.
 *
 * <p>Two sweeps produced these. The first took the findings the sibling ports recorded — a finding
 * in one port is a question for the others, never an answer — and asked the reference about each
 * one here. The second fuzzed every built-in with adversarial arguments looking for <em>leaked
 * host exceptions</em>: an error carrying a JVM class name instead of a JSONata code is one a
 * caller cannot match on, and one case leaked an {@code OutOfMemoryError}, which is not even
 * contained to the call that caused it.
 *
 * <p>The harness that finds these lives in {@code tools/conformance}; this class is the part that
 * has to keep passing without it.
 */
class ReferenceParityTest {

    private static JsonNode eval(String expr) throws Exception {
        return JsonNodeTestHelper.evaluate(expr);
    }

    /**
     * Asserts that {@code expr} fails with the given JSONata error code.
     *
     * <p>The code is the point: an error carrying a JVM class name instead of a code is one the
     * caller cannot match on, and several of these used to be exactly that.
     */
    private static void assertFails(String expr, String code) {
        Exception error = assertThrows(Exception.class, () -> eval(expr), expr);
        assertInstanceOf(JsonataException.class, error,
                expr + " should raise a JSONata error, got: " + error);
        assertEquals(code, ((JsonataException) error).getErrorCode(),
                expr + " raised: " + error.getMessage());
    }

    // =========================================================================
    // Calling a function with no arguments
    //
    // The runtime convention passed NULL for "no argument", so a parameter the caller left out
    // arrived as a real JSON null: $exists($x) was true and $type($x) was "null".
    // =========================================================================

    @Test
    void anOmittedParameterIsUndefinedNotNull() throws Exception {
        assertFalse(eval("( $f := function($x){ $exists($x) }; $f() )").booleanValue());
        assertTrue(eval("( $f := function($x){ $type($x) }; $f() )").isMissingNode());
        JsonNodeTestHelper.assertJsonEquals(JsonNodeTestHelper.parseJson("[false,false]"),
                eval("( $f := function($x,$y){ [$exists($x), $exists($y)] }; $f() )"),
                "neither parameter is bound");
    }

    @Test
    void anOmittedParameterIsUndefinedThroughAFieldCallToo() throws Exception {
        assertEquals(42,
                eval("( $o := {\"g\": function($x){ $exists($x) ? $x * 2 : 42 }}; $o.g() )").asInt());
        assertEquals(42,
                eval("( $o := {\"g\": function($x){ $exists($x) ? $x * 2 : 42 }}; ($o.g)() )").asInt());
    }

    @Test
    void anArgumentThatIsSuppliedStillArrives() throws Exception {
        assertTrue(eval("( $f := function($x){ $exists($x) }; $f(1) )").booleanValue());
        assertEquals(7, eval("( $f := function(){ 7 }; $f() )").asInt());
    }

    // =========================================================================
    // Higher-order built-ins called with the arguments the wrong way round
    //
    // A lambda in the SEQUENCE slot was treated as the callback the built-in generates itself,
    // so the placeholder for "the generator will fill this in" was emitted into the sequence
    // slot and the generated class did not compile: a plainly wrong expression came back as a
    // Java compilation error naming generated symbols.
    // =========================================================================

    @Test
    void aSwappedHigherOrderCallIsAJsonataError() {
        for (String expr : new String[] {
                "$filter(function($x){$x}, [1,2])",
                "$map(function($x){$x}, 1)",
                "$single(function($x){$x}, [1,2])",
                "$reduce(function($x,$y){$x}, [1,2])",
                "$sort(function($x,$y){true}, [1,2])"}) {
            assertFails(expr, "T0410");
        }
    }

    @Test
    void theCorrectlyOrderedFormsStillWork() throws Exception {
        assertEquals(2, eval("$filter([1,2], function($x){$x>1})").asInt());
        JsonNodeTestHelper.assertJsonEquals(JsonNodeTestHelper.parseJson("[2,4]"),
                eval("$map([1,2], function($x){$x*2})"), "$map");
        assertEquals(6, eval("$reduce([1,2,3], function($a,$b){$a+$b})").asInt());
        JsonNodeTestHelper.assertJsonEquals(JsonNodeTestHelper.parseJson("[1,2,3]"),
                eval("$sort([3,1,2])"), "$sort");
        // A scalar in the sequence slot is legal — it promotes to a one-element sequence.
        assertEquals("1", eval("$map(1, $string)").textValue());
    }

    // =========================================================================
    // Built-ins called with no arguments at all
    //
    // These default their first argument to the context value when given one argument, and
    // indexed an empty argument list when given none.
    // =========================================================================

    @Test
    void aBuiltinCalledWithNoArgumentsReportsItsArity() {
        for (String name : new String[] {
                "contains", "eval", "match", "substringBefore", "substringAfter"}) {
            assertFails("$" + name + "()", "T0410");
        }
    }

    // =========================================================================
    // Numeric and string arguments far outside any usable range
    // =========================================================================

    @Test
    void aPrecisionThatOverflowsTheProductYieldsUndefined() throws Exception {
        // The reference computes value * 10^precision, so whether it overflows depends on the
        // value as well as the precision: $round(1, 308) answers, $round(1e15, 308) does not.
        assertEquals(1, eval("$round(1, 308)").asInt());
        assertTrue(eval("$round(1, 309)").isMissingNode());
        assertTrue(eval("$round(1, 1e15)").isMissingNode());
        assertTrue(eval("$round(1e15, 308)").isMissingNode());
        assertEquals(0, eval("$round(1, -1e15)").asInt());
        assertEquals(0, eval("$round(0, 1e15)").asInt());
    }

    @Test
    void anAbsurdPadWidthIsAnErrorRatherThanAnOutOfMemory() {
        // The reference raises RangeError past its host's maximum string length. This raised
        // OutOfMemoryError, which can take unrelated work in the same JVM down with it.
        assertFails("$pad(\"x\", 1e15)", "D1001");
        assertFails("$pad(\"x\", -1e15)", "D1001");
    }

    @Test
    void anOrdinaryPadStillPads() throws Exception {
        assertEquals("x  ", eval("$pad(\"x\", 3)").textValue());
        assertEquals("  x", eval("$pad(\"x\", -3)").textValue());
        assertEquals(1000, eval("$length($pad(\"x\", 1000))").asInt());
    }

    // =========================================================================
    // Regex built-in arguments
    // =========================================================================

    @Test
    void aDeclaredNumericLimitIsCheckedBeforeTheBodyRuns() {
        // The reference validates a call against its signature first, so an absent earlier
        // argument does not hide a bad later one.
        assertFails("$match(\"abc\", /a/, \"1\")", "T0410");
        assertFails("$match(\"abc\", /a/, null)", "T0410");
        assertFails("$match(nope, /a/, \"1\")", "T0410");
        assertFails("$split(nope, /a/, \"1\")", "T0410");
        assertFails("$replace(nope, /a/, \"x\", \"1\")", "T0410");
    }

    @Test
    void aFractionalLimitIsComparedNotTruncated() throws Exception {
        // The reference tests `count < limit`, so 1.5 admits two pieces rather than one.
        assertEquals(2, eval("$count($match(\"abcabc\", /a/, 1.5))").asInt());
        assertEquals(2, eval("$count($split(\"a1b2c3\", /[0-9]/, 1.5))").asInt());
    }

    @Test
    void aReplacementFunctionMustReturnAString() {
        // Undefined is not a string either: $m.index does not exist — the match object carries
        // `start` and `end` — so this must fail rather than substitute nothing.
        assertFails("$replace(\"abcabc\", /a(b)/, function($m){ $string($m.index) })", "D3012");
        assertFails("$replace(\"ab\", /a/, function($m){ nothing })", "D3012");
    }

    @Test
    void theReplacementFunctionSeesTheReferencesMatchObject() throws Exception {
        // The whole subject matches, so the result is exactly what the replacer returned: the
        // reference's own matcher-closure keys, `start`/`end` rather than `index`.
        assertEquals("[\"match\",\"start\",\"end\",\"groups\",\"next\"]",
                eval("$replace(\"ab\", /a(b)/, function($m){ $string($keys($m)) })").textValue());
        assertEquals("bcbc", eval("$replace(\"abcabc\", /a(b)/, function($m){ $m.groups[0] })").textValue());
    }

    // =========================================================================
    // $base64decode
    // =========================================================================

    @Test
    void padCharacterEndsTheBase64Stream() throws Exception {
        // Lenient about everything else, but '=' terminates rather than being skipped. Skipping
        // it agrees with the reference on every well-formed input, so only mid-string padding
        // tells the two apart.
        assertEquals("a", eval("$base64decode(\"YW=J\")").textValue());
        assertEquals("", eval("$base64decode(\"=YWJj\")").textValue());
        assertEquals("ab", eval("$base64decode(\"YWJ=jZA\")").textValue());
    }

    @Test
    void ordinaryBase64StillRoundTrips() throws Exception {
        assertEquals("abc", eval("$base64decode(\"YWJj\")").textValue());
        assertEquals("abcd", eval("$base64decode(\"YWJjZA==\")").textValue());
        assertEquals("héllo", eval("$base64decode($base64encode(\"héllo\"))").textValue());
        assertEquals("", eval("$base64decode(\"!!!!\")").textValue());
    }

    // =========================================================================
    // Calling a function held in a field
    // =========================================================================

    @Test
    void aBareNameInAPathStepResolvesAsAField() throws Exception {
        assertEquals("field", eval("( $o := {\"count\": function(){ \"field\" }}; $o.count() )").textValue());
        assertEquals("var", eval(
                "( $g := function(){ \"var\" }; $o := {\"g\": function(){ \"field\" }}; $o.$g() )")
                .textValue());
        assertEquals(7, eval("( $o := {\"g\": function($x){ $x }, \"n\": 7}; $o.g(n) )").asInt());
        JsonNodeTestHelper.assertJsonEquals(JsonNodeTestHelper.parseJson("[1,2]"),
                eval("( $o := [{\"g\": function(){1}}, {\"g\": function(){2}}]; $o.g() )"),
                "the step maps");
    }
}
