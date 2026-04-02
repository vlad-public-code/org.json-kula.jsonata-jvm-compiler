package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.parseJson;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a compiled {@link JsonataExpression} can be called concurrently
 * from multiple threads without data races, corrupted results, or exceptions.
 *
 * <p>Strategy: a single {@link JsonataExpression} instance is shared across many
 * threads. All threads start at the same moment (via a {@link CountDownLatch})
 * to maximise contention. Each thread calls {@code evaluate()} with its own
 * input JSON and verifies the returned value independently.
 */
class JsonataExpressionThreadSafetyTest {

    private static final int THREADS    = 32;
    private static final int ITERATIONS = 50;

    private static JsonataExpressionFactory FACTORY;

    @BeforeAll
    static void setup() {
        FACTORY = new JsonataExpressionFactory();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Runs {@code task} from {@code THREADS} threads simultaneously and returns
     * the list of per-thread results. Propagates the first failure, if any.
     */
    private static <T> List<T> runConcurrently(Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);  // all threads report ready
        CountDownLatch go    = new CountDownLatch(1);         // main thread fires the gun

        List<Future<T>> futures = new ArrayList<>(THREADS);
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();          // wait for all threads to be ready
                return task.call();
            }));
        }

        ready.await();   // block until every thread is parked at 'go'
        go.countDown();  // release all threads at once

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS),
                "Thread pool did not terminate in time");

        List<T> results = new ArrayList<>(THREADS);
        for (Future<T> f : futures) {
            results.add(f.get()); // re-throws ExecutionException on thread failure
        }
        return results;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    /**
     * Arithmetic on a field: each thread uses a different multiplier as input
     * and verifies the result matches {@code value * 3}.
     */
    @Test
    void sharedExpression_arithmetic_concurrentEval() throws Exception {
        JsonataExpression expr = FACTORY.compile("value * 3");
        AtomicInteger counter = new AtomicInteger(1);

        List<double[]> results = runConcurrently(() -> {
            int n = counter.getAndIncrement();
            JsonNode result = expr.evaluate(parseJson("{\"value\":" + n + "}"));
            return new double[]{n, result.doubleValue()};
        });

        for (double[] pair : results) {
            assertEquals(pair[0] * 3, pair[1], 1e-9,
                    "Wrong result for value=" + (int) pair[0]);
        }
    }

    /**
     * String concatenation: each thread supplies its own first/last name and
     * checks the output is correct.
     */
    @Test
    void sharedExpression_stringConcat_concurrentEval() throws Exception {
        JsonataExpression expr = FACTORY.compile("first & \" \" & last");
        AtomicInteger counter = new AtomicInteger(1);

        List<String> results = runConcurrently(() -> {
            int n = counter.getAndIncrement();
            String json = "{\"first\":\"Alice" + n + "\",\"last\":\"Smith" + n + "\"}";
            return expr.evaluate(parseJson(json)).asText();
        });

        AtomicInteger verifyIdx = new AtomicInteger(1);
        for (String s : results) {
            // Each result must be "Alice<N> Smith<N>" for some N — just check shape
            assertTrue(s.matches("Alice\\d+ Smith\\d+"),
                    "Unexpected result: " + s);
        }
    }

    /**
     * Conditional expression: threads with even indices expect "even", odd → "odd".
     */
    @Test
    void sharedExpression_conditional_concurrentEval() throws Exception {
        JsonataExpression expr = FACTORY.compile("n % 2 = 0 ? \"even\" : \"odd\"");
        AtomicInteger counter = new AtomicInteger(0);

        List<String[]> results = runConcurrently(() -> {
            int n = counter.getAndIncrement();
            String result = expr.evaluate(parseJson("{\"n\":" + n + "}")).asText();
            return new String[]{String.valueOf(n), result};
        });

        for (String[] pair : results) {
            int n = Integer.parseInt(pair[0]);
            String expected = (n % 2 == 0) ? "even" : "odd";
            assertEquals(expected, pair[1],
                    "Wrong conditional result for n=" + n);
        }
    }

    /**
     * Built-in function: {@code $sum} over a per-thread array.
     * Each thread sums [1..n] and checks the result equals n*(n+1)/2.
     */
    @Test
    void sharedExpression_builtinFunction_concurrentEval() throws Exception {
        JsonataExpression expr = FACTORY.compile("$sum(nums)");
        AtomicInteger counter = new AtomicInteger(1);

        List<double[]> results = runConcurrently(() -> {
            int n = counter.getAndIncrement() % 20 + 1; // 1..20
            // Build JSON array [1,2,...,n]
            StringBuilder sb = new StringBuilder("{\"nums\":[");
            for (int i = 1; i <= n; i++) {
                if (i > 1) sb.append(',');
                sb.append(i);
            }
            sb.append("]}");
            double sum = expr.evaluate(parseJson(sb.toString())).doubleValue();
            return new double[]{n, sum};
        });

        for (double[] pair : results) {
            int n = (int) pair[0];
            double expected = (double) n * (n + 1) / 2;
            assertEquals(expected, pair[1], 1e-9,
                    "Wrong $sum for n=" + n);
        }
    }

    /**
     * Higher-order function ({@code $map}) with a lambda: the lambda captures
     * nothing from the outside so it must be safe across threads.
     */
    @Test
    void sharedExpression_higherOrderFunction_concurrentEval() throws Exception {
        JsonataExpression expr = FACTORY.compile("$map(nums, function($v){ $v * $v })");
        AtomicInteger counter = new AtomicInteger(1);

        List<Boolean> results = runConcurrently(() -> {
            int base = counter.getAndIncrement() % 5 + 1; // 1..5
            String json = "{\"nums\":[" + base + "," + (base + 1) + "," + (base + 2) + "]}";
            JsonNode arr = expr.evaluate(parseJson(json));
            boolean ok = arr.isArray()
                    && arr.size() == 3
                    && Math.abs(arr.get(0).doubleValue() - (double) base * base)         < 1e-9
                    && Math.abs(arr.get(1).doubleValue() - (double) (base+1) * (base+1)) < 1e-9
                    && Math.abs(arr.get(2).doubleValue() - (double) (base+2) * (base+2)) < 1e-9;
            return ok;
        });

        assertTrue(results.stream().allMatch(b -> b), "$map produced wrong results under concurrency");
    }

    /**
     * Verifies that {@link JsonataExpression#getSourceJsonata()} returns the
     * correct immutable value from every thread.
     */
    @Test
    void sharedExpression_getSourceJsonata_concurrentRead() throws Exception {
        String expression = "price * qty - discount";
        JsonataExpression expr = FACTORY.compile(expression);

        List<String> results = runConcurrently(expr::getSourceJsonata);

        assertTrue(results.stream().allMatch(expression::equals),
                "getSourceJsonata() returned inconsistent values under concurrency");
    }

    /**
     * Mixed workload: half the threads call {@code evaluate()}, the other half
     * call {@code getSourceJsonata()}. No thread must see an exception.
     */
    @Test
    void sharedExpression_mixedReadAndEval_concurrentAccess() throws Exception {
        String expression = "x + 1";
        JsonataExpression expr = FACTORY.compile(expression);
        AtomicInteger counter = new AtomicInteger(0);

        List<Boolean> results = runConcurrently(() -> {
            int idx = counter.getAndIncrement();
            if (idx % 2 == 0) {
                // evaluate branch
                JsonNode r = expr.evaluate(parseJson("{\"x\":" + idx + "}"));
                return Math.abs(r.doubleValue() - (idx + 1)) < 1e-9;
            } else {
                // getSourceJsonata branch
                return expression.equals(expr.getSourceJsonata());
            }
        });

        assertTrue(results.stream().allMatch(b -> b),
                "Mixed concurrent access produced wrong results");
    }

    /**
     * Stress test: {@code ITERATIONS} rounds of concurrent evaluation on the
     * same expression to surface intermittent races.
     */
    @Test
    void sharedExpression_repeatedConcurrentEval_noExceptions() throws Exception {
        JsonataExpression expr = FACTORY.compile("a * b + c");

        for (int round = 0; round < ITERATIONS; round++) {
            AtomicInteger counter = new AtomicInteger(1);
            List<double[]> results = runConcurrently(() -> {
                int n = counter.getAndIncrement();
                // a=n, b=2, c=n  →  n*2+n = 3n
                String json = "{\"a\":" + n + ",\"b\":2,\"c\":" + n + "}";
                double result = expr.evaluate(parseJson(json)).doubleValue();
                return new double[]{n, result};
            });

            for (double[] pair : results) {
                assertEquals(pair[0] * 3, pair[1], 1e-9);
            }
        }
    }
}
