package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for function libraries — turning a JSONata definition expression into
 * {@link JsonataBoundFunction}s via
 * {@link JsonataExpressionFactory#compileFunctions(List, String)}.
 *
 * <p>The definition expressions are the lambda examples from the language-feature suite
 * ({@code ProgrammingTest}, {@code HigherOrderFunctionsTest}) with their trailing invocation
 * removed — the invocation now happens from Java, or from a different expression, which is exactly
 * what a library is for.
 */
class JsonataFunctionLibraryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonataExpressionFactory FACTORY;

    @BeforeAll
    static void setup() {
        FACTORY = new JsonataExpressionFactory();
    }

    // -------------------------------------------------------------------------
    // Definition expressions (from docs.jsonata.org, as used in the language tests)
    // -------------------------------------------------------------------------

    /** The trigonometry example from https://docs.jsonata.org/programming, minus its plot. */
    private static final String TRIG = """
            (
              $pi := 3.1415926535897932384626;

              /* Factorial is the product of the integers 1..n */
              $product := function($a, $b) { $a * $b };
              $factorial := function($n) { $n = 0 ? 1 : $reduce([1..$n], $product) };

              $sin := function($x){ /* define sine in terms of cosine */
                $cos($x - $pi/2)
              };
              $cos := function($x){ /* Derive cosine by expanding Maclaurin series */
                $x > $pi ? $cos($x - 2 * $pi) : $x < -$pi ? $cos($x + 2 * $pi) :
                  $sum([0..12].($power(-1, $) * $power($x, 2*$) / $factorial(2*$)))
              };
            )
            """;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static JsonNode num(double v) {
        return MAPPER.convertValue(v, JsonNode.class);
    }

    private static JsonNode str(String s) {
        return MAPPER.convertValue(s, JsonNode.class);
    }

    /** Calls an exported function directly from Java, outside any evaluation. */
    private static JsonNode call(JsonataBoundFunction fn, JsonNode... args) throws Exception {
        return fn.apply(new JsonataFunctionArguments(List.of(args)));
    }

    private static Map<String, JsonataBoundFunction> export(String definition, String... names)
            throws Exception {
        return FACTORY.compileFunctions(List.of(names), definition);
    }

    // =========================================================================
    // The motivating example — mutually recursive $sin / $cos
    // =========================================================================

    @Test
    void trig_exportedFunctionsMatchJavaMath() throws Exception {
        Map<String, JsonataBoundFunction> trig = export(TRIG, "$sin", "$cos");

        assertEquals(Math.sin(1), call(trig.get("sin"), num(1)).doubleValue(), 1e-12);
        assertEquals(Math.sin(0), call(trig.get("sin"), num(0)).doubleValue(), 1e-12);
        assertEquals(Math.cos(0), call(trig.get("cos"), num(0)).doubleValue(), 1e-12);
        assertEquals(Math.cos(2), call(trig.get("cos"), num(2)).doubleValue(), 1e-12);
    }

    @Test
    void trig_usedFromAnotherExpression() throws Exception {
        Map<String, JsonataBoundFunction> trig = export(TRIG, "$sin", "$cos");

        JsonataExpression expr = FACTORY.compile("angles.$sin($)");
        trig.forEach(expr::registerFunction);

        JsonNode result = expr.evaluate(MAPPER.readTree("{\"angles\": [0, 1, 2]}"));
        assertTrue(result.isArray());
        assertEquals(Math.sin(0), result.get(0).doubleValue(), 1e-12);
        assertEquals(Math.sin(1), result.get(1).doubleValue(), 1e-12);
        assertEquals(Math.sin(2), result.get(2).doubleValue(), 1e-12);
    }

    @Test
    void trig_nonExportedHelpersStayReachable() throws Exception {
        // $sin calls $cos, which calls $factorial, which reduces with $product — none of which
        // are exported. Exporting only $sin must not break that chain.
        Map<String, JsonataBoundFunction> only = export(TRIG, "$sin");
        assertEquals(1, only.size());
        assertEquals(Math.sin(1), call(only.get("sin"), num(1)).doubleValue(), 1e-12);
    }

    @Test
    void trig_internalHelperCanBeExportedToo() throws Exception {
        Map<String, JsonataBoundFunction> fns = export(TRIG, "$factorial", "$product");
        assertEquals(120, call(fns.get("factorial"), num(5)).intValue());
        assertEquals(12, call(fns.get("product"), num(3), num(4)).intValue());
    }

    @Test
    void trig_survivesRepeatedUse() throws Exception {
        // The per-evaluation lambda map is cleared after every evaluate(); a library must not be.
        Map<String, JsonataBoundFunction> trig = export(TRIG, "$sin");
        JsonataExpression expr = FACTORY.compile("$sin(1)");
        trig.forEach(expr::registerFunction);

        for (int i = 0; i < 200; i++) {
            assertEquals(Math.sin(1), expr.evaluate(EMPTY_OBJECT).doubleValue(), 1e-12);
        }
    }

    // =========================================================================
    // Lambda shapes from the language-feature suite
    // =========================================================================

    @Test
    void multiParamLambda_volume() throws Exception {
        // ProgrammingTest.lambda_assignAndInvoke, minus the "$volume(10, 10, 5)" call.
        Map<String, JsonataBoundFunction> fns =
                export("($volume := function($l, $w, $h){ $l * $w * $h };)", "$volume");

        assertEquals(500, call(fns.get("volume"), num(10), num(10), num(5)).intValue());

        JsonataExpression expr = FACTORY.compile("$volume(2, 3, 4)");
        fns.forEach(expr::registerFunction);
        assertEquals(24, expr.evaluate(EMPTY_OBJECT).intValue());
    }

    @Test
    void lambdaCapturingBlockLocal_prefix() throws Exception {
        // ProgrammingTest.lambda_usesClosureOverContext — $prefix is captured, not exported.
        Map<String, JsonataBoundFunction> fns = export(
                "($prefix := \"Ph: \"; $fmt := function($n){ $prefix & $n };)", "$fmt");

        assertEquals("Ph: 0203 544 1234", call(fns.get("fmt"), str("0203 544 1234")).textValue());
    }

    @Test
    void recursiveLambda_factorial() throws Exception {
        // ProgrammingTest.recursive_factorial
        Map<String, JsonataBoundFunction> fns = export(
                "($factorial := function($x){ $x <= 1 ? 1 : $x * $factorial($x-1) };)", "$factorial");

        assertEquals(24, call(fns.get("factorial"), num(4)).intValue());
        assertEquals(3628800, call(fns.get("factorial"), num(10)).intValue());
    }

    @Test
    void tailRecursiveLambda_factorialWithAccumulator() throws Exception {
        // ProgrammingTest.recursive_factorial_largish — exercises the TCO trampoline through the
        // exported entry point.
        Map<String, JsonataBoundFunction> fns = export("""
                ($factorial := function($x){(
                   $iter := function($x, $acc) {
                     $x <= 1 ? $acc : $iter($x - 1, $x * $acc)
                   };
                   $iter($x, 1)
                 )};)""", "$factorial");

        assertEquals(3628800, call(fns.get("factorial"), num(10)).intValue());
    }

    @Test
    void greekLambda_fib() throws Exception {
        // ProgrammingTest.greekLambdaAsFunction
        Map<String, JsonataBoundFunction> fns = export(
                "($fib := λ($n) { $n <= 1 ? $n : $fib($n-1) + $fib($n-2) };)", "$fib");

        JsonataExpression expr = FACTORY.compile("[1,2,3,4,5,6,7,8,9].$fib($)");
        fns.forEach(expr::registerFunction);
        assertEquals(MAPPER.readTree("[1,1,2,3,5,8,13,21,34]").toString(),
                expr.evaluate(EMPTY_OBJECT).toString());
    }

    @Test
    void higherOrderLambda_returnedFunctionIsExportable() throws Exception {
        // ProgrammingTest.higherOrder_twiceApplied — $add6 is not a literal lambda but the result
        // of calling one, so its arity is only known at call time.
        Map<String, JsonataBoundFunction> fns = export("""
                ($twice := function($f) { function($x){ $f($f($x)) } };
                 $add3 := function($y){ $y + 3 };
                 $add6 := $twice($add3);)""", "$add6", "$add3");

        assertEquals(13, call(fns.get("add6"), num(7)).intValue());
        assertEquals(10, call(fns.get("add3"), num(7)).intValue());
    }

    @Test
    void functionChaining_normalizeWhitespace() throws Exception {
        // ProgrammingTest.functionChaining_normalizeWhitespace — a ~> chain of built-ins.
        Map<String, JsonataBoundFunction> fns =
                export("($normalize := $uppercase ~> $trim;)", "$normalize");

        assertEquals("SOME WORDS", call(fns.get("normalize"), str("   Some   Words   ")).textValue());
    }

    @Test
    void partialApplication_first5() throws Exception {
        // ProgrammingTest.partial_substringFirst5
        Map<String, JsonataBoundFunction> fns = export("($first5 := $substring(?, 0, 5);)", "$first5");

        assertEquals("Hello", call(fns.get("first5"), str("Hello, World")).textValue());
    }

    @Test
    void higherOrderBuiltins_insideExportedBody() throws Exception {
        // HigherOrderFunctionsTest patterns ($map/$filter/$reduce) used inside a library function.
        Map<String, JsonataBoundFunction> fns = export("""
                (
                  $doubleAll := function($a){ $map($a, function($v){ $v * 2 }) };
                  $bigOnes := function($a, $min){ $filter($a, function($v){ $v > $min }) };
                  $total := function($a){ $reduce($a, function($acc, $v){ $acc + $v }) };
                )""", "$doubleAll", "$bigOnes", "$total");

        JsonataExpression expr = FACTORY.compile("$total($bigOnes($doubleAll([1,2,3,4,5]), 4))");
        fns.forEach(expr::registerFunction);
        // doubled: [2,4,6,8,10]; > 4: [6,8,10]; total: 24
        assertEquals(24, expr.evaluate(EMPTY_OBJECT).intValue());
    }

    @Test
    void definitionWithoutParentheses_singleBinding() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export("$double := function($x){ $x * 2 }", "$double");

        assertEquals(8, call(fns.get("double"), num(4)).intValue());
    }

    // =========================================================================
    // Signatures and argument handling
    // =========================================================================

    @Test
    void declaredSignature_isReportedAndCoerces() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export("($twice := function($x)<n:n>{ $x * 2 };)", "$twice");

        assertEquals("<n:n>", fns.get("twice").getFunctionSignature());

        // The signature coerces the numeric string to a number at the call boundary.
        JsonataExpression expr = FACTORY.compile("$twice(\"21\")");
        fns.forEach(expr::registerFunction);
        assertEquals(42, expr.evaluate(EMPTY_OBJECT).intValue());
    }

    @Test
    void synthesizedSignature_isAllOptional() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export("($volume := function($l, $w, $h){ $l * $w * $h };)", "$volume");

        assertEquals("<j?j?j?:j>", fns.get("volume").getFunctionSignature());
    }

    @Test
    void signatureOverride_appliesCoercion() throws Exception {
        JsonataFunctionLibrary lib = FACTORY.compileFunctionLibrary(
                List.of("$twice"), "($twice := function($x){ $x * 2 };)",
                new JsonataFunctionLibraryOptions().signature("$twice", "<n:n>"));

        assertEquals("<n:n>", lib.get("twice").getFunctionSignature());

        JsonataExpression expr = FACTORY.compile("$twice(\"21\")");
        lib.asMap().forEach(expr::registerFunction);
        assertEquals(42, expr.evaluate(EMPTY_OBJECT).intValue());
    }

    @Test
    void missingArgument_becomesUndefinedNotAnError() throws Exception {
        // JSONata binds unsupplied parameters to undefined; the exported function does the same.
        Map<String, JsonataBoundFunction> fns = export(
                "($greet := function($name, $greeting){ ($greeting ? $greeting : \"Hello\") & \", \" & $name };)",
                "$greet");

        JsonataExpression expr = FACTORY.compile("$greet(\"Fred\")");
        fns.forEach(expr::registerFunction);
        assertEquals("Hello, Fred", expr.evaluate(EMPTY_OBJECT).textValue());
    }

    @Test
    void arrayArgument_isNotFlattened() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export("($count2 := function($a){ $count($a) };)", "$count2");

        assertEquals(3, call(fns.get("count2"), MAPPER.readTree("[1,2,3]")).intValue());
    }

    // =========================================================================
    // Bindings integration
    // =========================================================================

    @Test
    void exportedFunctions_bindPerEvaluation() throws Exception {
        Map<String, JsonataBoundFunction> trig = export(TRIG, "$sin");

        JsonataExpression expr = FACTORY.compile("$sin($angle)");
        JsonataBindings bindings = new JsonataBindings()
                .bindValue("angle", num(1))
                .bindFunctions(trig);

        assertEquals(Math.sin(1), expr.evaluate(EMPTY_OBJECT, bindings).doubleValue(), 1e-12);
    }

    @Test
    void freeVariable_resolvesAgainstDefinitionBindingsWhenCalledFromJava() throws Exception {
        JsonataFunctionLibrary lib = FACTORY.compileFunctionLibrary(
                List.of("$withVat"), "($withVat := function($net){ $net * (1 + $vatRate) };)",
                new JsonataFunctionLibraryOptions()
                        .bindings(new JsonataBindings().bindValue("vatRate", num(0.2))));

        assertEquals(120.0, call(lib.get("withVat"), num(100)).doubleValue(), 1e-9);
    }

    @Test
    void freeVariable_resolvesAgainstCallerBindingsInsideAnExpression() throws Exception {
        // Documented semantics: a name the definition never binds is late-bound to the caller.
        Map<String, JsonataBoundFunction> fns =
                export("($withVat := function($net){ $net * (1 + $vatRate) };)", "$withVat");

        JsonataExpression expr = FACTORY.compile("$withVat(100)");
        fns.forEach(expr::registerFunction);
        expr.assign("vatRate", num(0.5));

        assertEquals(150.0, expr.evaluate(EMPTY_OBJECT).doubleValue(), 1e-9);
    }

    @Test
    void definitionInput_isAvailableToTheDefinition() throws Exception {
        JsonataFunctionLibrary lib = FACTORY.compileFunctionLibrary(
                List.of("$rate"),
                "($factor := rates.vat; $rate := function($net){ $net * $factor };)",
                new JsonataFunctionLibraryOptions()
                        .input(MAPPER.readTree("{\"rates\": {\"vat\": 1.2}}")));

        assertEquals(120.0, call(lib.get("rate"), num(100)).doubleValue(), 1e-9);
    }

    // =========================================================================
    // Naming, map shape and lifetime
    // =========================================================================

    @Test
    void names_acceptedWithAndWithoutDollar_keysNeverCarryIt() throws Exception {
        Map<String, JsonataBoundFunction> fns = FACTORY.compileFunctions(
                List.of("$sin", "cos"), TRIG);

        assertEquals(List.of("sin", "cos"), new ArrayList<>(fns.keySet()));
        assertNotNull(fns.get("sin"));
        assertNotNull(fns.get("cos"));
    }

    @Test
    void library_getAcceptsEitherForm() throws Exception {
        JsonataFunctionLibrary lib = FACTORY.compileFunctionLibrary(List.of("$sin"), TRIG);
        assertSame(lib.get("sin"), lib.get("$sin"));
        assertNull(lib.get("nope"));
    }

    @Test
    void library_mapIsImmutable() throws Exception {
        JsonataFunctionLibrary lib = FACTORY.compileFunctionLibrary(List.of("$sin"), TRIG);
        assertThrows(UnsupportedOperationException.class, () -> lib.asMap().remove("sin"));
    }

    @Test
    void library_closeReleasesTheFunctions() throws Exception {
        JsonataFunctionLibrary lib = FACTORY.compileFunctionLibrary(List.of("$sin"), TRIG);
        JsonataBoundFunction sin = lib.get("sin");
        assertTrue(lib.isOpen());
        assertTrue(lib.lambdaCount() > 0);
        assertEquals(Math.sin(1), call(sin, num(1)).doubleValue(), 1e-12);

        lib.close();

        assertFalse(lib.isOpen());
        JsonataEvaluationException e =
                assertThrows(JsonataEvaluationException.class, () -> call(sin, num(1)));
        assertTrue(e.getMessage().contains("$sin"), "message should name the function: " + e.getMessage());
    }

    @Test
    void library_closeIsIdempotent() throws Exception {
        JsonataFunctionLibrary lib = FACTORY.compileFunctionLibrary(List.of("$sin"), TRIG);
        lib.close();
        assertDoesNotThrow(lib::close);
    }

    @Test
    void library_reportsItsSource() throws Exception {
        JsonataFunctionLibrary lib = FACTORY.compileFunctionLibrary(List.of("$sin"), TRIG);
        assertEquals(TRIG, lib.getSourceJsonata());
    }

    @Test
    void twoLibraries_areIndependent() throws Exception {
        Map<String, JsonataBoundFunction> a = export("($f := function($x){ $x + 1 };)", "$f");
        JsonataFunctionLibrary b = FACTORY.compileFunctionLibrary(
                List.of("$f"), "($f := function($x){ $x + 100 };)");

        assertEquals(2, call(a.get("f"), num(1)).intValue());
        assertEquals(101, call(b.get("f"), num(1)).intValue());

        b.close();
        assertEquals(2, call(a.get("f"), num(1)).intValue(), "closing one library must not affect another");
    }

    // =========================================================================
    // Errors
    // =========================================================================

    @Test
    void error_nameNotDefinedAtTopLevel() {
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export(TRIG, "$tan"));
        assertTrue(e.getMessage().contains("$tan"), e.getMessage());
        assertTrue(e.getMessage().contains("not defined"), e.getMessage());
    }

    @Test
    void error_nameBoundOnlyInsideANestedBlock() {
        String definition = """
                ($outer := function($x){(
                   $inner := function($y){ $y * 2 };
                   $inner($x)
                 )};)""";
        assertThrows(JsonataCompilationException.class, () -> export(definition, "$inner"));
    }

    @Test
    void error_nameBoundToALiteralValue() {
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export(TRIG, "$pi"));
        assertTrue(e.getMessage().contains("not a function"), e.getMessage());
    }

    @Test
    void error_nameBoundToAComputedNonFunction() {
        // Not detectable from the AST — caught after the definition runs.
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export("($label := $string(42);)", "$label"));
        assertTrue(e.getMessage().contains("not a function"), e.getMessage());
    }

    @Test
    void error_duplicateNames() {
        assertThrows(IllegalArgumentException.class, () -> export(TRIG, "$sin", "sin"));
    }

    @Test
    void error_noNamesRequested() {
        assertThrows(IllegalArgumentException.class, () -> FACTORY.compileFunctions(List.of(), TRIG));
    }

    @Test
    void error_invalidDefinitionExpression() {
        assertThrows(JsonataCompilationException.class,
                () -> export("($f := function($x){ $x + };)", "$f"));
    }

    @Test
    void error_definitionThatThrows() {
        assertThrows(JsonataCompilationException.class,
                () -> export("($f := $error(\"nope\"); $g := function($x){ $x };)", "$g"));
    }

    @Test
    void error_callingAnExportedFunctionThatFails() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export("($boom := function($x){ $error(\"boom \" & $x) };)", "$boom");

        JsonataEvaluationException e =
                assertThrows(JsonataEvaluationException.class, () -> call(fns.get("boom"), num(1)));
        assertTrue(e.getMessage().contains("$boom"), e.getMessage());
    }

    // =========================================================================
    // Concurrency
    // =========================================================================

    @Test
    void exportedFunctions_areThreadSafe() throws Exception {
        Map<String, JsonataBoundFunction> trig = export(TRIG, "$sin");
        JsonataExpression expr = FACTORY.compile("$sin($angle)");
        trig.forEach(expr::registerFunction);

        int threads = 8;
        int iterations = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final double angle = t / 4.0;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        JsonataBindings b = new JsonataBindings().bindValue("angle", num(angle));
                        JsonNode result = expr.evaluate(EMPTY_OBJECT, b);
                        if (Math.abs(result.doubleValue() - Math.sin(angle)) > 1e-12) {
                            throw new AssertionError("wrong value for " + angle + ": " + result);
                        }
                        successes.incrementAndGet();
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish in time");

        assertNull(failure.get(), () -> "concurrent call failed: " + failure.get());
        assertEquals(threads * iterations, successes.get());
    }

    // =========================================================================
    // Regression — ordinary expressions are unaffected
    // =========================================================================

    @Test
    void ordinaryLambdas_stillEvaluateNormally() throws Exception {
        // The same definitions, used the ordinary way, keep working — libraries add a mode, they
        // do not change the default one.
        assertEquals(500, FACTORY
                .compile("($volume := function($l, $w, $h){ $l * $w * $h }; $volume(10, 10, 5))")
                .evaluate(EMPTY_OBJECT).intValue());
        assertEquals(24, FACTORY
                .compile("($factorial := function($x){ $x <= 1 ? 1 : $x * $factorial($x-1) }; $factorial(4))")
                .evaluate(EMPTY_OBJECT).intValue());
        assertEquals(Math.sin(1), FACTORY
                .compile(TRIG.strip().substring(0, TRIG.strip().length() - 1) + " $sin(1))")
                .evaluate(NullNode.instance).doubleValue(), 1e-12);
    }

    @Test
    void ordinaryLambdaTokens_stayScopedToTheirEvaluation() throws Exception {
        // A lambda produced by a normal expression is still per-evaluation: exporting it as a value
        // and calling it later is not supported (and must not silently resolve to something else).
        JsonataExpression expr = FACTORY.compile("($f := function($x){ $x }; {\"f\": $f})");
        JsonNode result = expr.evaluate(EMPTY_OBJECT);
        assertTrue(result.get("f").isTextual(), "lambda values are carried as tokens");
        assertFalse(result.get("f").textValue().contains("/"),
                "an ordinary lambda token must not be scope-qualified: " + result.get("f"));
    }
}
