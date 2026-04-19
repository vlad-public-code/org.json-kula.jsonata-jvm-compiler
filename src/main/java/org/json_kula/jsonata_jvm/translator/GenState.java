package org.json_kula.jsonata_jvm.translator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Mutable state shared across all {@link GenCtx} instances in one translation. */
final class GenState {
    int counter;
    final StringBuilder helperMethods = new StringBuilder();
    /** Counter-array declarations (final long[] __ctrN = {0};) emitted before the body expression. */
    final StringBuilder localDeclarations = new StringBuilder();

    /**
     * Stack of locally-bound variable name sets, one entry per active scope
     * (block or lambda body). Used by {@link #isLocal} to decide whether a
     * {@code VariableRef} should resolve to a Java local variable or to a
     * runtime binding lookup.
     */
    final Deque<Set<String>> scopeStack = new ArrayDeque<>();

    /**
     * Variables that use an array-holder pattern for recursive self-reference.
     * When {@code name} is in this set, {@link Translator#visitVariableRef} emits
     * {@code $nameRef[0]} instead of {@code $name}.
     */
    final Set<String> holderVars = new HashSet<>();

    /**
     * Per-scope alias maps: when a multi-param lambda uses id-suffixed Java
     * names to avoid shadowing, the canonical JSONata name maps to its Java
     * alias.  Mirrors the scope stack — pushed and popped together.
     */
    private final Deque<Map<String, String>> aliasStack = new ArrayDeque<>();

    /** Set to a variable name while generating a PartialApplication body. */
    String partialPhVar = null;
    boolean partialPhNeedIdx = false;
    int partialPhIdx = 0;

    int nextId() { return counter++; }

    /** Opens a new lexical scope (with an associated alias scope). */
    void pushScope() {
        scopeStack.push(new HashSet<>());
        aliasStack.push(new HashMap<>());
    }

    /** Closes the innermost lexical scope and its alias scope. */
    void popScope() {
        if (!scopeStack.isEmpty()) { scopeStack.pop(); aliasStack.pop(); }
    }

    /** Adds {@code name} to the innermost open scope with no alias. */
    void addLocalVar(String name) { if (!scopeStack.isEmpty()) scopeStack.peek().add(name); }

    /**
     * Adds {@code name} to the innermost scope with a Java alias.
     * {@link Translator#visitVariableRef} will emit {@code javaName} instead of
     * the default {@code $name}.
     */
    void addLocalVarWithAlias(String name, String javaName) {
        if (!scopeStack.isEmpty()) {
            scopeStack.peek().add(name);
            aliasStack.peek().put(name, javaName);
        }
    }

    /**
     * Returns {@code true} if {@code name} is defined in any active lexical
     * scope, meaning it should be emitted as a Java local variable reference
     * rather than a runtime binding lookup.
     */
    boolean isLocal(String name) {
        for (Set<String> scope : scopeStack) {
            if (scope.contains(name)) return true;
        }
        return false;
    }

    /**
     * Returns the Java alias for {@code name} in the innermost scope that
     * <em>defines</em> {@code name}, or {@code null} if no alias exists in that
     * scope (meaning the default {@code $name} should be used).
     *
     * <p>Searches {@code scopeStack} and {@code aliasStack} in lock-step from
     * innermost to outermost and stops as soon as the defining scope is found.
     * This prevents an outer alias from leaking through into an inner scope that
     * re-binds the same name without an alias (e.g. a block that rebinds a lambda
     * parameter via {@code $step := ...}).
     */
    String getAlias(String name) {
        java.util.Iterator<Set<String>> scopeIt = scopeStack.iterator();
        java.util.Iterator<Map<String, String>> aliasIt = aliasStack.iterator();
        while (scopeIt.hasNext() && aliasIt.hasNext()) {
            Set<String> scope = scopeIt.next();
            Map<String, String> aliases = aliasIt.next();
            if (scope.contains(name)) {
                return aliases.get(name); // null if no alias in the defining scope
            }
        }
        return null;
    }
}
