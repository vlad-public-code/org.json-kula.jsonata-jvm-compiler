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
     * Holds the active {@link JsonataBindings} for the current evaluation thread.
     * Set by {@link #beginEvaluation} and cleared by {@link #endEvaluation}.
     */
    private static final ThreadLocal<JsonataBindings> CURRENT_BINDINGS = new ThreadLocal<>();

    /**
     * Captures the wall-clock time at the start of each evaluation, in milliseconds
     * since the Unix epoch.  Per the JSONata specification, every call to
     * {@code $now()} and {@code $millis()} within the same expression evaluation
     * must return this identical timestamp value.
     */
    private static final ThreadLocal<Long> EVALUATION_MILLIS = new ThreadLocal<>();

    /**
     * Merges permanent bindings from the generated class with per-evaluation
     * bindings and installs the result as the active bindings for this thread.
     *
     * <p>Must be paired with a {@link #endEvaluation()} call in a finally block.
     *
     * @param permanentValues    permanent named values registered on the expression instance
     * @param permanentFunctions permanent named functions registered on the expression instance
     * @param perEval            per-evaluation bindings, or {@code null}
     */
    static void beginEvaluation(Map<String, JsonNode> permanentValues,
                                Map<String, JsonataBoundFunction> permanentFunctions,
                                JsonataBindings perEval) {
        JsonataBindings merged = new JsonataBindings();
        permanentValues.forEach(merged::bindValue);
        permanentFunctions.forEach(merged::bindFunction);
        if (perEval != null) {
            // Per-evaluation bindings override permanent ones.
            perEval.getValues().forEach(merged::bindValue);
            perEval.getFunctions().forEach(merged::bindFunction);
        }
        CURRENT_BINDINGS.set(merged);
        EVALUATION_MILLIS.set(System.currentTimeMillis());
    }

    /**
     * Clears the active bindings for the current thread.
     * Always call this in a {@code finally} block after {@link #beginEvaluation}.
     */
    static void endEvaluation() {
        CURRENT_BINDINGS.remove();
        EVALUATION_MILLIS.remove();
    }

    /**
     * Returns the evaluation-start timestamp in milliseconds since the Unix epoch.
     * If called outside an active evaluation (e.g., in a test), falls back to the
     * current wall-clock time so callers never receive {@code null}.
     */
    static long evaluationMillis() {
        Long t = EVALUATION_MILLIS.get();
        return t != null ? t : System.currentTimeMillis();
    }

    /**
     * Resolves a named value from the active bindings.
     *
     * @param name the variable name (without the leading {@code $})
     * @return the bound {@link JsonNode}, or {@link JsonataRuntime#MISSING} if not bound
     */
    static JsonNode resolveBinding(String name) {
        JsonataBindings b = CURRENT_BINDINGS.get();
        if (b == null) return JsonataRuntime.MISSING;
        JsonNode v = b.getValue(name);
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
        JsonataBindings b = CURRENT_BINDINGS.get();
        if (b != null) {
            JsonataBoundFunction fn = b.getFunction(name);
            if (fn != null) {
                List<JsonNode> coerced = FunctionSignature.coerce(
                        fn.getFunctionSignature(), Arrays.asList(args));
                try {
                    return fn.apply(new JsonataFunctionArguments(coerced));
                } catch (JsonataEvaluationException e) {
                    throw new RuntimeEvaluationException("Error calling bound function", e);
                }
            }
        }
        throw new RuntimeEvaluationException("T1006: The function '" + name + "' is not defined");
    }
}
