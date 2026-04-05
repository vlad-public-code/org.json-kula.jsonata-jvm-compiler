package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the bindings API:
 * <ul>
 *   <li>Per-evaluation bindings via {@link JsonataExpression#evaluate(JsonNode, JsonataBindings)}</li>
 *   <li>Permanent value bindings via {@link JsonataExpression#assign}</li>
 *   <li>Permanent function bindings via {@link JsonataExpression#registerFunction}</li>
 *   <li>{@link JsonataBoundFunction} invocation and signature</li>
 *   <li>Precedence rules (per-eval overrides permanent)</li>
 *   <li>Thread safety with concurrent per-evaluation bindings</li>
 * </ul>
 */
class JsonataBindingsTest {

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

    private static JsonNode str(String s) {
        return MAPPER.convertValue(s, JsonNode.class);
    }

    private static JsonataExpression compile(String expr) throws Exception {
        return FACTORY.compile(expr);
    }

    private static JsonNode parseJson(String json) {
        return JsonNodeTestHelper.parseJson(json);
    }

    // =========================================================================
    // Per-evaluation value bindings
    // =========================================================================

    @Test
    void perEval_singleValue_resolvedInExpression() throws Exception {
        JsonataExpression expr = compile("$rate * price");
        JsonataBindings b = new JsonataBindings().bindValue("rate", num(1.2));
        JsonNode result = expr.evaluate(parseJson("{\"price\": 100}"), b);
        assertEquals(120.0, result.doubleValue(), 1e-9);
    }

    @Test
    void perEval_multipleValues_allResolved() throws Exception {
        JsonataExpression expr = compile("$a + $b + $c");
        JsonataBindings b = new JsonataBindings()
                .bindValue("a", num(1))
                .bindValue("b", num(2))
                .bindValue("c", num(3));
        assertEquals(6.0, expr.evaluate(EMPTY_OBJECT, b).doubleValue(), 1e-9);
    }

    @Test
    void perEval_stringValue_resolvedInConcat() throws Exception {
        JsonataExpression expr = compile("$greeting & \", \" & name");
        JsonataBindings b = new JsonataBindings().bindValue("greeting", str("Hello"));
        JsonNode result = expr.evaluate(parseJson("{\"name\": \"World\"}"), b);
        assertEquals("Hello, World", result.asText());
    }

    @Test
    void perEval_unboundVariable_returnsNull() throws Exception {
        // $missing is never bound — JSONata undefined propagates as MISSING
        JsonataExpression expr = compile("$missing");
        JsonNode result = expr.evaluate(EMPTY_OBJECT, new JsonataBindings());
        assertTrue(result.isMissingNode(), "Expected MISSING for unbound variable");
    }

    @Test
    void perEval_nullBindings_behavesLikeNoBindings() throws Exception {
        JsonataExpression expr = compile("1 + 1");
        assertEquals(2.0, expr.evaluate(EMPTY_OBJECT, null).doubleValue(), 1e-9);
    }

    @Test
    void perEval_valueUsedInConditional() throws Exception {
        JsonataExpression expr = compile("$discount > 0 ? price * (1 - $discount) : price");
        JsonataBindings b10 = new JsonataBindings().bindValue("discount", num(0.10));
        JsonataBindings b0  = new JsonataBindings().bindValue("discount", num(0));
        String json = "{\"price\": 200}";
        assertEquals(180.0, expr.evaluate(parseJson(json), b10).doubleValue(), 1e-9);
        assertEquals(200.0, expr.evaluate(parseJson(json), b0).doubleValue(), 1e-9);
    }

    @Test
    void perEval_valueUsedInPredicate() throws Exception {
        JsonataExpression expr = compile("items[price < $maxPrice].name");
        JsonataBindings b = new JsonataBindings().bindValue("maxPrice", num(15));
        String json = "{\"items\":[{\"name\":\"A\",\"price\":10},{\"name\":\"B\",\"price\":20}]}";
        JsonNode result = expr.evaluate(parseJson(json), b);
        assertEquals("A", result.asText());
    }

    // =========================================================================
    // Permanent value bindings — assign()
    // =========================================================================

    @Test
    void assign_valueAvailableOnAllSubsequentEvals() throws Exception {
        JsonataExpression expr = compile("$taxRate * amount");
        expr.assign("taxRate", num(0.2));
        assertEquals(20.0, expr.evaluate(parseJson("{\"amount\": 100}")).doubleValue(), 1e-9);
        assertEquals(40.0, expr.evaluate(parseJson("{\"amount\": 200}")).doubleValue(), 1e-9);
    }

    @Test
    void assign_multipleValues_allAvailable() throws Exception {
        JsonataExpression expr = compile("$x + $y");
        expr.assign("x", num(7));
        expr.assign("y", num(3));
        assertEquals(10.0, expr.evaluate(EMPTY_OBJECT).doubleValue(), 1e-9);
    }

    @Test
    void assign_overwriteExistingAssignment() throws Exception {
        JsonataExpression expr = compile("$factor * 10");
        expr.assign("factor", num(2));
        assertEquals(20.0, expr.evaluate(EMPTY_OBJECT).doubleValue(), 1e-9);
        expr.assign("factor", num(5));
        assertEquals(50.0, expr.evaluate(EMPTY_OBJECT).doubleValue(), 1e-9);
    }

    @Test
    void assign_doesNotAffectOtherInstances() throws Exception {
        JsonataExpression e1 = compile("$x");
        JsonataExpression e2 = compile("$x");
        e1.assign("x", num(42));
        assertTrue(e2.evaluate(EMPTY_OBJECT).isMissingNode(),
                "Binding on e1 must not leak to e2");
    }

    // =========================================================================
    // Per-evaluation function bindings
    // =========================================================================

    @Test
    void perEval_boundFunction_calledWithArgs() throws Exception {
        JsonataExpression expr = compile("$double(value)");
        JsonataBindings b = new JsonataBindings().bindFunction("double", new JsonataBoundFunction() {
            @Override public String getFunctionSignature() { return "<n:n>"; }
            @Override public JsonNode apply(JsonataFunctionArguments args) {
                return MAPPER.convertValue(args.get(0).doubleValue() * 2, JsonNode.class);
            }
        });
        JsonNode result = expr.evaluate(parseJson("{\"value\": 21}"), b);
        assertEquals(42.0, result.doubleValue(), 1e-9);
    }

    @Test
    void perEval_boundFunction_multipleArgs() throws Exception {
        JsonataExpression expr = compile("$add($a, $b)");
        JsonataBindings b = new JsonataBindings()
                .bindValue("a", num(3))
                .bindValue("b", num(4))
                .bindFunction("add", new JsonataBoundFunction() {
                    @Override public String getFunctionSignature() { return "<nn:n>"; }
                    @Override public JsonNode apply(JsonataFunctionArguments args) {
                        return MAPPER.convertValue(
                                args.get(0).doubleValue() + args.get(1).doubleValue(),
                                JsonNode.class);
                    }
                });
        assertEquals(7.0, expr.evaluate(EMPTY_OBJECT, b).doubleValue(), 1e-9);
    }

    @Test
    void perEval_unboundFunction_returnsNull() throws Exception {
        JsonataExpression expr = compile("$notRegistered(1)");
        Exception exception = assertThrows(JsonataEvaluationException.class,
                () -> expr.evaluate(EMPTY_OBJECT, new JsonataBindings()));
        assertEquals("T1006: The function 'notRegistered' is not defined", exception.getMessage());
    }

    @Test
    void perEval_boundFunction_canThrowEvaluationException() throws Exception {
        JsonataExpression expr = compile("$boom()");
        JsonataBindings b = new JsonataBindings().bindFunction("boom", new JsonataBoundFunction() {
            @Override public String getFunctionSignature() { return "<:j>"; }
            @Override public JsonNode apply(JsonataFunctionArguments args) throws JsonataEvaluationException {
                throw new JsonataEvaluationException("intentional error");
            }
        });
        assertThrows(JsonataEvaluationException.class, () -> expr.evaluate(EMPTY_OBJECT, b));
    }

    // =========================================================================
    // Permanent function bindings — registerFunction()
    // =========================================================================

    @Test
    void registerFunction_availableOnAllSubsequentEvals() throws Exception {
        JsonataExpression expr = compile("$square(n)");
        expr.registerFunction("square", new JsonataBoundFunction() {
            @Override public String getFunctionSignature() { return "<n:n>"; }
            @Override public JsonNode apply(JsonataFunctionArguments args) {
                double v = args.get(0).doubleValue();
                return MAPPER.convertValue(v * v, JsonNode.class);
            }
        });
        assertEquals(9.0,  expr.evaluate(parseJson("{\"n\":3}")).doubleValue(), 1e-9);
        assertEquals(25.0, expr.evaluate(parseJson("{\"n\":5}")).doubleValue(), 1e-9);
    }

    @Test
    void registerFunction_doesNotAffectOtherInstances() throws Exception {
        JsonataExpression e1 = compile("$fn()");
        JsonataExpression e2 = compile("$fn()");
        e1.registerFunction("fn", new JsonataBoundFunction() {
            @Override public String getFunctionSignature() { return "<:n>"; }
            @Override public JsonNode apply(JsonataFunctionArguments args) { return IntNode.valueOf(1); }
        });
        Exception exception = assertThrows(JsonataEvaluationException.class,
                () -> e2.evaluate(EMPTY_OBJECT).isMissingNode());
        assertEquals("T1006: The function 'fn' is not defined", exception.getMessage());
    }

    // =========================================================================
    // Precedence: per-eval overrides permanent
    // =========================================================================

    @Test
    void perEval_valueOverridesPermanentAssign() throws Exception {
        JsonataExpression expr = compile("$x");
        expr.assign("x", num(10));
        // Per-evaluation binding should win.
        JsonataBindings b = new JsonataBindings().bindValue("x", num(99));
        assertEquals(99.0, expr.evaluate(EMPTY_OBJECT, b).doubleValue(), 1e-9);
        // Without per-eval binding, permanent value is still there.
        assertEquals(10.0, expr.evaluate(EMPTY_OBJECT).doubleValue(), 1e-9);
    }

    @Test
    void perEval_functionOverridesPermanentRegister() throws Exception {
        JsonataExpression expr = compile("$fn()");
        expr.registerFunction("fn", new JsonataBoundFunction() {
            @Override public String getFunctionSignature() { return "<:n>"; }
            @Override public JsonNode apply(JsonataFunctionArguments args) { return IntNode.valueOf(1); }
        });
        JsonataBindings b = new JsonataBindings().bindFunction("fn", new JsonataBoundFunction() {
            @Override public String getFunctionSignature() { return "<:n>"; }
            @Override public JsonNode apply(JsonataFunctionArguments args) { return IntNode.valueOf(2); }
        });
        assertEquals(2.0, expr.evaluate(EMPTY_OBJECT, b).doubleValue(), 1e-9);
        assertEquals(1.0, expr.evaluate(EMPTY_OBJECT).doubleValue(), 1e-9);
    }

    // =========================================================================
    // JsonataFunctionArguments
    // =========================================================================

    @Test
    void functionArguments_outOfRangeIndex_returnsMissing() throws Exception {
        JsonataFunctionArguments args = new JsonataFunctionArguments(List.of(IntNode.valueOf(5)));
        assertFalse(args.get(0).isMissingNode(), "index 0 should be present");
        assertTrue(args.get(1).isMissingNode(), "index 1 is out of range — should be MissingNode");
        assertTrue(args.get(-1).isMissingNode(), "negative index should be MissingNode");
    }

    @Test
    void functionArguments_size_matchesSuppliedArgs() {
        JsonataFunctionArguments args = new JsonataFunctionArguments(
                List.of(IntNode.valueOf(1), IntNode.valueOf(2)));
        assertEquals(2, args.size());
    }

    @Test
    void functionArguments_asList_isImmutable() {
        JsonataFunctionArguments args = new JsonataFunctionArguments(List.of(IntNode.valueOf(1)));
        assertThrows(UnsupportedOperationException.class,
                () -> args.asList().add(IntNode.valueOf(2)));
    }

    // =========================================================================
    // JsonataBoundFunction — getFunctionSignature
    // =========================================================================

    @Test
    void boundFunction_getFunctionSignature_returnsConfiguredSignature() {
        JsonataBoundFunction fn = new JsonataBoundFunction() {
            @Override public String getFunctionSignature() { return "<s-:n>"; }
            @Override public JsonNode apply(JsonataFunctionArguments args) { return IntNode.valueOf(0); }
        };
        assertEquals("<s-:n>", fn.getFunctionSignature());
    }

    // =========================================================================
    // Backward compatibility
    // =========================================================================

    @Test
    void evaluate_withoutBindings_stillWorks() throws Exception {
        JsonataExpression expr = compile("a + b");
        assertEquals(7.0, expr.evaluate(JsonNodeTestHelper.parseJson("{\"a\":3,\"b\":4}")).doubleValue(), 1e-9);
    }

    @Test
    void evaluate_withEmptyBindings_stillWorks() throws Exception {
        JsonataExpression expr = compile("a * 2");
        assertEquals(10.0, expr.evaluate(JsonNodeTestHelper.parseJson("{\"a\":5}"), new JsonataBindings()).doubleValue(), 1e-9);
    }

    // =========================================================================
    // Thread safety — concurrent per-evaluation bindings
    // =========================================================================

    @Test
    void perEval_concurrentEvals_differentBindings_noInterference() throws Exception {
        // Each thread passes its own $rate; results must not bleed across threads.
        JsonataExpression expr = compile("$rate * amount");
        int threads    = 32;
        int iterations = 20;

        ExecutorService pool   = Executors.newFixedThreadPool(threads);
        CountDownLatch  ready  = new CountDownLatch(threads);
        CountDownLatch  go     = new CountDownLatch(1);
        AtomicInteger   errors = new AtomicInteger();

        List<Future<Void>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final double rate = t + 1;  // thread-specific rate
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                for (int i = 0; i < iterations; i++) {
                    JsonataBindings b = new JsonataBindings().bindValue("rate", num(rate));
                    double result = expr.evaluate(JsonNodeTestHelper.parseJson("{\"amount\":10}"), b).doubleValue();
                    if (Math.abs(result - rate * 10) > 1e-9) errors.incrementAndGet();
                }
                return null;
            }));
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        for (Future<Void> f : futures) f.get();

        assertEquals(0, errors.get(),
                "Per-evaluation bindings leaked across threads");
    }

    @Test
    void assign_concurrentReads_permanentBindingStable() throws Exception {
        JsonataExpression expr = compile("$multiplier * x");
        expr.assign("multiplier", num(3));

        int threads = 32;
        ExecutorService pool  = Executors.newFixedThreadPool(threads);
        CountDownLatch  ready = new CountDownLatch(threads);
        CountDownLatch  go    = new CountDownLatch(1);
        AtomicInteger   counter = new AtomicInteger(1);

        List<Future<double[]>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                int x = counter.getAndIncrement();
                double result = expr.evaluate(JsonNodeTestHelper.parseJson("{\"x\":" + x + "}")).doubleValue();
                return new double[]{x, result};
            }));
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        for (Future<double[]> f : futures) {
            double[] pair = f.get();
            assertEquals(pair[0] * 3, pair[1], 1e-9,
                    "Permanent binding produced wrong result for x=" + (int) pair[0]);
        }
    }
}
