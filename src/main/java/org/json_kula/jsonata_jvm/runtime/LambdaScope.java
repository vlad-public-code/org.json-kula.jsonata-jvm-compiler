package org.json_kula.jsonata_jvm.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A durable home for lambdas that must outlive the evaluation that created them.
 *
 * <p>Ordinary user-defined functions live in the per-evaluation lambda map, which
 * {@link EvaluationContext.EvalState#end()} clears when {@code evaluate()} returns — a lambda token
 * minted during one evaluation cannot be called after it. That is the right lifetime for a function
 * value that only flows within a single expression, but not for a <em>function library</em>: a
 * definition expression is evaluated once and its functions are then called from other expressions,
 * on other threads, possibly with no evaluation active at all.
 *
 * <p>While a scope is installed on the current evaluation (see
 * {@link EvaluationContext#beginEvaluation(Map, Map, org.json_kula.jsonata_jvm.JsonataBindings, Map, int, LambdaScope)}),
 * {@link LambdaRegistry#lambdaNode} writes into the scope and mints <em>scope-qualified</em> keys of
 * the form {@code <scopeId>/<counter>}. Such a token carries its own home, so it resolves anywhere
 * for as long as the scope is registered — no push/pop at the call site, and no dependence on which
 * evaluation happens to be running.
 *
 * <p>Scopes are registered in a process-wide table on {@link #create()} and removed by
 * {@link #close()}. A scope that becomes unreachable without being closed is removed automatically
 * by a {@link java.lang.ref.Cleaner}, so a forgotten library costs a bounded amount of memory (its
 * own closures) rather than growing without limit.
 */
public final class LambdaScope implements AutoCloseable {

    private static final ConcurrentHashMap<String, Map<String, JsonataLambda>> SCOPES =
            new ConcurrentHashMap<>();
    private static final AtomicLong SCOPE_COUNTER = new AtomicLong();
    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

    /** Separates the scope id from the per-lambda counter inside a token key. */
    static final char SCOPE_SEPARATOR = '/';

    private final String id;
    private final Map<String, JsonataLambda> lambdas;

    private LambdaScope(String id, Map<String, JsonataLambda> lambdas) {
        this.id = id;
        this.lambdas = lambdas;
    }

    /**
     * Creates and registers a new scope. The caller owns it and should {@link #close()} it when the
     * functions it holds are no longer needed.
     */
    public static LambdaScope create() {
        String id = "s" + SCOPE_COUNTER.incrementAndGet();
        Map<String, JsonataLambda> lambdas = new ConcurrentHashMap<>();
        SCOPES.put(id, lambdas);
        LambdaScope scope = new LambdaScope(id, lambdas);
        CLEANER.register(scope, () -> SCOPES.remove(id));
        return scope;
    }

    /** Returns the scope id used as the prefix of every token key minted into this scope. */
    public String id() {
        return id;
    }

    /** Returns the number of lambdas held by this scope. */
    public int size() {
        return lambdas.size();
    }

    /** Returns {@code true} if this scope is still registered (i.e. not yet closed). */
    public boolean isOpen() {
        return SCOPES.containsKey(id);
    }

    /**
     * Deregisters this scope. Tokens minted into it stop resolving; calling a function that was
     * exported from it afterwards fails with the usual "not found" evaluation error.
     */
    @Override
    public void close() {
        SCOPES.remove(id);
        lambdas.clear();
    }

    void put(String key, JsonataLambda fn) {
        lambdas.put(key, fn);
    }

    /**
     * Resolves a scope-qualified key, or returns {@code null} if the key is not scope-qualified,
     * names an unknown scope, or names an unknown lambda within a known scope.
     */
    static JsonataLambda resolveQualified(String key) {
        int sep = key.indexOf(SCOPE_SEPARATOR);
        if (sep <= 0) return null;
        Map<String, JsonataLambda> scope = SCOPES.get(key.substring(0, sep));
        return scope != null ? scope.get(key) : null;
    }
}
