package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Call and composition support for JSONata function values.
 *
 * <p>A function value <em>is</em> a {@link LambdaNode} — it carries its {@link JsonataLambda}
 * directly, so calling it is a cast rather than a registry lookup, and it stays callable for exactly
 * as long as something references it.
 */
final class LambdaRegistry {

    private LambdaRegistry() {}

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

    /** Carries the next tail-call target (lambda + arg) when TCO_SENTINEL is returned. */
    record TailCallData(JsonataLambda fn, JsonNode arg) {}

    /**
     * Fallback pending-tail-call slot used when fn_apply_tco is called outside an active
     * evaluation. Under normal evaluation the slot lives in EvalState.
     */
    private static final ThreadLocal<TailCallData> PENDING_TAIL_CALL = ThreadLocal.withInitial(() -> null);

    /**
     * Wraps {@code fn} as a JSONata function value of unknown arity.
     */
    static JsonNode lambdaNode(JsonataLambda fn) {
        return new LambdaNode(fn, LambdaNode.UNKNOWN_ARITY);
    }

    /**
     * Wraps {@code fn} as a JSONata function value that declares {@code arity} parameters.
     * Built-in higher-order functions consult the arity when the callback reaches them as a value
     * rather than as a literal lambda.
     */
    static JsonNode lambdaNode(JsonataLambda fn, int arity) {
        return new LambdaNode(fn, arity);
    }

    /** Returns {@code true} if {@code n} is a function value. */
    static boolean isLambdaToken(JsonNode n) {
        return n instanceof LambdaNode;
    }

    /** Returns the callable carried by {@code n}. */
    static JsonataLambda lookupLambda(JsonNode n) throws RuntimeEvaluationException {
        if (!(n instanceof LambdaNode lambda))
            throw new RuntimeEvaluationException("T1006", "The expression is not a function; got: " + n);
        return lambda.function();
    }

    /** Returns the declared parameter count of {@code n}, or {@link LambdaNode#UNKNOWN_ARITY}. */
    static int arityOf(JsonNode n) {
        return n instanceof LambdaNode lambda ? lambda.arity() : LambdaNode.UNKNOWN_ARITY;
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
            return lambdaNode(x -> g.apply(f.apply(x)), arityOf(arg));
        }
        return lookupLambda(fn).apply(arg);
    }

    /**
     * Applies {@code fn} to {@code arg} — used when calling a user-defined
     * lambda stored in a local variable.
     *
     * <p>Implements a <em>trampoline</em> for tail-call optimisation (TCO): a tail call returns
     * {@link #TCO_SENTINEL} and is re-dispatched here instead of growing the JVM stack.
     */
    static JsonNode fn_apply(JsonNode fn, JsonNode arg) throws RuntimeEvaluationException {
        if (fn instanceof LambdaNode lambdaNode) {
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
                JsonNode result = lambdaNode.function().apply(arg);
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
        if (!(fn instanceof LambdaNode lambdaNode))
            throw new RuntimeEvaluationException(
                    "T1006", "The expression is not a function; got: " + fn);

        TailCallData tcd = new TailCallData(lambdaNode.function(), arg);
        EvaluationContext.EvalState evalState = EvaluationContext.getState();
        if (evalState != null) {
            evalState.pendingTailCall()[0] = tcd;
        } else {
            PENDING_TAIL_CALL.set(tcd);
        }
        return TCO_SENTINEL;
    }
}
