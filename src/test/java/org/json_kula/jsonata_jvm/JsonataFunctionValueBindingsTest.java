package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for functions crossing the binding boundary as <em>values</em> rather than as call targets:
 *
 * <ul>
 *   <li>A bound function ({@code bindFunction} / {@code registerFunction}) referenced as
 *       {@code $name} — passed to {@code $map}, piped through {@code ~>}, handed to another bound
 *       function.</li>
 *   <li>A function value bound with {@code bindValue} and called directly as
 *       {@code $name(...)}.</li>
 *   <li>The {@code f} (function) signature type, on both a Java bound function and a JSONata lambda
 *       signature.</li>
 * </ul>
 *
 * <p>The JSONata-side half of this is covered by the official suite
 * ({@code function-signatures/case024}, {@code case025}); the binding API has no counterpart there,
 * so it is covered here.
 */
class JsonataFunctionValueBindingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonataExpressionFactory FACTORY;

    @BeforeAll
    static void setup() {
        FACTORY = new JsonataExpressionFactory();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static JsonNode num(double v) {
        return MAPPER.convertValue(v, JsonNode.class);
    }

    private static JsonataExpression compile(String expr) throws Exception {
        return FACTORY.compile(expr);
    }

    private static JsonNode eval(String expr, JsonataBindings b) throws Exception {
        return compile(expr).evaluate(EMPTY_OBJECT, b);
    }

    /** Compares against parsed JSON without insisting on a particular numeric node type. */
    private static void assertJson(String expected, JsonNode actual) {
        JsonNodeTestHelper.assertJsonEquals(JsonNodeTestHelper.parseJson(expected), actual, "");
    }

    /** A bound function of the given signature, from a plain Java lambda over the argument list. */
    private static JsonataBoundFunction fn(String signature,
                                           java.util.function.Function<JsonataFunctionArguments, JsonNode> body) {
        return new JsonataBoundFunction() {
            @Override public String getFunctionSignature() { return signature; }
            @Override public JsonNode apply(JsonataFunctionArguments args) { return body.apply(args); }
        };
    }

    /** {@code $double}: one number in, twice it out. */
    private static JsonataBoundFunction doubler() {
        return fn("<n:n>", a -> num(a.get(0).doubleValue() * 2));
    }

    /** {@code $add}: two numbers in, their sum out. */
    private static JsonataBoundFunction adder() {
        return fn("<nn:n>", a -> num(a.get(0).doubleValue() + a.get(1).doubleValue()));
    }

    // =========================================================================
    // A bound function used as a value
    // =========================================================================

    @Test
    void boundFunction_asValue_passedToMap() throws Exception {
        JsonataBindings b = new JsonataBindings().bindFunction("double", doubler());
        assertJson("[2,4,6]", eval("$map([1,2,3], $double)", b));
    }

    @Test
    void boundFunction_asValue_pipedThroughChain() throws Exception {
        JsonataBindings b = new JsonataBindings().bindFunction("double", doubler());
        assertEquals(10.0, eval("5 ~> $double", b).doubleValue(), 1e-9);
    }

    @Test
    void boundFunction_asValue_isAFunction() throws Exception {
        JsonataBindings b = new JsonataBindings().bindFunction("double", doubler());
        assertEquals("function", eval("$type($double)", b).asText());
    }

    @Test
    void boundFunction_asValue_assignedToLocalThenCalled() throws Exception {
        JsonataBindings b = new JsonataBindings().bindFunction("double", doubler());
        assertEquals(8.0, eval("($f := $double; $f(4))", b).doubleValue(), 1e-9);
    }

    @Test
    void boundFunction_asValue_passedToAnotherBoundFunction() throws Exception {
        JsonataBoundFunction applyTwice = fn("<fj:j>", a -> {
            try {
                return JsonataRuntime.fn_apply(a.get(0), JsonataRuntime.fn_apply(a.get(0), a.get(1)));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        JsonataBindings b = new JsonataBindings()
                .bindFunction("double", doubler())
                .bindFunction("applyTwice", applyTwice);
        assertEquals(12.0, eval("$applyTwice($double, 3)", b).doubleValue(), 1e-9);
    }

    @Test
    void boundFunction_asValue_twoParamCallbackReceivesIndex() throws Exception {
        // $map passes (value, index, array) to a callback that declares more than one parameter.
        // The declared arity comes from the signature, so a two-argument signature gets the index.
        JsonataBindings b = new JsonataBindings().bindFunction("withIndex",
                fn("<nn:n>", a -> num(a.get(0).doubleValue() * 10 + a.get(1).doubleValue())));
        assertJson("[10,21,32]", eval("$map([1,2,3], $withIndex)", b));
    }

    @Test
    void boundFunction_asValue_usedAsReduceAccumulator() throws Exception {
        JsonataBindings b = new JsonataBindings().bindFunction("add", adder());
        assertEquals(10.0, eval("$reduce([1,2,3,4], $add)", b).doubleValue(), 1e-9);
    }

    @Test
    void boundFunction_asValue_usedAsSortComparator() throws Exception {
        JsonataBindings b = new JsonataBindings().bindFunction("desc",
                fn("<nn:b>", a -> BooleanNode.valueOf(a.get(0).doubleValue() < a.get(1).doubleValue())));
        assertJson("[3,2,1]", eval("$sort([2,3,1], $desc)", b));
    }

    @Test
    void permanentlyRegisteredFunction_usableAsValue() throws Exception {
        JsonataExpression expr = compile("$map([1,2,3], $double)");
        expr.registerFunction("double", doubler());
        assertJson("[2,4,6]", expr.evaluate(EMPTY_OBJECT));
    }

    @Test
    void libraryExportedFunction_passedToBoundFunction() throws Exception {
        // Library exports are registered as bound functions, so before they could be referenced as
        // values a library function could not be handed to another function.
        try (JsonataLibrary lib = FACTORY.compileLibrary("($inc := function($x){$x + 1}; ['inc'])")) {
            JsonataBoundFunction applyTwice = fn("<fj:j>", a -> {
                try {
                    return JsonataRuntime.fn_apply(a.get(0), JsonataRuntime.fn_apply(a.get(0), a.get(1)));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            JsonataBindings b = new JsonataBindings()
                    .bindFunction("applyTwice", applyTwice)
                    .useLibrary(lib);
            assertEquals(7.0, eval("$applyTwice($inc, 5)", b).doubleValue(), 1e-9);
            assertJson("[2,3,4]", eval("$map([1,2,3], $inc)", b));
        }
    }

    @Test
    void boundFunction_asValue_stillReportsItsOwnErrors() throws Exception {
        JsonataBindings b = new JsonataBindings().bindFunction("boom", new JsonataBoundFunction() {
            @Override public String getFunctionSignature() { return "<j:j>"; }
            @Override public JsonNode apply(JsonataFunctionArguments args) throws JsonataEvaluationException {
                throw new JsonataEvaluationException("D9999", "intentional");
            }
        });
        JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                () -> eval("$map([1], $boom)", b));
        assertEquals("D9999", e.getErrorCode());
    }

    @Test
    void boundFunction_asValue_signatureIsStillApplied() throws Exception {
        // Reached through a value, a bound function coerces its arguments exactly as a direct call
        // does: "5" arrives as a number because the signature says n.
        JsonataBindings b = new JsonataBindings().bindFunction("isNumber",
                fn("<n:b>", a -> BooleanNode.valueOf(a.get(0).isNumber())));
        assertTrue(eval("$map([\"5\", \"6\"], $isNumber)", b).get(0).asBoolean());
    }

    @Test
    void valueBinding_winsOverFunctionBinding_inValuePosition() throws Exception {
        JsonataBindings b = new JsonataBindings()
                .bindFunction("x", doubler())
                .bindValue("x", num(7));
        assertEquals(7.0, eval("$x", b).doubleValue(), 1e-9);
    }

    @Test
    void variadicBoundFunction_asValue_receivesOneArgument() throws Exception {
        // Documented limitation: a signature that does not pin the arity down — absent, unparseable
        // or variadic — yields a one-argument function value, because a packed argument tuple is
        // indistinguishable from a single array argument. Declare a fixed arity to receive several.
        JsonataBindings b = new JsonataBindings().bindFunction("count",
                fn("<j+:n>", a -> IntNode.valueOf(a.size())));
        assertJson("[1,1]", eval("$map([1,2], $count)", b));
    }

    // =========================================================================
    // A function value bound as a value
    // =========================================================================

    /** A one-argument function value, as client code would build one. */
    private static JsonNode timesTen() {
        return JsonataRuntime.lambdaNode(x -> num(x.doubleValue() * 10), 1);
    }

    @Test
    void boundFunctionValue_calledDirectly() throws Exception {
        JsonataBindings b = new JsonataBindings().bindValue("f", timesTen());
        assertEquals(30.0, eval("$f(3)", b).doubleValue(), 1e-9);
    }

    @Test
    void boundFunctionValue_calledWithSeveralArguments() throws Exception {
        JsonNode sum = JsonataRuntime.lambdaNode(
                args -> num(args.get(0).doubleValue() + args.get(1).doubleValue()), 2);
        JsonataBindings b = new JsonataBindings().bindValue("sum2", sum);
        assertEquals(7.0, eval("$sum2(3, 4)", b).doubleValue(), 1e-9);
    }

    @Test
    void boundFunctionValue_calledWithNoArguments() throws Exception {
        JsonNode constant = JsonataRuntime.lambdaNode(ignored -> num(42), 0);
        JsonataBindings b = new JsonataBindings().bindValue("answer", constant);
        assertEquals(42.0, eval("$answer()", b).doubleValue(), 1e-9);
    }

    @Test
    void boundFunctionValue_usableAsValueToo() throws Exception {
        JsonataBindings b = new JsonataBindings().bindValue("f", timesTen());
        assertJson("[10,20]", eval("$map([1,2], $f)", b));
        assertEquals(40.0, eval("4 ~> $f", b).doubleValue(), 1e-9);
    }

    @Test
    void functionBinding_winsOverValueBinding_atCallSite() throws Exception {
        JsonataBindings b = new JsonataBindings()
                .bindValue("f", timesTen())
                .bindFunction("f", doubler());
        assertEquals(6.0, eval("$f(3)", b).doubleValue(), 1e-9);
    }

    @Test
    void boundValueThatIsNotAFunction_calledAsFunction_stillFails() throws Exception {
        JsonataBindings b = new JsonataBindings().bindValue("notAFunction", num(3));
        JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                () -> eval("$notAFunction(1)", b));
        assertEquals("T1006", e.getErrorCode());
        assertEquals("The function 'notAFunction' is not defined", e.getMessage());
    }

    @Test
    void unboundName_calledAsFunction_stillFails() throws Exception {
        JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                () -> eval("$nothingBound(1)", new JsonataBindings()));
        assertEquals("T1006", e.getErrorCode());
    }

    // =========================================================================
    // The "f" signature type — Java bound functions
    // =========================================================================

    @Test
    void signatureF_acceptsAFunctionArgument() throws Exception {
        JsonataBindings b = new JsonataBindings().bindFunction("callWith", fn("<fn:n>", a -> {
            try {
                return JsonataRuntime.fn_apply(a.get(0), a.get(1));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }));
        assertEquals(9.0, eval("$callWith(function($x){$x * 3}, 3)", b).doubleValue(), 1e-9);
    }

    @Test
    void signatureF_rejectsANonFunctionArgument() throws Exception {
        JsonataBindings b = new JsonataBindings().bindFunction("callWith",
                fn("<fn:n>", a -> num(0)));
        JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                () -> eval("$callWith(5, 3)", b));
        assertEquals("T0410", e.getErrorCode());
    }

    @Test
    void signatureF_doesNotDisableTheRestOfTheSignature() throws Exception {
        // An unrecognised type symbol makes the whole signature unparseable, and an unparseable
        // signature is passed over: before "f" was recognised, declaring it silently switched off
        // coercion and arity checking for every other parameter of that function.
        JsonataBindings b = new JsonataBindings().bindFunction("second",
                fn("<fn:b>", a -> BooleanNode.valueOf(a.get(1).isNumber())));
        assertTrue(eval("$second(function($x){$x}, \"5\")", b).asBoolean(),
                "the n parameter should have been coerced to a number");

        JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                () -> eval("$second(function($x){$x})", b));
        assertTrue(e.getMessage().contains("Missing required argument"), e.getMessage());
    }

    @Test
    void signatureF_parametrisedForm_isAccepted() throws Exception {
        // f<n:n> imposes the same check as f; the argument function's own types are not verified.
        JsonataBindings b = new JsonataBindings().bindFunction("callWith", fn("<f<n:n>n:n>", a -> {
            try {
                return JsonataRuntime.fn_apply(a.get(0), a.get(1));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }));
        assertEquals(8.0, eval("$callWith(function($x){$x + 5}, 3)", b).doubleValue(), 1e-9);
        assertEquals("T0410",
                assertThrows(JsonataEvaluationException.class, () -> eval("$callWith(1, 3)", b))
                        .getErrorCode());
    }

    @Test
    void signatureF_acceptsABoundFunctionReferencedAsAValue() throws Exception {
        JsonataBindings b = new JsonataBindings()
                .bindFunction("double", doubler())
                .bindFunction("callWith", fn("<fn:n>", a -> {
                    try {
                        return JsonataRuntime.fn_apply(a.get(0), a.get(1));
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }));
        assertEquals(14.0, eval("$callWith($double, 7)", b).doubleValue(), 1e-9);
    }

    // =========================================================================
    // The "f" and "x" signature types — JSONata lambda signatures
    // =========================================================================

    @Test
    void lambdaSignatureF_acceptsAFunction() throws Exception {
        assertEquals(6.0, eval("λ($f)<f:n>{$f(2)}(function($x){$x * 3})", new JsonataBindings())
                .doubleValue(), 1e-9);
    }

    @Test
    void lambdaSignatureF_rejectsANonFunction() throws Exception {
        JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                () -> eval("λ($f)<f:n>{2}(5)", new JsonataBindings()));
        assertEquals("T0410", e.getErrorCode());
    }

    @Test
    void lambdaSignatureF_keepsLaterParametersAligned() throws Exception {
        // "f" used to be skipped rather than consumed as a spec, which shifted every later spec one
        // parameter to the left — here the "n" check would have landed on $f, the function.
        assertEquals(7.0, eval("λ($f, $n)<fn:n>{$n}(function($x){$x}, 7)", new JsonataBindings())
                .doubleValue(), 1e-9);
    }

    @Test
    void lambdaSignatureX_acceptsAnythingAndKeepsAlignment() throws Exception {
        assertEquals(7.0, eval("λ($a, $n)<xn:n>{$n}(\"anything\", 7)", new JsonataBindings())
                .doubleValue(), 1e-9);
    }

    @Test
    void aFailingBoundFunctionReportsWhyItFailed() throws Exception {
        // The wrapper names the call site; without the cause's message a caller is told only that
        // "$boom" failed — not that it was a timeout, a signature rejection, or an explicit $error.
        JsonataBindings bindings = new JsonataBindings().bindFunction("boom", new JsonataBoundFunction() {
            @Override public String getFunctionSignature() { return "<n:n>"; }
            @Override public JsonNode apply(JsonataFunctionArguments args) throws JsonataEvaluationException {
                throw new JsonataEvaluationException("U1001", "Expression evaluation timeout");
            }
        });

        JsonataEvaluationException e = assertThrows(JsonataEvaluationException.class,
                () -> eval("$map([1], $boom)", bindings));

        assertEquals("U1001", e.getErrorCode());
        assertTrue(e.getMessage().contains("$boom"), e.getMessage());
        assertTrue(e.getMessage().contains("Expression evaluation timeout"), e.getMessage());
    }
}
