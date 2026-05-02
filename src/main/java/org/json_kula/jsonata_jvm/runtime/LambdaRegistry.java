package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** Bounded static LRU cache: fallback when no evaluation context is active (max 100 entries). */
    private static final Map<String, JsonataLambda> LAMBDA_REGISTRY =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JsonataLambda> eldest) {
                    return size() > 100;
                }
            });
    static final String LAMBDA_PREFIX = "__\u03bb:";
    private static final AtomicLong LAMBDA_COUNTER = new AtomicLong();

    /** Maximum nesting depth for user-defined function calls (JSONata U1001 limit).
     *  factorial(99) needs 100 fn_apply calls (0..99) — all succeed.
     *  factorial(100) needs 101 fn_apply calls — the 101st triggers U1001. */
    private static final int MAX_CALL_DEPTH = 100;

    /**
     * Fallback call-depth counter used when fn_apply is called outside an active evaluation
     * (e.g., in tests). Under normal evaluation the counter lives in EvalState.
     */
    private static final ThreadLocal<int[]> CALL_DEPTH = ThreadLocal.withInitial(() -> new int[]{0});

    /**
     * Maximum number of trampoline iterations for TCO'd tail-recursive loops.
     */
    private static final int MAX_TRAMPOLINE_ITERATIONS = 100_000;

    /** Sentinel node returned by {@link #fn_apply_tco} to signal a pending tail call. */
    static final com.fasterxml.jackson.databind.node.TextNode TCO_SENTINEL =
            JsonNodeFactory.instance.textNode("__λ_tco:");

    /** Carries the next tail-call target (lambda token + arg) when TCO_SENTINEL is returned. */
    record TailCallData(JsonataLambda fn, JsonNode arg) {}

    /**
     * Fallback pending-tail-call slot used when fn_apply_tco is called outside an active
     * evaluation. Under normal evaluation the slot lives in EvalState.
     */
    private static final ThreadLocal<TailCallData> PENDING_TAIL_CALL = ThreadLocal.withInitial(() -> null);

    /**
     * Registers {@code fn} in the lambda registry and returns a sentinel
     * {@link com.fasterxml.jackson.databind.node.TextNode} that can be stored as a
     * {@link JsonNode} value and later resolved by {@link #lookupLambda}.
     *
     * <p>Lambdas are stored in the per-evaluation ThreadLocal map when inside an active
     * evaluation, so the map is automatically discarded after each {@code evaluate()} call.
     * The static fallback is used only outside an active evaluation (e.g. in tests).
     */
    static JsonNode lambdaNode(JsonataLambda fn) {
        String key = String.valueOf(LAMBDA_COUNTER.incrementAndGet());
        EvaluationContext.EvalState evalState = EvaluationContext.getState();
        if (evalState != null) {
            evalState.evalLambdas().put(key, fn);
        } else {
            LAMBDA_REGISTRY.put(key, fn);
        }
        return JsonNodeFactory.instance.textNode(LAMBDA_PREFIX + key);
    }

    /** Returns {@code true} if {@code n} is a lambda sentinel token. */
    static boolean isLambdaToken(JsonNode n) {
        return n != null && n.isTextual() && n.textValue().startsWith(LAMBDA_PREFIX);
    }

    /** Resolves the lambda sentinel token to the registered {@link JsonataLambda}. */
    static JsonataLambda lookupLambda(JsonNode n) throws RuntimeEvaluationException {
        String key = n.textValue().substring(LAMBDA_PREFIX.length());
        EvaluationContext.EvalState evalState = EvaluationContext.getState();
        if (evalState != null) {
            JsonataLambda fn = evalState.evalLambdas().get(key);
            if (fn != null) return fn;
        }
        JsonataLambda fn = LAMBDA_REGISTRY.get(key);
        if (fn == null) throw new RuntimeEvaluationException(null, "Lambda expired or not found: " + key);
        return fn;
    }

    /**
     * Implements the {@code ~>} (chain/pipe) operator.
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
     *
     * <p>Uses a single {@link EvaluationContext#getState()} call to access call depth,
     * pending TCO slot, and lambda map, avoiding redundant ThreadLocal lookups.
     * Implements a <em>trampoline</em> for tail-call optimisation (TCO).
     */
    static JsonNode fn_apply(JsonNode fn, JsonNode arg) throws RuntimeEvaluationException {
        if (isLambdaToken(fn)) {
            EvaluationContext.EvalState evalState = EvaluationContext.getState();
            int[] depth = evalState != null ? evalState.callDepth() : CALL_DEPTH.get();
            TailCallData[] pendingSlot = evalState != null ? evalState.pendingTailCall() : null;

            if (depth[0] >= MAX_CALL_DEPTH)
                throw new RuntimeEvaluationException(
                        "U1001", "Stack overflow error: Check for circular reference or too many function calls");
            if (evalState != null && evalState.timeoutDeadline != Long.MAX_VALUE
                    && System.currentTimeMillis() > evalState.timeoutDeadline)
                throw new RuntimeEvaluationException("U1001", "Expression evaluation timeout");
            depth[0]++;
            try {
                // Inline lookup reuses evalState already obtained above
                String key = fn.textValue().substring(LAMBDA_PREFIX.length());
                JsonataLambda lambda = evalState != null ? evalState.evalLambdas().get(key) : null;
                if (lambda == null) lambda = LAMBDA_REGISTRY.get(key);
                if (lambda == null) throw new RuntimeEvaluationException(null, "Lambda expired or not found: " + key);

                JsonNode result = lambda.apply(arg);
                int trampolineCount = 0;
                while (result == TCO_SENTINEL) {
                    if (++trampolineCount > MAX_TRAMPOLINE_ITERATIONS)
                        throw new RuntimeEvaluationException(
                                "U1001", "Stack overflow error: Check for circular reference or too many function calls");
                    TailCallData tcd;
                    if (pendingSlot != null) {
                        tcd = pendingSlot[0];
                        pendingSlot[0] = null;
                    } else {
                        tcd = PENDING_TAIL_CALL.get();
                        PENDING_TAIL_CALL.set(null);
                    }
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
                if (pendingSlot != null) pendingSlot[0] = null;
                else PENDING_TAIL_CALL.set(null);
            }
        }
        throw new RuntimeEvaluationException(
                "T1006", "The expression is not a function; got: " + fn);
    }

    /**
     * Tail-call variant of {@link #fn_apply}: stores the next call as a pending tail
     * call in EvalState (or the fallback ThreadLocal) and returns {@link #TCO_SENTINEL}.
     * The trampoline loop in {@link #fn_apply} picks this up without growing the JVM stack.
     *
     * <p>Must only be called from <em>tail position</em> in a lambda body.
     */
    static JsonNode fn_apply_tco(JsonNode fn, JsonNode arg) throws RuntimeEvaluationException {
        if (!isLambdaToken(fn))
            throw new RuntimeEvaluationException(
                    "T1006", "The expression is not a function; got: " + fn);

        EvaluationContext.EvalState evalState = EvaluationContext.getState();
        // Inline lookup reuses evalState
        String key = fn.textValue().substring(LAMBDA_PREFIX.length());
        JsonataLambda lambda = evalState != null ? evalState.evalLambdas().get(key) : null;
        if (lambda == null) lambda = LAMBDA_REGISTRY.get(key);
        if (lambda == null) throw new RuntimeEvaluationException(null, "Lambda expired or not found: " + key);

        TailCallData tcd = new TailCallData(lambda, arg);
        if (evalState != null) {
            evalState.pendingTailCall()[0] = tcd;
        } else {
            PENDING_TAIL_CALL.set(tcd);
        }
        return TCO_SENTINEL;
    }
}
