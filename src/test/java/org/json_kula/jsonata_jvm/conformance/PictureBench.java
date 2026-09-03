
package org.json_kula.jsonata_jvm.conformance;

import com.fasterxml.jackson.databind.node.NullNode;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;

/**
 * Micro-benchmark for the picture-string built-ins. Not a test; run by hand.
 *
 * <p>Reports the minimum of several timed rounds rather than a single reading: a single
 * round on this machine varies by 40% or more, which is wider than the effects being
 * measured.
 */
public final class PictureBench {
    private PictureBench() {}

    private static final String[] EXPRS = {
        "$formatNumber(1234.5678, '#,###.00')",
        "$formatInteger(1234, '#,##0')",
        "$fromMillis(1521801216617, '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01]')",
        "$toMillis('2018-03-23', '[Y0001]-[M01]-[D01]')",
    };

    public static void main(String[] args) throws Exception {
        JsonataExpressionFactory factory = new JsonataExpressionFactory();
        for (String source : EXPRS) {
            JsonataExpression expr = factory.compile(source);
            for (int i = 0; i < 50_000; i++) expr.evaluate(NullNode.instance);
            int n = 200_000;
            double best = Double.MAX_VALUE;
            for (int round = 0; round < 7; round++) {
                long start = System.nanoTime();
                for (int i = 0; i < n; i++) expr.evaluate(NullNode.instance);
                best = Math.min(best, (System.nanoTime() - start) / 1000.0 / n);
            }
            System.out.printf("%-70s %8.3f us/call%n", source, best);
        }
    }
}
