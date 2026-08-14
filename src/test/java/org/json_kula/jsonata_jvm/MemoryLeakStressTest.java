package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Long-running stress test for detecting memory leaks in LambdaRegistry and RegexRegistry.
 *
 * <p>Each of the {@value #THREADS} threads compiles {@value #CYCLES_PER_THREAD} unique
 * JSONata expressions (each containing a distinct regex literal and lambda), then
 * evaluates every expression {@value #EVALS_PER_EXPRESSION} times against randomly
 * generated JSON input, verifying the result after each evaluation.
 *
 * <h2>Running it</h2>
 *
 * <p>Not intended for normal CI runs. {@code @Disabled} keeps it out of a normal build, and JUnit
 * honours that even when the class is named with {@code -Dtest=} — selecting it on its own reports
 * success without running anything. Switch the condition off to actually run it, and pass that
 * inside {@code argLine} so it reaches the forked JVM: a bare {@code -D} stays with the Maven JVM
 * and has no effect on the tests.
 *
 * <pre>
 *   mvn test -Dtest=MemoryLeakStressTest
 *       -DargLine="-Xmx512m -Xms512m -Xlog:gc:file=target/leak-gc.log:time,uptime
 *                  -Djunit.jupiter.conditions.deactivate=org.junit.jupiter.engine.extension.DisabledCondition"
 * </pre>
 *
 * <h2>Reading the result</h2>
 *
 * <p>The test asserts correctness, not memory: a leak shows up as an {@code OutOfMemoryError} or not
 * at all, so watch the JVM while it runs rather than waiting for a verdict at the end.
 *
 * <ul>
 *   <li><b>Metaspace</b> is the one that matters most here. Every cycle compiles a <em>distinct</em>
 *       expression into its own class and {@code InMemoryClassLoader}, so a retained loader shows as
 *       Metaspace climbing without bound. It should sit flat
 *       ({@code jcmd <pid> GC.heap_info}).</li>
 *   <li><b>The live set</b>, via {@code jcmd <pid> GC.class_histogram} — which forces a full GC and
 *       reports only reachable objects. Sample it twice, minutes apart, and compare: what this test
 *       would catch is {@code LambdaNode}, {@code RegexNode} or generated classes accumulating.
 *       A handful of {@code InMemoryFileManager} and {@code EvaluationContext$EvalState} instances
 *       (one per thread) is expected.</li>
 *   <li><b>Post-GC heap</b> from the GC log: the floor should be flat. Young-collection numbers
 *       alone prove nothing, since they do not collect the old generation.</li>
 * </ul>
 *
 * <p>Most of the retained heap belongs to {@code javac} — its zip file-system index and
 * {@code SharedNameTable} — rather than to this library, and it grows with the number of compiler
 * invocations. That is a cost of compiling in-process, and the JVM reclaims it under heap pressure;
 * do not read it as a leak here.
 */
@Tag("stress")
@Disabled("Please start it manually since it takes several minutes")
class MemoryLeakStressTest {

    private static final int THREADS = 10;
    private static final int CYCLES_PER_THREAD = 10000;
    private static final int EVALS_PER_EXPRESSION = 100;
    private static final int ITEMS_COUNT = 10;

    @Test
    void uniqueExpressionStress() throws InterruptedException {
        JsonataExpressionFactory factory = new JsonataExpressionFactory();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Void>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                runThread(factory, threadId);
                return null;
            }));
        }

        pool.shutdown();
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

        for (int t = 0; t < futures.size(); t++) {
            try {
                futures.get(t).get();
            } catch (ExecutionException e) {
                fail("Thread " + t + " failed: " + e.getCause().getMessage(), e.getCause());
            }
        }
    }

    private void runThread(JsonataExpressionFactory factory, int threadId) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Random random = new Random(threadId * 31L);

        for (int cycle = 0; cycle < CYCLES_PER_THREAD; cycle++) {
            // Globally unique id across all threads — used as both the tag string and
            // the regex pattern, ensuring every compiled expression is distinct.
            long id = (long) threadId * CYCLES_PER_THREAD + cycle;
            String tag = "t" + id;
            int mult = (int)(id % 9) + 1;  // 1..9

            // Expression uses a unique regex literal (/$tag/) and two lambdas.
            String expr = "$map("
                    + "$filter(items, function($v){ $contains($v.tag, /" + tag + "/) }),"
                    + " function($v){ $v.num * " + mult + " })";

            JsonataExpression expression = factory.compile(expr);

            for (int eval = 0; eval < EVALS_PER_EXPRESSION; eval++) {
                List<Integer> matchingNums = new ArrayList<>();
                ArrayNode items = mapper.createArrayNode();

                for (int i = 0; i < ITEMS_COUNT; i++) {
                    boolean matches = random.nextBoolean();
                    int num = random.nextInt(100) + 1;
                    ObjectNode item = mapper.createObjectNode();
                    item.put("tag", matches ? tag : "x" + i);
                    item.put("num", num);
                    items.add(item);
                    if (matches) matchingNums.add(num);
                }

                ObjectNode input = mapper.createObjectNode();
                input.set("items", items);

                JsonNode result = expression.evaluate(input);

                assertResult(result, matchingNums, mult, expr);
            }
        }
    }

    private static void assertResult(JsonNode result, List<Integer> matchingNums,
                                     int mult, String expr) {
        if (matchingNums.isEmpty()) {
            assertTrue(result.isMissingNode(),
                    () -> "Expected undefined for zero matches, got: " + result + " in: " + expr);
            return;
        }
        if (matchingNums.size() == 1) {
            assertFalse(result.isMissingNode(),
                    () -> "Expected a number, got missing for: " + expr);
            assertEquals(matchingNums.get(0) * mult, result.asInt(),
                    () -> "Single-match value mismatch in: " + expr);
            return;
        }
        assertTrue(result.isArray(),
                () -> "Expected array for " + matchingNums.size() + " matches, got: " + result
                        + " in: " + expr);
        assertEquals(matchingNums.size(), result.size(),
                () -> "Array size mismatch in: " + expr);
        for (int i = 0; i < matchingNums.size(); i++) {
            final int idx = i;
            assertEquals(matchingNums.get(i) * mult, result.get(i).asInt(),
                    () -> "Element mismatch at index " + idx + " in: " + expr);
        }
    }
}
