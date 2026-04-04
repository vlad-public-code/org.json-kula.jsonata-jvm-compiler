package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry for lambda functions used by the {@code ~>} chain operator.
 *
 * <p>Lambdas are represented as plain {@link com.fasterxml.jackson.databind.node.TextNode}s
 * with the sentinel prefix {@code "__λ:"} so they flow through the Jackson type
 * system without requiring a custom {@code JsonNode} subclass.
 */
final class LambdaRegistry {

    private LambdaRegistry() {}

    private static final ConcurrentHashMap<String, JsonataLambda> LAMBDA_REGISTRY =
            new ConcurrentHashMap<>();
    static final String LAMBDA_PREFIX = "__\u03bb:";
    private static final AtomicLong LAMBDA_COUNTER = new AtomicLong();

    /**
     * Registers {@code fn} in the lambda registry and returns a sentinel
     * {@link com.fasterxml.jackson.databind.node.TextNode} that can be stored as a
     * {@link JsonNode} value and later resolved by {@link #lookupLambda}.
     */
    static JsonNode lambdaNode(JsonataLambda fn) {
        String key = String.valueOf(LAMBDA_COUNTER.incrementAndGet());
        LAMBDA_REGISTRY.put(key, fn);
        return JsonNodeFactory.instance.textNode(LAMBDA_PREFIX + key);
    }

    /** Returns {@code true} if {@code n} is a lambda sentinel token. */
    static boolean isLambdaToken(JsonNode n) {
        return n != null && n.isTextual() && n.textValue().startsWith(LAMBDA_PREFIX);
    }

    /** Resolves the lambda sentinel token to the registered {@link JsonataLambda}. */
    static JsonataLambda lookupLambda(JsonNode n) throws RuntimeEvaluationException {
        String key = n.textValue().substring(LAMBDA_PREFIX.length());
        JsonataLambda fn = LAMBDA_REGISTRY.get(key);
        if (fn == null) throw new RuntimeEvaluationException("Lambda expired or not found: " + key);
        return fn;
    }

    /**
     * Implements the {@code ~>} (chain/pipe) operator.
     *
     * <ul>
     *   <li>If {@code arg} is also a lambda token the two are <em>composed</em>:
     *       returns a new lambda that applies {@code arg} first, then {@code fn}.
     *       This supports {@code $f ~> $g} yielding a composed function.</li>
     *   <li>Otherwise {@code fn} is invoked with {@code arg} as its argument
     *       (standard value-piping: {@code value ~> $fn}).</li>
     * </ul>
     */
    static JsonNode fn_pipe(JsonNode arg, JsonNode fn) throws RuntimeEvaluationException {
        if (!isLambdaToken(fn)) {
            throw new RuntimeEvaluationException(
                    "Right-hand side of ~> is not a function; got: " + fn);
        }
        if (isLambdaToken(arg)) {
            final JsonataLambda f = lookupLambda(arg);
            final JsonataLambda g = lookupLambda(fn);
            return lambdaNode(x -> g.apply(f.apply(x)));
        }
        return lookupLambda(fn).apply(arg);
    }

    /**
     * Applies {@code fn} to {@code arg} — used when calling a user-defined
     * lambda stored in a local variable.
     * {@code fn} must be a lambda token produced by {@link #lambdaNode}.
     */
    static JsonNode fn_apply(JsonNode fn, JsonNode arg) throws RuntimeEvaluationException {
        if (isLambdaToken(fn)) {
            return lookupLambda(fn).apply(arg);
        }
        throw new RuntimeEvaluationException(
                "T1006: The expression is not a function; got: " + fn);
    }
}
