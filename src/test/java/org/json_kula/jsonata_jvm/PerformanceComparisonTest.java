package org.json_kula.jsonata_jvm;

import com.api.jsonata4java.expressions.Expressions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Head-to-head performance comparison between jsonata-jvm-compiler and JSONata4Java.
 *
 * <p>The benchmark:
 * <ol>
 *   <li>Each library compiles the expression once (compilation time is reported
 *       separately but is NOT included in the evaluation throughput figure).</li>
 *   <li>100 000 evaluations are performed against the same pre-parsed JSON document.</li>
 *   <li>Wall-clock elapsed time and throughput (evaluations / second) are printed.</li>
 *   <li>Both libraries must agree on a set of key result values, confirming that
 *       the expression produces correct output.</li>
 * </ol>
 *
 * <p>The expression ({@code benchmark_expression.jsonata}) and the input document
 * ({@code benchmark_input.json}) live in {@code src/test/resources}.
 */
@Disabled("Please start it manually since it takes several minutes to measure performance")
class PerformanceComparisonTest {

    private static final int EVALUATIONS   = 100_000;
    private static final int WARMUP_ROUNDS = 1_000;   // not timed

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode inputJsonNode;   // pre-parsed — shared across all evaluations

    // Compiled expressions
    private static JsonataExpression jsj;   // jsonata-jvm-compiler
    private static Expressions       j4j;   // JSONata4Java

    @BeforeAll
    static void compile() throws Exception {
        // Resources loaded once
        String expressionSource = resource("benchmark/benchmark_expression.jsonata");
        String inputJsonString = resource("benchmark/benchmark_input.json");
        inputJsonNode    = MAPPER.readTree(inputJsonString);

        // ---- jsonata-jvm-compiler ----
        long jsjStart = System.nanoTime();
        jsj = new JsonataExpressionFactory().compile(expressionSource);
        long jsjCompile = System.nanoTime() - jsjStart;

        // ---- JSONata4Java ----
        long j4jStart = System.nanoTime();
        j4j = Expressions.parse(expressionSource);
        long j4jCompile = System.nanoTime() - j4jStart;

        System.out.printf("%n=== Compilation ===%n");
        System.out.printf("  jsonata-jvm-compiler  : %,d ms%n", jsjCompile / 1_000_000);
        System.out.printf("  JSONata4Java  : %,d ms%n", j4jCompile / 1_000_000);
    }

    // =========================================================================
    // Correctness
    // =========================================================================

    /**
     * Both libraries must return the same key values so we know they are
     * evaluating the same expression correctly.
     */
    @Test
    void bothLibraries_produceCorrectResults() throws Exception {
        JsonNode jsjResult = jsj.evaluate(inputJsonNode);
        JsonNode j4jResult = j4j.evaluate(inputJsonNode);

        // Company name  (field key is "company" in the output object)
        assertEquals("Acme Corporation",
                jsjResult.path("company").asText(),
                "jsonata-jvm-compiler: company");
        assertEquals("Acme Corporation",
                j4jResult.path("company").asText(),
                "JSONata4Java: company");

        // Founded year
        assertEquals(1985, jsjResult.path("founded").asInt(), "jsonata-jvm-compiler: founded");
        assertEquals(1985, j4jResult.path("founded").asInt(), "JSONata4Java: founded");

        // Total employees  (5 + 7 + 4 + 3 = 19)
        assertEquals(19, jsjResult.path("workforce").path("totalEmployees").asInt(),
                "jsonata-jvm-compiler: totalEmployees");
        assertEquals(19, j4jResult.path("workforce").path("totalEmployees").asInt(),
                "JSONata4Java: totalEmployees");

        // Department count
        assertEquals(4, jsjResult.path("workforce").path("departments").asInt(),
                "jsonata-jvm-compiler: departments");
        assertEquals(4, j4jResult.path("workforce").path("departments").asInt(),
                "JSONata4Java: departments");

        // Active product count  (P001..P007 minus P004 = 6)
        assertEquals(6, jsjResult.path("catalog").path("active").asInt(),
                "jsonata-jvm-compiler: active products");
        assertEquals(6, j4jResult.path("catalog").path("active").asInt(),
                "JSONata4Java: active products");

        // Delivered orders  (ORD-001, ORD-003, ORD-006)
        assertEquals(3, jsjResult.path("orders").path("delivered").asInt(),
                "jsonata-jvm-compiler: delivered orders");
        assertEquals(3, j4jResult.path("orders").path("delivered").asInt(),
                "JSONata4Java: delivered orders");

        // SKU count  (P001..P007 appear in orders: P001,P003,P006,P005,P002,P007,P004 = 7)
        assertEquals(7, jsjResult.path("orders").path("skuCount").asInt(),
                "jsonata-jvm-compiler: skuCount");
        assertEquals(7, j4jResult.path("orders").path("skuCount").asInt(),
                "JSONata4Java: skuCount");

        // Summary string starts correctly
        String jsjSummary = jsjResult.path("summary").asText();
        String j4jSummary = j4jResult.path("summary").asText();
        assertTrue(jsjSummary.startsWith("Company Acme Corporation"),
                "jsonata-jvm-compiler: unexpected summary: " + jsjSummary);
        assertTrue(j4jSummary.startsWith("Company Acme Corporation"),
                "JSONata4Java: unexpected summary: " + j4jSummary);
    }

