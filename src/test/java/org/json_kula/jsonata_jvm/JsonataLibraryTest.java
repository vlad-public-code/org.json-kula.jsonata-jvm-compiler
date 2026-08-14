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
 * {@link JsonataBoundFunction}s via {@link JsonataExpressionFactory#compileLibrary(String)}.
 *
 * <p>A definition expression is ordinary JSONata: it binds named functions and returns the names of
 * the ones to export. The definitions below are the lambda examples from the language-feature suite
 * ({@code ProgrammingTest}, {@code HigherOrderFunctionsTest}) with their trailing invocation
 * replaced by that export list — the invocation now happens from Java, or from a different
 * expression, which is what a library is for.
 */
class JsonataLibraryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonataExpressionFactory FACTORY;

    @BeforeAll
    static void setup() {
        FACTORY = new JsonataExpressionFactory();
    }

    // -------------------------------------------------------------------------
    // Definition expressions (from docs.jsonata.org, as used in the language tests)
    // -------------------------------------------------------------------------

    /** The trigonometry example from https://docs.jsonata.org/programming, exporting $sin and $cos. */
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

              ["sin", "cos"]
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

    private static Map<String, JsonataBoundFunction> export(String definition) throws Exception {
        return FACTORY.compileLibrary(definition).getFunctions();
    }

    private static JsonataLibrary library(String definition) throws Exception {
        return FACTORY.compileLibrary(definition);
    }

    // =========================================================================
    // The motivating example — mutually recursive $sin / $cos
    // =========================================================================

    @Test
    void trig_exportedFunctionsMatchJavaMath() throws Exception {
        Map<String, JsonataBoundFunction> trig = export(TRIG);

        assertEquals(List.of("sin", "cos"), new ArrayList<>(trig.keySet()));
        assertEquals(Math.sin(1), call(trig.get("sin"), num(1)).doubleValue(), 1e-12);
        assertEquals(Math.sin(0), call(trig.get("sin"), num(0)).doubleValue(), 1e-12);
        assertEquals(Math.cos(0), call(trig.get("cos"), num(0)).doubleValue(), 1e-12);
        assertEquals(Math.cos(2), call(trig.get("cos"), num(2)).doubleValue(), 1e-12);
    }

    @Test
    void trig_definitionIsAValidJsonataExpressionOnItsOwn() throws Exception {
        // Compiled and evaluated as an ordinary expression it yields its export list — nothing in
        // the definition is library-specific syntax.
        JsonNode result = FACTORY.compile(TRIG).evaluate(NullNode.instance);
        assertEquals("[\"sin\",\"cos\"]", result.toString());
    }

    @Test
    void trig_usedFromAnotherExpression() throws Exception {
        Map<String, JsonataBoundFunction> trig = export(TRIG);

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
        // $sin calls $cos, which calls $factorial, which reduces with $product — none of which are
        // exported. Exporting only $sin must not break that chain.
        Map<String, JsonataBoundFunction> only = export(TRIG.replace("[\"sin\", \"cos\"]", "[\"sin\"]"));
        assertEquals(1, only.size());
        assertEquals(Math.sin(1), call(only.get("sin"), num(1)).doubleValue(), 1e-12);
    }

    @Test
    void trig_internalHelperCanBeExportedToo() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export(TRIG.replace("[\"sin\", \"cos\"]", "[\"factorial\", \"product\"]"));
        assertEquals(120, call(fns.get("factorial"), num(5)).intValue());
        assertEquals(12, call(fns.get("product"), num(3), num(4)).intValue());
    }

    @Test
    void trig_survivesRepeatedUse() throws Exception {
        // The per-evaluation lambda map is cleared after every evaluate(); a library must not be.
        Map<String, JsonataBoundFunction> trig = export(TRIG);
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
        // ProgrammingTest.lambda_assignAndInvoke, with "$volume(10, 10, 5)" replaced by the exports.
        Map<String, JsonataBoundFunction> fns =
                export("($volume := function($l, $w, $h){ $l * $w * $h }; [\"volume\"])");

        assertEquals(500, call(fns.get("volume"), num(10), num(10), num(5)).intValue());

        JsonataExpression expr = FACTORY.compile("$volume(2, 3, 4)");
        fns.forEach(expr::registerFunction);
        assertEquals(24, expr.evaluate(EMPTY_OBJECT).intValue());
    }

    @Test
    void lambdaCapturingBlockLocal_prefix() throws Exception {
        // ProgrammingTest.lambda_usesClosureOverContext — $prefix is captured, not exported.
        Map<String, JsonataBoundFunction> fns = export(
                "($prefix := \"Ph: \"; $fmt := function($n){ $prefix & $n }; [\"fmt\"])");

        assertEquals("Ph: 0203 544 1234", call(fns.get("fmt"), str("0203 544 1234")).textValue());
    }

    @Test
    void recursiveLambda_factorial() throws Exception {
        // ProgrammingTest.recursive_factorial
        Map<String, JsonataBoundFunction> fns = export(
                "($factorial := function($x){ $x <= 1 ? 1 : $x * $factorial($x-1) }; [\"factorial\"])");

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
                 )};
                 ["factorial"])""");

        assertEquals(3628800, call(fns.get("factorial"), num(10)).intValue());
    }

    @Test
    void greekLambda_fib() throws Exception {
        // ProgrammingTest.greekLambdaAsFunction
        Map<String, JsonataBoundFunction> fns = export(
                "($fib := λ($n) { $n <= 1 ? $n : $fib($n-1) + $fib($n-2) }; [\"fib\"])");

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
                 $add6 := $twice($add3);
                 ["add6", "add3"])""");

        assertEquals(13, call(fns.get("add6"), num(7)).intValue());
        assertEquals(10, call(fns.get("add3"), num(7)).intValue());
    }

    @Test
    void functionChaining_normalizeWhitespace() throws Exception {
        // ProgrammingTest.functionChaining_normalizeWhitespace — a ~> chain of built-ins.
        Map<String, JsonataBoundFunction> fns =
                export("($normalize := $uppercase ~> $trim; [\"normalize\"])");

        assertEquals("SOME WORDS", call(fns.get("normalize"), str("   Some   Words   ")).textValue());
    }

    @Test
    void partialApplication_first5() throws Exception {
        // ProgrammingTest.partial_substringFirst5
        Map<String, JsonataBoundFunction> fns = export("($first5 := $substring(?, 0, 5); [\"first5\"])");

        assertEquals("Hello", call(fns.get("first5"), str("Hello, World")).textValue());
    }

    @Test
    void higherOrderBuiltins_insideExportedBody() throws Exception {
        // HigherOrderFunctionsTest patterns ($map/$filter/$reduce) used inside library functions.
        Map<String, JsonataBoundFunction> fns = export("""
                (
                  $doubleAll := function($a){ $map($a, function($v){ $v * 2 }) };
                  $bigOnes := function($a, $min){ $filter($a, function($v){ $v > $min }) };
                  $total := function($a){ $reduce($a, function($acc, $v){ $acc + $v }) };

                  ["doubleAll", "bigOnes", "total"]
                )""");

        JsonataExpression expr = FACTORY.compile("$total($bigOnes($doubleAll([1,2,3,4,5]), 4))");
        fns.forEach(expr::registerFunction);
        // doubled: [2,4,6,8,10]; > 4: [6,8,10]; total: 24
        assertEquals(24, expr.evaluate(EMPTY_OBJECT).intValue());
    }

    // =========================================================================
    // The export list
    // =========================================================================

    @Test
    void exportList_acceptsDollarPrefixedNames() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export(TRIG.replace("[\"sin\", \"cos\"]", "[\"$sin\", \"$cos\"]"));

        assertEquals(List.of("sin", "cos"), new ArrayList<>(fns.keySet()),
                "map keys never carry the $");
        assertEquals(Math.sin(1), call(fns.get("sin"), num(1)).doubleValue(), 1e-12);
    }

    @Test
    void exportList_acceptsASingleUnwrappedName() throws Exception {
        // JSONata collapses one-element sequences, so a lone string is a valid export list.
        Map<String, JsonataBoundFunction> fns =
                export("($double := function($x){ $x * 2 }; \"double\")");

        assertEquals(List.of("double"), new ArrayList<>(fns.keySet()));
        assertEquals(8, call(fns.get("double"), num(4)).intValue());
    }

    @Test
    void exportList_mayBeComputed() throws Exception {
        // The list is an expression like any other — here it is built at definition time.
        Map<String, JsonataBoundFunction> fns = export("""
                (
                  $inc := function($x){ $x + 1 };
                  $dec := function($x){ $x - 1 };
                  $exports := ["inc", "dec"];
                  $withDec := true;

                  $withDec ? $exports : $exports[0]
                )""");

        assertEquals(List.of("inc", "dec"), new ArrayList<>(fns.keySet()));
        assertEquals(5, call(fns.get("inc"), num(4)).intValue());
        assertEquals(3, call(fns.get("dec"), num(4)).intValue());
    }

    @Test
    void exportList_orderIsPreserved() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export(TRIG.replace("[\"sin\", \"cos\"]", "[\"cos\", \"sin\", \"factorial\"]"));

        assertEquals(List.of("cos", "sin", "factorial"), new ArrayList<>(fns.keySet()));
    }

    @Test
    void definitionWithoutParentheses_singleBinding() throws Exception {
        // Not a block at all: one binding, then the export list, joined by ; inside parentheses is
        // the usual form — this checks the degenerate case of a definition that is just a list.
        Map<String, JsonataBoundFunction> fns =
                export("($double := function($x){ $x * 2 }; [\"double\"])");

        assertEquals(8, call(fns.get("double"), num(4)).intValue());
    }

    @Test
    void definitionMayBindAnExportNamesVariableItself() throws Exception {
        // The rewrite introduces a synthetic $__exportNames binding; a definition that already uses
        // that name must not be disturbed.
        Map<String, JsonataBoundFunction> fns = export("""
                (
                  $__exportNames := "not the export list";
                  $echo := function($x){ $x & "/" & $__exportNames };
                  ["echo"]
                )""");

        assertEquals("a/not the export list", call(fns.get("echo"), str("a")).textValue());
    }

    // =========================================================================
    // Constants — exported names that are not functions
    // =========================================================================

    @Test
    void constants_areSortedFromFunctionsByWhatTheyEvaluateTo() throws Exception {
        JsonataLibrary lib = library(TRIG.replace("[\"sin\", \"cos\"]", "[\"sin\", \"cos\", \"pi\"]"));

        assertEquals(List.of("sin", "cos"), new ArrayList<>(lib.getFunctions().keySet()));
        assertEquals(List.of("pi"), new ArrayList<>(lib.getConstants().keySet()));
        assertEquals(Math.PI, lib.getConstants().get("pi").doubleValue(), 1e-12);
    }

    @Test
    void constants_areEmptyWhenOnlyFunctionsAreExported() throws Exception {
        JsonataLibrary lib = library(TRIG);
        assertTrue(lib.getConstants().isEmpty());
        assertEquals(2, lib.getFunctions().size());
    }

    @Test
    void constants_ofEveryJsonType() throws Exception {
        JsonataLibrary lib = library("""
                (
                  $count := 42;
                  $name := "acme";
                  $active := true;
                  $nothing := null;
                  $rates := {"vat": 0.2, "duty": 0.1};
                  $regions := ["eu", "us"];

                  ["count", "name", "active", "nothing", "rates", "regions"]
                )""");

        Map<String, JsonNode> constants = lib.getConstants();
        assertTrue(lib.getFunctions().isEmpty());
        assertEquals(42, constants.get("count").intValue());
        assertEquals("acme", constants.get("name").textValue());
        assertTrue(constants.get("active").booleanValue());
        assertTrue(constants.get("nothing").isNull());
        assertEquals(0.2, constants.get("rates").get("vat").doubleValue(), 1e-12);
        assertEquals("[\"eu\",\"us\"]", constants.get("regions").toString());
    }

    @Test
    void constants_areComputedOnceAtCompileTime() throws Exception {
        // A constant is a value, not an expression: whatever the definition evaluated to.
        JsonataLibrary lib = library("($total := $sum([1..10]); [\"total\"])");
        assertEquals(55, lib.getConstants().get("total").intValue());
    }

    @Test
    void constants_dropStraightIntoAnExpression() throws Exception {
        JsonataLibrary lib = library("""
                (
                  $pi := 3.14159;
                  $area := function($r){ $pi * $r * $r };
                  ["area", "pi"]
                )""");

        JsonataExpression expr = FACTORY.compile("{\"area\": $area(2), \"pi\": $pi}");
        lib.getFunctions().forEach(expr::registerFunction);
        lib.getConstants().forEach(expr::assign);

        JsonNode result = expr.evaluate(EMPTY_OBJECT);
        assertEquals(12.56636, result.get("area").doubleValue(), 1e-9);
        assertEquals(3.14159, result.get("pi").doubleValue(), 1e-9);
    }

    @Test
    void constants_bindPerEvaluationToo() throws Exception {
        JsonataLibrary lib = library("($vat := 0.2; [\"vat\"])");

        JsonataExpression expr = FACTORY.compile("100 * (1 + $vat)");
        JsonataBindings bindings = new JsonataBindings();
        lib.getConstants().forEach(bindings::bindValue);

        assertEquals(120.0, expr.evaluate(EMPTY_OBJECT, bindings).doubleValue(), 1e-9);
    }

    @Test
    void constants_fromADefinitionThatMixesBothAndDependsOnItsOwnValues() throws Exception {
        JsonataLibrary lib = library("""
                (
                  $rates := {"standard": 0.2, "reduced": 0.05};
                  $gross := function($net, $band){ $net * (1 + $lookup($rates, $band)) };
                  ["gross", "rates"]
                )""");

        assertEquals(120.0,
                call(lib.getFunctions().get("gross"), num(100), str("standard")).doubleValue(), 1e-9);
        assertEquals(0.05, lib.getConstants().get("rates").get("reduced").doubleValue(), 1e-12);
    }

    @Test
    void constants_survivesClose() throws Exception {
        // Constants are ordinary nodes; only functions stop working.
        JsonataLibrary lib = library("($pi := 3.14159; $f := function($x){ $x }; [\"pi\", \"f\"])");
        JsonNode pi = lib.getConstants().get("pi");
        JsonataBoundFunction f = lib.getFunctions().get("f");
        lib.close();

        assertEquals(3.14159, pi.doubleValue(), 1e-9);
        assertEquals(3.14159, lib.getConstants().get("pi").doubleValue(), 1e-9);
        assertThrows(JsonataEvaluationException.class, () -> call(f, num(1)));
    }

    // =========================================================================
    // Signatures and argument handling
    // =========================================================================

    @Test
    void declaredSignature_isReportedAndCoerces() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export("($twice := function($x)<n:n>{ $x * 2 }; [\"twice\"])");

        assertEquals("<n:n>", fns.get("twice").getFunctionSignature());

        // The signature coerces the numeric string to a number at the call boundary.
        JsonataExpression expr = FACTORY.compile("$twice(\"21\")");
        fns.forEach(expr::registerFunction);
        assertEquals(42, expr.evaluate(EMPTY_OBJECT).intValue());
    }

    @Test
    void synthesizedSignature_isAllOptional() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export("($volume := function($l, $w, $h){ $l * $w * $h }; [\"volume\"])");

        assertEquals("<j?j?j?:j>", fns.get("volume").getFunctionSignature());
    }

    @Test
    void signatureOverride_appliesCoercion() throws Exception {
        JsonataLibrary lib = FACTORY.compileLibrary(
                "($twice := function($x){ $x * 2 }; [\"twice\"])",
                new JsonataLibraryOptions().signature("$twice", "<n:n>"));

        assertEquals("<n:n>", lib.getFunctions().get("twice").getFunctionSignature());

        JsonataExpression expr = FACTORY.compile("$twice(\"21\")");
        lib.getFunctions().forEach(expr::registerFunction);
        assertEquals(42, expr.evaluate(EMPTY_OBJECT).intValue());
    }

    @Test
    void missingArgument_becomesUndefinedNotAnError() throws Exception {
        // JSONata binds unsupplied parameters to undefined; the exported function does the same.
        Map<String, JsonataBoundFunction> fns = export(
                "($greet := function($name, $greeting){ ($greeting ? $greeting : \"Hello\") & \", \" & $name };"
                        + " [\"greet\"])");

        JsonataExpression expr = FACTORY.compile("$greet(\"Fred\")");
        fns.forEach(expr::registerFunction);
        assertEquals("Hello, Fred", expr.evaluate(EMPTY_OBJECT).textValue());
    }

    @Test
    void arrayArgument_isNotFlattened() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export("($count2 := function($a){ $count($a) }; [\"count2\"])");

        assertEquals(3, call(fns.get("count2"), MAPPER.readTree("[1,2,3]")).intValue());
    }

    // =========================================================================
    // Bindings integration
    // =========================================================================

    @Test
    void exportedFunctions_bindPerEvaluation() throws Exception {
        Map<String, JsonataBoundFunction> trig = export(TRIG);

        JsonataExpression expr = FACTORY.compile("$sin($angle)");
        JsonataBindings bindings = new JsonataBindings()
                .bindValue("angle", num(1))
                .bindFunctions(trig);

        assertEquals(Math.sin(1), expr.evaluate(EMPTY_OBJECT, bindings).doubleValue(), 1e-12);
    }

    @Test
    void freeVariable_resolvesAgainstDefinitionBindingsWhenCalledFromJava() throws Exception {
        JsonataLibrary lib = FACTORY.compileLibrary(
                "($withVat := function($net){ $net * (1 + $vatRate) }; [\"withVat\"])",
                new JsonataLibraryOptions()
                        .bindings(new JsonataBindings().bindValue("vatRate", num(0.2))));

        assertEquals(120.0, call(lib.getFunctions().get("withVat"), num(100)).doubleValue(), 1e-9);
    }

    // =========================================================================
    // A definition must be self-contained
    // =========================================================================

    @Test
    void error_definitionUsesAnUnboundVariable() {
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export("($withVat := function($net){ $net * (1 + $vatRate) }; [\"withVat\"])"));
        assertTrue(e.getMessage().contains("$vatRate"), e.getMessage());
        assertTrue(e.getMessage().contains("does not bind"), e.getMessage());
        assertTrue(e.getMessage().contains("JsonataLibraryOptions.bindings"),
                "the message should say how to fix it: " + e.getMessage());
    }

    @Test
    void error_definitionCallsAnUnboundFunction() {
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export("($f := function($x){ $helper($x) }; [\"f\"])"));
        assertTrue(e.getMessage().contains("$helper"), e.getMessage());
    }

    @Test
    void error_unboundNamesAreAllReported() {
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export("($f := function($x){ $x * $rate + $offset }; [\"f\"])"));
        assertTrue(e.getMessage().contains("$rate"), e.getMessage());
        assertTrue(e.getMessage().contains("$offset"), e.getMessage());
        assertTrue(e.getMessage().contains("are not JSONata built-ins"), e.getMessage());
    }

    @Test
    void error_typoInAnExportedName_isCaughtAsAnUnboundReference() {
        // The reason for rejecting rather than late-binding: this is a typo, not a hook.
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export("($rate := 0.2; $f := function($x){ $x * $rat }; [\"f\"])"));
        assertTrue(e.getMessage().contains("$rat"), e.getMessage());
    }

    @Test
    void selfContained_builtinsAreNotFreeVariables() throws Exception {
        Map<String, JsonataBoundFunction> fns = export("""
                (
                  $stats := function($a){ {"n": $count($a), "total": $sum($a), "avg": $average($a)} };
                  $shout := $uppercase ~> $trim;
                  $head := $substring(?, 0, 3);
                  ["stats", "shout", "head"]
                )""");
        assertEquals(3, fns.size());
        assertEquals(6, call(fns.get("stats"), MAPPER.readTree("[1,2,3]")).get("total").intValue());
    }

    @Test
    void selfContained_lambdaParametersAndInnerBindingsAreBound() throws Exception {
        Map<String, JsonataBoundFunction> fns = export("""
                ($f := function($x, $y){ ( $sum := $x + $y; $scale := 2; $sum * $scale ) };
                 ["f"])""");
        assertEquals(14, call(fns.get("f"), num(3), num(4)).intValue());
    }

    @Test
    void selfContained_forwardReferencesBetweenSiblingsAreBound() throws Exception {
        // $sin refers to $cos before it is bound — legal, and not a free variable.
        assertEquals(2, export(TRIG).size());
    }

    @Test
    void selfContained_pathBindingsAreBound() throws Exception {
        // @$v and #$i bind names for the steps that follow them.
        Map<String, JsonataBoundFunction> fns = export("""
                ($labels := function($a){ $a@$v#$i.($string($i) & ":" & $v) };
                 ["labels"])""");
        assertEquals("[\"0:a\",\"1:b\"]",
                call(fns.get("labels"), MAPPER.readTree("[\"a\",\"b\"]")).toString());
    }

    @Test
    void selfContained_namesSuppliedThroughOptionsAreAccepted() throws Exception {
        JsonataLibrary lib = FACTORY.compileLibrary(
                "($withVat := function($net){ $net * (1 + $vatRate) }; [\"withVat\"])",
                new JsonataLibraryOptions()
                        .bindings(new JsonataBindings().bindValue("vatRate", num(0.2))));

        assertEquals(120.0, call(lib.getFunctions().get("withVat"), num(100)).doubleValue(), 1e-9);
    }

    @Test
    void selfContained_functionsSuppliedThroughOptionsAreAccepted() throws Exception {
        JsonataBindings provided = new JsonataBindings().bindFunction("triple",
                new JsonataBoundFunction() {
                    public String getFunctionSignature() { return "<n:n>"; }
                    public JsonNode apply(JsonataFunctionArguments args) {
                        return MAPPER.getNodeFactory().numberNode(args.get(0).intValue() * 3);
                    }
                });

        JsonataLibrary lib = FACTORY.compileLibrary(
                "($f := function($x){ $triple($x) + 1 }; [\"f\"])",
                new JsonataLibraryOptions().bindings(provided));

        assertEquals(13, call(lib.getFunctions().get("f"), num(4)).intValue());
    }

    @Test
    void definitionInput_isAvailableToTheDefinition() throws Exception {
        JsonataLibrary lib = FACTORY.compileLibrary(
                "($factor := rates.vat; $rate := function($net){ $net * $factor }; [\"rate\"])",
                new JsonataLibraryOptions()
                        .input(MAPPER.readTree("{\"rates\": {\"vat\": 1.2}}")));

        assertEquals(120.0, call(lib.getFunctions().get("rate"), num(100)).doubleValue(), 1e-9);
    }

    // =========================================================================
    // Map shape and lifetime
    // =========================================================================

    @Test
    void library_keysNeverCarryTheDollar() throws Exception {
        JsonataLibrary lib = library(TRIG);
        assertNotNull(lib.getFunctions().get("sin"));
        assertNull(lib.getFunctions().get("$sin"), "map keys are the bare names");
        assertNull(lib.getFunctions().get("nope"));
    }

    @Test
    void library_mapsAreImmutable() throws Exception {
        JsonataLibrary lib = library(TRIG);
        assertThrows(UnsupportedOperationException.class, () -> lib.getFunctions().remove("sin"));
        assertThrows(UnsupportedOperationException.class, () -> lib.getConstants().clear());
    }

    @Test
    void library_closeReleasesTheFunctions() throws Exception {
        JsonataLibrary lib = FACTORY.compileLibrary(TRIG);
        JsonataBoundFunction sin = lib.getFunctions().get("sin");
        assertTrue(lib.isOpen());
        assertEquals(Math.sin(1), call(sin, num(1)).doubleValue(), 1e-12);

        lib.close();

        assertFalse(lib.isOpen());
        JsonataEvaluationException e =
                assertThrows(JsonataEvaluationException.class, () -> call(sin, num(1)));
        assertTrue(e.getMessage().contains("$sin"), "message should name the function: " + e.getMessage());
    }

    @Test
    void library_closeIsIdempotent() throws Exception {
        JsonataLibrary lib = FACTORY.compileLibrary(TRIG);
        lib.close();
        assertDoesNotThrow(lib::close);
    }

    @Test
    void library_reportsItsSource() throws Exception {
        JsonataLibrary lib = FACTORY.compileLibrary(TRIG);
        assertEquals(TRIG, lib.getSourceJsonata());
    }

    @Test
    void twoLibraries_areIndependent() throws Exception {
        Map<String, JsonataBoundFunction> a = export("($f := function($x){ $x + 1 }; [\"f\"])");
        JsonataLibrary b =
                FACTORY.compileLibrary("($f := function($x){ $x + 100 }; [\"f\"])");

        assertEquals(2, call(a.get("f"), num(1)).intValue());
        assertEquals(101, call(b.getFunctions().get("f"), num(1)).intValue());

        b.close();
        assertEquals(2, call(a.get("f"), num(1)).intValue(), "closing one library must not affect another");
    }

    // =========================================================================
    // Errors
    // =========================================================================

    @Test
    void error_definitionDoesNotReturnAnExportList() {
        // A definition that ends with its last binding returns that function, not a list of names.
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export("($sin := function($x){ $x };)"));
        assertTrue(e.getMessage().contains("array of function names"), e.getMessage());
        assertTrue(e.getMessage().contains("function"), e.getMessage());
    }

    @Test
    void error_definitionReturnsNothing() {
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export("($f := function($x){ $x }; nothing.here)"));
        assertTrue(e.getMessage().contains("returned nothing"), e.getMessage());
    }

    @Test
    void error_definitionReturnsNonStrings() {
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export("($f := function($x){ $x }; [1, 2])"));
        assertTrue(e.getMessage().contains("array of function names"), e.getMessage());
    }

    @Test
    void error_definitionReturnsAnEmptyList() {
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export("($f := function($x){ $x }; [])"));
        assertTrue(e.getMessage().contains("nothing") || e.getMessage().contains("empty"), e.getMessage());
    }

    @Test
    void error_nameNotDefinedAtTopLevel() {
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export(TRIG.replace("[\"sin\", \"cos\"]", "[\"sin\", \"tan\"]")));
        assertTrue(e.getMessage().contains("$tan"), e.getMessage());
        assertTrue(e.getMessage().contains("not defined"), e.getMessage());
    }

    @Test
    void error_nameBoundOnlyInsideANestedBlock() {
        String definition = """
                ($outer := function($x){(
                   $inner := function($y){ $y * 2 };
                   $inner($x)
                 )};
                 ["inner"])""";
        JsonataCompilationException e =
                assertThrows(JsonataCompilationException.class, () -> export(definition));
        assertTrue(e.getMessage().contains("$inner"), e.getMessage());
    }

    @Test
    void error_duplicateNamesInTheExportList() {
        JsonataCompilationException e = assertThrows(JsonataCompilationException.class,
                () -> export(TRIG.replace("[\"sin\", \"cos\"]", "[\"sin\", \"$sin\"]")));
        assertTrue(e.getMessage().contains("twice"), e.getMessage());
    }

    @Test
    void error_invalidDefinitionExpression() {
        assertThrows(JsonataCompilationException.class,
                () -> export("($f := function($x){ $x + }; [\"f\"])"));
    }

    @Test
    void error_definitionThatThrows() {
        assertThrows(JsonataCompilationException.class,
                () -> export("($f := $error(\"nope\"); $g := function($x){ $x }; [\"g\"])"));
    }

    @Test
    void error_callingAnExportedFunctionThatFails() throws Exception {
        Map<String, JsonataBoundFunction> fns =
                export("($boom := function($x){ $error(\"boom \" & $x) }; [\"boom\"])");

        JsonataEvaluationException e =
                assertThrows(JsonataEvaluationException.class, () -> call(fns.get("boom"), num(1)));
        assertTrue(e.getMessage().contains("$boom"), e.getMessage());
    }

    // =========================================================================
    // Concurrency
    // =========================================================================

    @Test
    void exportedFunctions_areThreadSafe() throws Exception {
        Map<String, JsonataBoundFunction> trig = export(TRIG);
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
        // The same definitions, used the ordinary way, keep working — libraries add a mode, they do
        // not change the default one.
        assertEquals(500, FACTORY
                .compile("($volume := function($l, $w, $h){ $l * $w * $h }; $volume(10, 10, 5))")
                .evaluate(EMPTY_OBJECT).intValue());
        assertEquals(24, FACTORY
                .compile("($factorial := function($x){ $x <= 1 ? 1 : $x * $factorial($x-1) }; $factorial(4))")
                .evaluate(EMPTY_OBJECT).intValue());
        assertEquals(Math.sin(1), FACTORY
                .compile(TRIG.replace("[\"sin\", \"cos\"]", "$sin(1)"))
                .evaluate(NullNode.instance).doubleValue(), 1e-12);
    }

    @Test
    void functionValues_areNotStrings() throws Exception {
        // A function value is a node of its own, not a string carrying a sentinel prefix — so no
        // string in the input document can be mistaken for one.
        JsonataExpression expr = FACTORY.compile("($f := function($x){ $x }; {\"f\": $f})");
        JsonNode result = expr.evaluate(EMPTY_OBJECT);
        JsonNode fn = result.get("f");
        assertFalse(fn.isTextual(), "a function value must not be a string: " + fn);
        assertEquals("function", FACTORY.compile("$type(f)").evaluate(result).textValue());
        assertEquals("\"\"", fn.toString(), "a function serialises as the empty string");
    }

    @Test
    void functionValues_outliveTheEvaluationThatCreatedThem() throws Exception {
        // Nothing expires: the node carries the closure, so a function produced by one evaluation
        // is still callable from a later, unrelated one.
        Map<String, JsonataBoundFunction> fns = export("($add := function($a,$b){ $a + $b }; [\"add\"])");
        for (int i = 0; i < 5; i++) {
            FACTORY.compile("1 + 1").evaluate(EMPTY_OBJECT);   // unrelated evaluations in between
        }
        assertEquals(7, call(fns.get("add"), num(3), num(4)).intValue());
    }
}
