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

    /** Maximum nesting depth for user-defined function calls (JSONata U1001 limit).
     *  factorial(99) needs 100 fn_apply calls (0..99) — all succeed.
     *  factorial(100) needs 101 fn_apply calls — the 101st triggers U1001. */
    private static final int MAX_CALL_DEPTH = 100;
    /** Per-thread call depth counter for user-defined lambda invocations. */
    private static final ThreadLocal<int[]> CALL_DEPTH = ThreadLocal.withInitial(() -> new int[]{0});

    /**
     * Maximum number of trampoline iterations for TCO'd tail-recursive loops.
     * Tail-recursive lambdas (e.g. {@code $odd(6555)}) consume one trampoline
     * iteration per recursive call without growing the JVM stack, so this limit
     * must exceed the maximum expected recursion depth for successful cases.
     * Truly infinite recursive lambdas (e.g. {@code $inf := function(){$inf()}}) will
     * eventually hit this limit and throw U1001.
     */
    private static final int MAX_TRAMPOLINE_ITERATIONS = 100_000;

    /** Sentinel node returned by {@link #fn_apply_tco} to signal a pending tail call. */
    static final com.fasterxml.jackson.databind.node.TextNode TCO_SENTINEL =
            JsonNodeFactory.instance.textNode("__λ_tco:");

    /** Carries the next tail-call target (lambda token + arg) when TCO_SENTINEL is returned. */
    private record TailCallData(JsonataLambda fn, JsonNode arg) {}

    /** Per-thread pending tail-call set by {@link #fn_apply_tco}. */
    private static final ThreadLocal<TailCallData> PENDING_TAIL_CALL = ThreadLocal.withInitial(() -> null);

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
        if (fn == null) throw new RuntimeEvaluationException(null, "Lambda expired or not found: " + key);
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
        // Regex on the right: test whether arg matches the regex
        if (RegexRegistry.isRegexToken(fn)) {
            if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
            byte[] bytes = JsonataRuntime.toText(arg).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return JsonataRuntime.bool(
                    RegexRegistry.lookupRegex(fn).matcher(bytes)
                            .search(0, bytes.length, org.joni.Option.NONE) >= 0);
        }
        if (!isLambdaToken(fn)) {
            throw new RuntimeEvaluationException(
                    "T2006", "Right-hand side of ~> is not a function; got: " + fn);
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
     *
     * <p>Implements a <em>trampoline</em> for tail-call optimisation (TCO).
     * When a lambda body emits a tail call via {@link #fn_apply_tco}, it returns
     * {@link #TCO_SENTINEL} without consuming a new stack frame.  The trampoline
     * loop here picks up the pending {@link TailCallData}, re-invokes the next
     * lambda directly (without a recursive {@code fn_apply} call), and repeats
     * until a real value is produced.  The {@link #MAX_TRAMPOLINE_ITERATIONS}
     * cap ensures that truly-infinite tail loops are still detected as U1001.
     */
    static JsonNode fn_apply(JsonNode fn, JsonNode arg) throws RuntimeEvaluationException {
        if (isLambdaToken(fn)) {
            int[] depth = CALL_DEPTH.get();
            if (depth[0] >= MAX_CALL_DEPTH)
                throw new RuntimeEvaluationException(
                        "U1001", "Stack overflow error: Check for circular reference or too many function calls");
            depth[0]++;
            try {
                JsonNode result = lookupLambda(fn).apply(arg);
                // Trampoline loop: handle tail calls without growing the JVM stack.
                int trampolineCount = 0;
                while (result == TCO_SENTINEL) {
                    if (++trampolineCount > MAX_TRAMPOLINE_ITERATIONS)
                        throw new RuntimeEvaluationException(
                                "U1001", "Stack overflow error: Check for circular reference or too many function calls");
                    TailCallData tcd = PENDING_TAIL_CALL.get();
                    PENDING_TAIL_CALL.set(null);
                    if (tcd == null)
                        throw new RuntimeEvaluationException(
                                "U1001", "Stack overflow error: Check for circular reference or too many function calls");
                    result = tcd.fn().apply(tcd.arg());
                }
                return result;
            } catch (StackOverflowError e) {
                throw new RuntimeEvaluationException(
                        "U1001", "Stack overflow error: Check for circular reference or too many function calls");
            } finally {
                depth[0]--;
                PENDING_TAIL_CALL.set(null); // clean up in case of exception mid-trampoline
            }
        }
        throw new RuntimeEvaluationException(
                "T1006", "The expression is not a function; got: " + fn);
    }

    /**
     * Tail-call variant of {@link #fn_apply}: instead of recursing, stores the
     * next call in a thread-local and returns {@link #TCO_SENTINEL}.  The
     * trampoline loop in {@link #fn_apply} will pick this up and continue without
     * growing the JVM stack.
     *
     * <p>This method must only be called from <em>tail position</em> in a lambda
     * body — i.e. when its result would be returned directly without further
     * processing.  The translator emits {@code fn_apply_tco} for function calls
     * that the tail-position analysis marks as eligible for TCO.
     */
    static JsonNode fn_apply_tco(JsonNode fn, JsonNode arg) throws RuntimeEvaluationException {
        if (!isLambdaToken(fn))
            throw new RuntimeEvaluationException(
                    "T1006", "The expression is not a function; got: " + fn);
        PENDING_TAIL_CALL.set(new TailCallData(lookupLambda(fn), arg));
        return TCO_SENTINEL;
    }
}