    // =========================================================================
    // Benchmarks
    // =========================================================================

    @Test
    void benchmark_jsonata_jvm_compiler_100k_evaluations() throws Exception {
        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            jsj.evaluate(inputJsonNode);
        }

        long start = System.nanoTime();
        for (int i = 0; i < EVALUATIONS; i++) {
            jsj.evaluate(inputJsonNode);
        }
        long elapsed = System.nanoTime() - start;

        printResult("jsonata-jvm-compiler", elapsed);

        // Sanity: must finish in a reasonable time (60 s ceiling)
        assertTrue(elapsed < 60_000_000_000L,
                "jsonata-jvm-compiler took longer than 60 s for " + EVALUATIONS + " evaluations");
    }

    @Test
    void benchmark_jsonata4Java_100k_evaluations() throws Exception {
        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            j4j.evaluate(inputJsonNode);
        }

        long start = System.nanoTime();
        for (int i = 0; i < EVALUATIONS; i++) {
            j4j.evaluate(inputJsonNode);
        }
        long elapsed = System.nanoTime() - start;

        printResult("JSONata4Java", elapsed);

        assertTrue(elapsed < 120_000_000_000L,
                "JSONata4Java took longer than 120 s for " + EVALUATIONS + " evaluations");
    }

    /**
     * Runs both benchmarks in sequence and prints a side-by-side summary with
     * the speedup ratio. This is the primary "comparison" test.
     */
    @Test
    void benchmark_comparison_sideBy_side() throws Exception {
        // Warmup both
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            jsj.evaluate(inputJsonNode);
            j4j.evaluate(inputJsonNode);
        }

        // jsonata-jvm-compiler
        long jsjStart = System.nanoTime();
        for (int i = 0; i < EVALUATIONS; i++) {
            jsj.evaluate(inputJsonNode);
        }
        long jsjElapsed = System.nanoTime() - jsjStart;

        // JSONata4Java
        long j4jStart = System.nanoTime();
        for (int i = 0; i < EVALUATIONS; i++) {
            j4j.evaluate(inputJsonNode);
        }
        long j4jElapsed = System.nanoTime() - j4jStart;

        double jsjThroughput = throughput(jsjElapsed);
        double j4jThroughput = throughput(j4jElapsed);
        double ratio = jsjThroughput / j4jThroughput;

        System.out.printf("%n=== Side-by-side comparison (%,d evaluations) ===%n", EVALUATIONS);
        System.out.printf("  %-16s  %,10.0f eval/s   (%,d ms total)%n",
                "jsonata-jvm-compiler", jsjThroughput, jsjElapsed / 1_000_000);
        System.out.printf("  %-16s  %,10.0f eval/s   (%,d ms total)%n",
                "JSONata4Java", j4jThroughput, j4jElapsed / 1_000_000);
        System.out.printf("  Speedup: jsonata-jvm-compiler is %.2fx %s than JSONata4Java%n",
                ratio > 1 ? ratio : 1.0 / ratio,
                ratio > 1 ? "faster" : "slower");

        // Both must complete within their respective ceilings
        assertTrue(jsjElapsed < 60_000_000_000L,  "jsonata-jvm-compiler exceeded 60 s");
        assertTrue(j4jElapsed < 120_000_000_000L, "JSONata4Java exceeded 120 s");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void printResult(String label, long elapsedNs) {
        System.out.printf("%n=== %s (%,d evaluations) ===%n", label, EVALUATIONS);
        System.out.printf("  Elapsed  : %,d ms%n", elapsedNs / 1_000_000);
        System.out.printf("  Throughput: %,.0f eval/s%n", throughput(elapsedNs));
    }

    private static double throughput(long elapsedNs) {
        return EVALUATIONS / (elapsedNs / 1_000_000_000.0);
    }

    private static String resource(String name) throws IOException {
        try (InputStream is = PerformanceComparisonTest.class
                .getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Test resource not found: " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
