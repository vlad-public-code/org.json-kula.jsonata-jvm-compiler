package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataBoundFunction;

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
     * Fields that are only needed when user-defined functions are called (callDepth,
     * pendingTailCall) are lazily initialised on first access.
     */
    static final class EvalState {
        boolean active;
        JsonataBindings bindings;
        long millis;
        long timeoutDeadline;   // Long.MAX_VALUE = disabled
        Map<String, org.joni.Regex> instanceRegexes;
        int[] callDepth;
        LambdaRegistry.TailCallData[] pendingTailCall;
        JsonataRuntime.EvalDelegate evalDelegate;

        /** The suspended frame beneath this one, or {@code null} at the outermost evaluation. */
        private Frame suspended;

        /**
         * One suspended evaluation. Allocated only when an evaluation starts inside another —
         * {@code $eval}, or a bound function that evaluates an expression of its own. Before this
         * existed, the inner evaluation overwrote the outer one and cleared it on the way out, so
         * the outer expression silently lost its bindings from that point on.
         */
        private record Frame(JsonataBindings bindings, long millis, long timeoutDeadline,
                             Map<String, org.joni.Regex> instanceRegexes,
                             JsonataRuntime.EvalDelegate evalDelegate,
                             int[] callDepth, LambdaRegistry.TailCallData[] pendingTailCall,
                             Frame suspended) {}

        void begin(JsonataBindings bindings, long millis, Map<String, org.joni.Regex> instanceRegexes,
                   int timeoutMs, JsonataRuntime.EvalDelegate evalDelegate) {
            if (active) {
                // Nested evaluation: suspend the enclosing one rather than overwriting it. The
                // inner evaluation gets its own recursion budget, which is restored on the way out.
                this.suspended = new Frame(this.bindings, this.millis, this.timeoutDeadline,
                        this.instanceRegexes, this.evalDelegate,
                        this.callDepth, this.pendingTailCall, this.suspended);
                this.callDepth = null;
                this.pendingTailCall = null;
            }
            this.active = true;
            this.bindings = bindings;
            this.millis = millis;
            this.instanceRegexes = instanceRegexes;
            this.timeoutDeadline = timeoutMs > 0 ? millis + timeoutMs : Long.MAX_VALUE;
            this.evalDelegate = evalDelegate;
        }

        void end() {
            Frame outer = this.suspended;
            if (outer != null) {
                this.suspended = outer.suspended();
                this.bindings = outer.bindings();
                this.millis = outer.millis();
                this.timeoutDeadline = outer.timeoutDeadline();
                this.instanceRegexes = outer.instanceRegexes();
                this.evalDelegate = outer.evalDelegate();
                this.callDepth = outer.callDepth();
                this.pendingTailCall = outer.pendingTailCall();
                return;
            }
            this.active = false;
            this.bindings = null;
            this.instanceRegexes = null;
            this.evalDelegate = null;
            if (callDepth != null) callDepth[0] = 0;
            if (pendingTailCall != null) pendingTailCall[0] = null;
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
     * Installs the bindings visible to this evaluation: the expression's permanent set, overlaid
     * with any per-evaluation set.
     *
     * <p>Must be paired with a {@link #endEvaluation()} call in a finally block.
     *
     * <p>The overlay is only built when both sides are non-empty. The expression instance keeps its
     * permanent set pre-merged (see {@code AbstractJsonataExpression}), so the common cases —
     * permanent bindings only, per-evaluation bindings only, or none — install an existing object
     * and allocate nothing. This matters: rebuilding the map per call cost 3× the throughput of a
     * simple expression with one binding, and 9× with ten.
     *
     * @param permanent       the expression's permanent bindings; never {@code null}
     * @param perEval         per-evaluation bindings, or {@code null}
     * @param instanceRegexes per-instance regex cache field from the expression instance
     */
    static void beginEvaluation(JsonataBindings permanent,
                                JsonataBindings perEval,
                                Map<String, org.joni.Regex> instanceRegexes,
                                int timeoutMs,
                                JsonataRuntime.EvalDelegate evalDelegate) {
        JsonataBindings merged;
        if (perEval == null || perEval.isEmpty()) {
            merged = permanent;
        } else if (permanent.isEmpty()) {
            merged = perEval;
        } else {
            merged = new JsonataBindings();
            permanent.getValues().forEach(merged::bindValue);
            permanent.getFunctions().forEach(merged::bindFunction);
            perEval.getValues().forEach(merged::bindValue);
            perEval.getFunctions().forEach(merged::bindFunction);
        }
        CURRENT.get().begin(merged, System.currentTimeMillis(), instanceRegexes, timeoutMs, evalDelegate);
    }

    /** The {@code $eval} delegate of the expression being evaluated, or {@code null}. */
    static JsonataRuntime.EvalDelegate getEvalDelegate() {
        EvalState s = CURRENT.get();
        return s.active ? s.evalDelegate : null;
    }

    /** The shared empty binding set, installed when an expression binds nothing. */
    static JsonataBindings emptyBindings() {
        return EMPTY_BINDINGS;
    }

    /** Returns {@code true} if an evaluation is currently active on this thread. */
    static boolean isActive() {
        return CURRENT.get().active;
    }

    /** Returns {@code true} if the current evaluation has a deadline to respect. */
    static boolean hasDeadline() {
        EvalState s = CURRENT.get();
        return s.active && s.timeoutDeadline != Long.MAX_VALUE;
    }

    /**
     * Throws {@link RuntimeEvaluationException} U1001 if the current evaluation has exceeded
     * its deadline. No-op when no timeout is set ({@code timeoutDeadline == Long.MAX_VALUE}).
     */
    static void checkTimeout() throws RuntimeEvaluationException {
        EvalState s = CURRENT.get();
        if (s.active && s.timeoutDeadline != Long.MAX_VALUE
                && System.currentTimeMillis() > s.timeoutDeadline)
            throw new RuntimeEvaluationException("U1001", "Expression evaluation timeout");
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
     * fn_apply invocation instead of separate lookups for call depth and pending TCO.
     */
    static EvalState getState() {
        EvalState s = CURRENT.get();
        return s.active ? s : null;
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
     * <p>A name bound as a <em>function</em> resolves to a function value, so {@code $myFn} can be
     * passed to {@code $map}, piped through {@code ~>}, or handed to another bound function — the
     * same things a JSONata-defined function can do. Without this a bound function was callable and
     * nothing else, which also made a function exported by {@code compileLibrary} unusable as an
     * argument, since library exports are registered as bound functions.
     *
     * <p>The values map wins if a name appears in both: {@code $name} in value position asks for a
     * value. (The call site {@code $name(...)} resolves the other way round — see
     * {@link #callBoundFunction}.)
     *
     * @param name the variable name (without the leading {@code $})
     * @return the bound {@link JsonNode}, or {@link JsonataRuntime#MISSING} if not bound
     */
    static JsonNode resolveBinding(String name) {
        EvalState s = CURRENT.get();
        if (!s.active) return JsonataRuntime.MISSING;
        JsonNode v = s.bindings.getValue(name);
        if (v != null) return v;
        JsonataBoundFunction fn = s.bindings.getFunction(name);
        if (fn != null) return BoundFunctionValue.of(name, fn);
        return JsonataRuntime.MISSING;
    }

    /**
     * Calls a named function from the active bindings.
     *
     * <p>Falls back to the values map when no function is bound under {@code name}: a value that is
     * a function — bound directly, or returned by an expression that was bound — is callable as
     * {@code $name(...)}, not only usable as an argument. The equivalent one-line rebind
     * ({@code $g := $name; $g(...)}) has always worked, so refusing the direct call was an
     * arbitrary distinction rather than a safeguard.
     *
     * @param name the function name (without the leading {@code $})
     * @param args the arguments to pass
     * @return the function result
     * @throws RuntimeEvaluationException if the function throws, or nothing callable is bound
     */
    static JsonNode callBoundFunction(String name, JsonNode[] args) throws RuntimeEvaluationException {
        EvalState s = CURRENT.get();
        if (s.active) {
            JsonataBoundFunction fn = s.bindings.getFunction(name);
            if (fn != null) return BoundFunctionValue.call(name, fn, args);

            JsonNode value = s.bindings.getValue(name);
            if (LambdaRegistry.isLambdaToken(value)) return BoundFunctionValue.apply(value, args);
        }
        throw new RuntimeEvaluationException("T1006", "The function '" + name + "' is not defined");
    }
}
