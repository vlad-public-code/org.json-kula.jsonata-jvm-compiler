package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataBoundFunction;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataFunctionArguments;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Thread-local bindings context for active JSONata evaluations.
 *
 * <p>Each evaluation thread installs its merged bindings via {@link #beginEvaluation}
 * and clears them via {@link #endEvaluation}.
 */
final class EvaluationContext {

    private EvaluationContext() {}

    /**
     * All per-evaluation thread-local state in one mutable container.
     * Reused across evaluate() calls on the same thread to avoid per-call allocation.
     * Fields that are only needed when lambdas are used (evalLambdas, callDepth,
     * pendingTailCall) are lazily initialised on first access.
     */
    static final class EvalState {
        boolean active;
        JsonataBindings bindings;
        long millis;
        Map<String, JsonataLambda> evalLambdas;
        Map<String, org.joni.Regex> instanceRegexes;
        int[] callDepth;
        LambdaRegistry.TailCallData[] pendingTailCall;

        void begin(JsonataBindings bindings, long millis, Map<String, org.joni.Regex> instanceRegexes) {
            this.active = true;
            this.bindings = bindings;
            this.millis = millis;
            this.instanceRegexes = instanceRegexes;
        }

        void end() {
            this.active = false;
            this.bindings = null;
            this.instanceRegexes = null;
            if (evalLambdas != null) evalLambdas.clear();
            if (callDepth != null) callDepth[0] = 0;
            if (pendingTailCall != null) pendingTailCall[0] = null;
        }

        Map<String, JsonataLambda> evalLambdas() {
            if (evalLambdas == null) evalLambdas = new java.util.HashMap<>();
            return evalLambdas;
        }

        int[] callDepth() {
            if (callDepth == null) callDepth = new int[]{0};
            return callDepth;
        }

        LambdaRegistry.TailCallData[] pendingTailCall() {
            if (pendingTailCall == null) pendingTailCall = new LambdaRegistry.TailCallData[1];
            return pendingTailCall;
        }
    }

    private static final ThreadLocal<EvalState> CURRENT = ThreadLocal.withInitial(EvalState::new);

    /** Reused for evaluations with no bindings — avoids allocation per evaluate() call. */
    private static final JsonataBindings EMPTY_BINDINGS = new JsonataBindings();

    /**
     * Merges permanent bindings from the generated class with per-evaluation
     * bindings and installs the result as the active bindings for this thread.
     *
     * <p>Must be paired with a {@link #endEvaluation()} call in a finally block.
     *
     * @param permanentValues    permanent named values registered on the expression instance
     * @param permanentFunctions permanent named functions registered on the expression instance
     * @param perEval            per-evaluation bindings, or {@code null}
     * @param instanceRegexes    per-instance regex cache field from the expression instance
     */
    static void beginEvaluation(Map<String, JsonNode> permanentValues,
                                Map<String, JsonataBoundFunction> permanentFunctions,
                                JsonataBindings perEval,
                                Map<String, org.joni.Regex> instanceRegexes) {
        JsonataBindings merged;
        if (permanentValues.isEmpty() && permanentFunctions.isEmpty() && perEval == null) {
            merged = EMPTY_BINDINGS;
        } else {
            merged = new JsonataBindings();
            permanentValues.forEach(merged::bindValue);
            permanentFunctions.forEach(merged::bindFunction);
            if (perEval != null) {
                perEval.getValues().forEach(merged::bindValue);
                perEval.getFunctions().forEach(merged::bindFunction);
            }
        }
        CURRENT.get().begin(merged, System.currentTimeMillis(), instanceRegexes);
    }

    /**
     * Clears the active bindings for the current thread.
     * Always call this in a {@code finally} block after {@link #beginEvaluation}.
     */
    static void endEvaluation() {
        CURRENT.get().end();
    }

    /**
     * Returns the full eval state for the current thread, or {@code null} if outside
     * an evaluation. LambdaRegistry uses this to pay only one ThreadLocal.get() per
     * fn_apply invocation instead of separate lookups for call depth, pending TCO, and lambdas.
     */
    static EvalState getState() {
        EvalState s = CURRENT.get();
        return s.active ? s : null;
    }

    /** Returns the per-evaluation lambda map for the current thread, or {@code null} if outside an evaluation. */
    static Map<String, JsonataLambda> getEvalLambdas() {
        EvalState s = CURRENT.get();
        return s.active ? s.evalLambdas() : null;
    }

    /** Returns the per-instance regex map for the current evaluation thread, or {@code null}. */
    static Map<String, org.joni.Regex> getInstanceRegexes() {
        EvalState s = CURRENT.get();
        return s.active ? s.instanceRegexes : null;
    }

    /**
     * Returns the evaluation-start timestamp in milliseconds since the Unix epoch.
     * If called outside an active evaluation (e.g., in a test), falls back to the
     * current wall-clock time so callers never receive {@code null}.
     */
    static long evaluationMillis() {
        EvalState s = CURRENT.get();
        return s.active ? s.millis : System.currentTimeMillis();
    }

    /**
     * Resolves a named value from the active bindings.
     *
     * @param name the variable name (without the leading {@code $})
     * @return the bound {@link JsonNode}, or {@link JsonataRuntime#MISSING} if not bound
     */
    static JsonNode resolveBinding(String name) {
        EvalState s = CURRENT.get();
        if (!s.active) return JsonataRuntime.MISSING;
        JsonNode v = s.bindings.getValue(name);
        return v != null ? v : JsonataRuntime.MISSING;
    }

    /**
     * Calls a named function from the active bindings.
     *
     * @param name the function name (without the leading {@code $})
     * @param args the arguments to pass
     * @return the function result, or {@link JsonataRuntime#MISSING} if no function is bound to {@code name}
     * @throws RuntimeEvaluationException if the function throws
     */
    static JsonNode callBoundFunction(String name, JsonNode[] args) throws RuntimeEvaluationException {
        EvalState s = CURRENT.get();
        if (s.active) {
            JsonataBoundFunction fn = s.bindings.getFunction(name);
            if (fn != null) {
                List<JsonNode> coerced = FunctionSignature.coerce(
                        fn.getFunctionSignature(), Arrays.asList(args));
                try {
                    return fn.apply(new JsonataFunctionArguments(coerced));
                } catch (JsonataEvaluationException e) {
                    throw new RuntimeEvaluationException(e.getErrorCode(), "Error calling bound function", e);
                }
            }
        }
        throw new RuntimeEvaluationException("T1006", "The function '" + name + "' is not defined");
    }
}
