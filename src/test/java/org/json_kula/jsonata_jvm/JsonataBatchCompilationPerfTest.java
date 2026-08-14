package org.json_kula.jsonata_jvm;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the wall-clock cost of compiling 20 expressions as a single batch
 * ({@link JsonataExpressionFactory#compileAll}) versus one at a time
 * ({@link JsonataExpressionFactory#compile}).
 *
 * <p>Each {@code javac} invocation carries a large fixed overhead (compiler bootstrap, platform
 * symbol loading, classpath indexing) that dwarfs the marginal cost of one small generated class.
 * Batching pays that fixed cost once instead of 20 times, so the batch must be meaningfully faster.
 * The assertion is deliberately conservative — batch strictly faster than one-by-one — because the
 * advantage is structural (1 compiler invocation vs 20) and does not depend on machine speed; the
 * measured ratio is printed for visibility.
 *
 * <h2>Running it</h2>
 *
 * <p>{@code @Disabled} keeps this out of a normal build, and JUnit honours that even when the class
 * is named with {@code -Dtest=} — selecting it on its own reports success without running anything.
 * Switch the condition off to actually run it, and pass that inside {@code argLine} so it reaches
 * the forked JVM: a bare {@code -D} stays with the Maven JVM and has no effect on the tests.
 *
 * <pre>
 *   mvn test -Dtest=JsonataBatchCompilationPerfTest
 *       -DargLine="-Djunit.jupiter.conditions.deactivate=org.junit.jupiter.engine.extension.DisabledCondition"
 * </pre>
 *
 * <p>Measured on OpenJDK 21 / Windows 11 the ratio is around 10x for these 20 expressions, which is
 * the figure the README quotes. It is not a constant: the saving is one fixed {@code javac} cost per
 * batch instead of one per expression, so it moves with the batch size and with how fast the host
 * starts a compiler.
 */
@Disabled("Please start it manually")
class JsonataBatchCompilationPerfTest {

    private static final int EXPRESSION_COUNT = 20;

    /**
     * {@code count} distinct, non-trivial expressions so every one is a genuine, separate
     * compilation. They are only ever compiled (never evaluated), so purely-syntactic distinctness is
     * all that matters; the factory has no compilation cache, so the same list can be reused safely.
     */
    private static List<String> expressions(int count) {
        List<String> exprs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            exprs.add("(a + " + i + ") * b - $sum([1.." + (i + 2) + "]) + c / " + (i + 1));
        }
        return exprs;
    }

    private static long timeOneByOne(JsonataExpressionFactory factory, List<String> exprs)
            throws JsonataCompilationException {
        long start = System.nanoTime();
        List<JsonataExpression> out = new ArrayList<>(exprs.size());
        for (String expr : exprs) {
            out.add(factory.compile(expr));
        }
        long elapsed = System.nanoTime() - start;
        assertEquals(exprs.size(), out.size());
        return elapsed;
    }

    private static long timeBatch(JsonataExpressionFactory factory, List<String> exprs)
            throws JsonataCompilationException {
        long start = System.nanoTime();
        List<JsonataExpression> out = factory.compileAll(exprs);
        long elapsed = System.nanoTime() - start;
        assertEquals(exprs.size(), out.size());
        return elapsed;
    }

    @Test
    @DisplayName("Batch compiling 20 expressions is faster than compiling them one by one")
    void batchIsFasterThanOneByOne() throws Exception {
        Assumptions.assumeTrue(ToolProvider.getSystemJavaCompiler() != null,
                "Requires a JDK (system Java compiler) — skipped on a JRE.");

        JsonataExpressionFactory factory = new JsonataExpressionFactory();

        // A tiny warmup primes the JIT and the compiler subsystem so the measured round is not skewed
        // by one-time class loading / compiler initialisation. The footprint is kept deliberately
        // small — each in-process javac invocation is a shared, finite resource and this test runs in
        // the same JVM fork as the whole suite — so the warmup compiles only a handful of throwaway
        // expressions rather than a full round. The comparison is decided structurally (1 compiler
        // invocation for the batch vs EXPRESSION_COUNT for one-by-one), so the ~10x+ gap dwarfs
        // run-to-run noise and a single measured round is conclusive.
        List<String> warmup = expressions(3);
        timeOneByOne(factory, warmup);
        timeBatch(factory, warmup);

        List<String> measured = expressions(EXPRESSION_COUNT);
        long oneByOneNanos = timeOneByOne(factory, measured);
        long batchNanos    = timeBatch(factory, measured);

        double oneMs   = oneByOneNanos / 1_000_000.0;
        double batchMs = batchNanos    / 1_000_000.0;
        System.out.printf(
                "[batch-compile perf] %d expressions: one-by-one=%.1f ms, batch=%.1f ms, speedup=%.2fx%n",
                EXPRESSION_COUNT, oneMs, batchMs, oneMs / batchMs);

        assertTrue(batchNanos < oneByOneNanos,
                () -> String.format(
                        "Expected batch compilation to be faster: batch=%.1f ms, one-by-one=%.1f ms",
                        batchMs, oneMs));
    }
}
