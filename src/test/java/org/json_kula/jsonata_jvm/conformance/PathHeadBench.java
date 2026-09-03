package org.json_kula.jsonata_jvm.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;

/**
 * Micro-benchmark for paths headed by an array constructor — the only shape the
 * {@code consarrayHead} short-circuit changes.
 *
 * <p>Every other path shape generates byte-identical code before and after, so this is the
 * only place a regression could hide. The end-to-end benchmark cannot answer the question:
 * its expression contains no array-constructor-headed path at all.
 *
 * <p>Reports the minimum of several timed rounds; a single round on this machine varies by
 * more than the effect being measured. Not a test — run by hand.
 */
public final class PathHeadBench {

    private PathHeadBench() {}

    private static final String[] EXPRS = {
        // the affected shape: a non-empty constructor, so the guard falls through
        "[1,2,3].x",
        "[{\"x\":1},{\"x\":2},{\"x\":3}].x",
        // affected, and the rest of the path captures a variable
        "($k := 2; [{\"x\":1},{\"x\":2}].(x * $k))",
        // affected, and empty: the short-circuit skips the remaining steps
        "[].x",
        // control: unaffected shapes, generated code is unchanged
        "a.b",
        "([{\"x\":1},{\"x\":2},{\"x\":3}]).x",
    };

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.readTree("{\"a\": {\"b\": 1}}");
        JsonataExpressionFactory factory = new JsonataExpressionFactory();

        for (String source : EXPRS) {
            JsonataExpression expr = factory.compile(source);
            for (int i = 0; i < 50_000; i++) expr.evaluate(input);
            int n = 500_000;
            double best = Double.MAX_VALUE;
            for (int round = 0; round < 7; round++) {
                long start = System.nanoTime();
                for (int i = 0; i < n; i++) expr.evaluate(input);
                best = Math.min(best, (System.nanoTime() - start) / 1000.0 / n);
            }
            System.out.printf("%-46s %8.4f us/call%n", source, best);
        }
    }
}
