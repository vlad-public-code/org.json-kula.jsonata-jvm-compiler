package org.json_kula.jsonata_jvm.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataCompilationException;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Differential-testing probe runner: the Java voice of the three-engine harness.
 *
 * <p>Reads a JSON array of {@code [{id, expr, input}]} and writes
 * {@code {id: {"ok": value} | {"err": "eval:CODE"}}} — deliberately the <em>same</em>
 * I/O contract as {@code jsonata2js/_xrun_ref.js}, which drives the reference
 * interpreter. Two files produced this way diff directly; if the conventions drift,
 * every comparison becomes noise rather than signal.
 *
 * <p>Conventions copied from the oracle, and load-bearing:
 * <ul>
 *   <li>an absent result ({@code undefined} there, {@code MissingNode} here) serialises
 *       as JSON {@code null}, because the oracle cannot distinguish them either;
 *   <li>a function value serialises as the string {@code "<fn>"};
 *   <li>an error becomes {@code "eval:CODE"}, so a divergence in <em>which</em> error is
 *       raised is visible rather than collapsed into a generic failure.
 * </ul>
 *
 * <p>Expressions are batch-compiled: {@code javac} costs far more per invocation than
 * per class, so compiling a 6,000-case sweep one at a time would dominate the run. A
 * batch that fails as a whole is retried expression-by-expression so a single
 * uncompilable probe does not take its batch with it.
 *
 * <p>Not a test. It is invoked directly ({@code java ... ProbeRunner cases.json out.json})
 * and lives under {@code src/test} so it stays out of the shipped jar.
 */
public final class ProbeRunner {

    private ProbeRunner() {}

    /** Expressions per {@code javac} invocation, matching {@code JsonataTestSuiteTest}. */
    private static final int COMPILE_BATCH = 250;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ProbeRunner <cases.json> <out.json>");
            System.exit(2);
        }
        JsonNode cases = MAPPER.readTree(Path.of(args[0]).toFile());

        Map<String, JsonataExpression> compiled = precompile(cases);
        ObjectNode out = MAPPER.createObjectNode();

        // Each probe runs on a worker with a wall-clock bound. A pathological probe is a
        // finding in its own right — the zero-length-match sweep turned up expressions
        // that run away building matches until the heap is gone — so the harness reports
        // it as "timeout" and carries on rather than losing the other 3,000 results.
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "probe");
            t.setDaemon(true);
            return t;
        });

        for (JsonNode probe : cases) {
            String id = probe.path("id").asText();
            String expr = probe.path("expr").asText();
            JsonNode input = probe.has("input") ? probe.get("input") : null;
            if (System.getenv("PROBE_TRACE") != null) System.err.println(id + "  " + expr);
            out.set(id, runGuarded(worker, expr, input, compiled));
        }

        Files.writeString(Path.of(args[1]), MAPPER.writeValueAsString(out));
        worker.shutdownNow();
        System.exit(0);
    }

    /** Wall-clock bound per probe. Generous: only a runaway should ever reach it. */
    private static final long PROBE_TIMEOUT_MS = 5_000;

    /**
     * Runs one probe under {@link #PROBE_TIMEOUT_MS}. A probe that overruns leaves its
     * thread running — it cannot be interrupted safely mid-evaluation — so the executor
     * is rebuilt for the next probe rather than reused.
     */
    private static ObjectNode runGuarded(ExecutorService worker, String expr, JsonNode input,
                                         Map<String, JsonataExpression> compiled) {
        Future<ObjectNode> pending = worker.submit(() -> runOne(expr, input, compiled));
        try {
            return pending.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            ObjectNode result = MAPPER.createObjectNode();
            result.put("err", "eval:timeout");
            return result;
        } catch (Exception e) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("err", "eval:" + e.getClass().getSimpleName());
            return result;
        }
    }

    /**
     * Compiles every distinct expression up front, in batches. Expressions that fail to
     * compile are simply absent from the map; {@link #runOne} then compiles them again
     * on demand so the compilation error is reported against the probe that owns it.
     */
    private static Map<String, JsonataExpression> precompile(JsonNode cases) {
        JsonataExpressionFactory factory = new JsonataExpressionFactory();
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (JsonNode probe : cases) distinct.add(probe.path("expr").asText());

        Map<String, JsonataExpression> compiled = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>(distinct);
        for (int start = 0; start < pending.size(); start += COMPILE_BATCH) {
            List<String> batch = pending.subList(start, Math.min(start + COMPILE_BATCH, pending.size()));
            try {
                List<JsonataExpression> result = factory.compileAll(batch);
                for (int i = 0; i < batch.size(); i++) compiled.put(batch.get(i), result.get(i));
            } catch (JsonataCompilationException | RuntimeException batchFailure) {
                for (String expression : batch) {
                    try {
                        compiled.put(expression, factory.compile(expression));
                    } catch (JsonataCompilationException | RuntimeException individual) {
                        // Left out on purpose: runOne recompiles and reports the error.
                        // RuntimeException is caught too — a translator that crashes on an
                        // expression is a finding, and losing the whole batch to it would
                        // hide every other result.
                    }
                }
            }
        }
        return compiled;
    }

    private static ObjectNode runOne(String expr, JsonNode input,
                                     Map<String, JsonataExpression> compiled) {
        ObjectNode result = MAPPER.createObjectNode();
        try {
            JsonataExpression expression = compiled.get(expr);
            if (expression == null) expression = new JsonataExpressionFactory().compile(expr);
            // (a compile that throws a RuntimeException is reported by the catch below)
            result.set("ok", normalise(expression.evaluate(input)));
        } catch (JsonataCompilationException e) {
            result.put("err", "compile:" + codeOf(e.getErrorCode(), e));
        } catch (JsonataEvaluationException e) {
            result.put("err", "eval:" + codeOf(e.getErrorCode(), e));
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            // An unexpected failure is a finding in its own right, so it is reported
            // rather than swallowed — a raw exception where the reference returns a
            // value is exactly the kind of divergence this harness exists to catch.
            // OutOfMemoryError is caught for the same reason: the zero-length-match
            // sweep produces probes that build matches until the heap is exhausted.
            result.put("err", "eval:" + e.getClass().getSimpleName());
        }
        return result;
    }

    private static String codeOf(String code, Throwable e) {
        return code != null ? code : e.getClass().getSimpleName();
    }

    /**
     * Maps a result into the oracle's serialisation conventions: absent becomes JSON
     * null, and a function value becomes {@code "<fn>"}.
     */
    private static JsonNode normalise(JsonNode node) {
        if (node == null || node.isMissingNode()) return MAPPER.nullNode();
        if (isFunctionValue(node)) return MAPPER.getNodeFactory().textNode("<fn>");
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            for (JsonNode element : node) copy.add(normalise(element));
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            node.properties().forEach(e -> copy.set(e.getKey(), normalise(e.getValue())));
            return copy;
        }
        return node;
    }

    /**
     * A function or regex value is carried as a dedicated {@code JsonNode} subclass, so
     * identifying one is a type test rather than a string-prefix sniff of the content.
     */
    private static boolean isFunctionValue(JsonNode node) {
        String type = node.getClass().getSimpleName();
        return type.equals("LambdaNode") || type.equals("RegexNode");
    }
}
