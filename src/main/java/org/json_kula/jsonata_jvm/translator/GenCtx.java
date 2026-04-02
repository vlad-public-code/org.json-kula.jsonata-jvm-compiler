package org.json_kula.jsonata_jvm.translator;

import java.util.List;

/**
 * Immutable per-node context.  A new instance is created whenever the
 * current-context variable name ({@code ctxVar}) changes (e.g. inside a
 * predicate lambda).
 */
final class GenCtx {
    final String ctxVar;   // Java variable holding the current context value
    final String rootVar;  // Java variable holding the document root
    final GenState state;

    /**
     * Stack of parent-level Java variable names, used to resolve {@code %} (parent
     * operator). The last element is the immediate parent of {@code ctxVar}.
     * Empty if no parent context is available.
     */
    final List<String> parentVars;

    /** True if we're inside an array constructor used as a step (e.g. Email.[address]). */
    final boolean inArrayConstructorStep;

    GenCtx(String ctxVar, String rootVar, GenState state) {
        this(ctxVar, rootVar, state, List.of(), false);
    }

    private GenCtx(String ctxVar, String rootVar, GenState state, List<String> parentVars, 
                   boolean inArrayConstructorStep) {
        this.ctxVar     = ctxVar;
        this.rootVar    = rootVar;
        this.state      = state;
        this.parentVars = parentVars;
        this.inArrayConstructorStep = inArrayConstructorStep;
    }

    GenCtx withCtx(String newCtx) {
        return new GenCtx(newCtx, rootVar, state, parentVars, inArrayConstructorStep);
    }

    /** Returns a new context with {@code newCtx} as context and the current ctxVar pushed as parent. */
    GenCtx withCtxAndParent(String newCtx) {
        List<String> newParents = new java.util.ArrayList<>(parentVars);
        newParents.add(ctxVar);
        return new GenCtx(newCtx, rootVar, state, newParents, inArrayConstructorStep);
    }

    /** Returns a new context with the given parent vars list. */
    GenCtx withParents(List<String> newParentVars) {
        return new GenCtx(ctxVar, rootVar, state, newParentVars, inArrayConstructorStep);
    }

    /** Returns a new context with inArrayConstructorStep flag set. */
    GenCtx withInArrayConstructorStep() {
        return new GenCtx(ctxVar, rootVar, state, parentVars, true);
    }
}
