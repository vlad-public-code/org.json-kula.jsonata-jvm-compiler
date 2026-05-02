package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.parseJson;
import static org.junit.jupiter.api.Assertions.*;

public class TimeoutTest {

    private static JsonataExpressionFactory FACTORY;

    @BeforeAll
    static void setup() {
        FACTORY = new JsonataExpressionFactory();
    }

    @Test
    void infiniteRecursionIsInterrupted() throws Exception {
        JsonataExpression expr = FACTORY.compile(
                "($loop := function($n) { $loop($n + 1) }; $loop(0))");
        expr.setTimeout(200);
        JsonataEvaluationException ex = assertThrows(JsonataEvaluationException.class,
                () -> expr.evaluate(MissingNode.getInstance()));
        assertEquals("U1001", ex.getErrorCode());
    }

    @Test
    void largeRangeIsInterrupted() throws Exception {
        JsonataExpression expr = FACTORY.compile("$count([1..9999999])");
        expr.setTimeout(10);
        JsonataEvaluationException ex = assertThrows(JsonataEvaluationException.class,
                () -> expr.evaluate(MissingNode.getInstance()));
        assertEquals("U1001", ex.getErrorCode());
    }

    @Test
    void fastExpressionCompletesNormally() throws Exception {
        JsonataExpression expr = FACTORY.compile("1 + 2 + 3");
        expr.setTimeout(5000);
        JsonNode result = expr.evaluate(MissingNode.getInstance());
        assertEquals(6, result.intValue());
    }

    @Test
    void noTimeoutByDefault() throws Exception {
        JsonataExpression expr = FACTORY.compile("$sum([1..1000])");
        JsonNode result = expr.evaluate(MissingNode.getInstance());
        assertEquals(500500, result.intValue());
    }

    @Test
    void disablingTimeoutAllowsCompletion() throws Exception {
        JsonataExpression expr = FACTORY.compile("$sum([1..1000])");
        expr.setTimeout(200);
        expr.setTimeout(0);
        JsonNode result = expr.evaluate(MissingNode.getInstance());
        assertEquals(500500, result.intValue());
    }

    @Test
    void timeoutIsPerEvaluationNotShared() throws Exception {
        JsonataExpression expr = FACTORY.compile("$sum([1..1000])");
        expr.setTimeout(5000);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<JsonNode>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> expr.evaluate(MissingNode.getInstance())));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        for (Future<JsonNode> f : futures) {
            assertEquals(500500, f.get().intValue());
        }
    }
}
