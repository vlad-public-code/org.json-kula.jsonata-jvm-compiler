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

    /**
     * True when the array constructor path step should preserve inner arrays as
     * single items (used for the {@code $.[arr][]} pattern, i.e. ForceArray wraps
     * a path ending with ArrayConstructor).
     */
    final boolean arrayConstructorPreserve;

    /**
     * The Java variable name that is the cross-join base, i.e. the parent from
     * which cross-joined FieldRef steps should navigate.  Set by a
     * {@code @$var.FieldRef} ContextBinding handler and propagated through
     * subsequent path steps.  {@code null} when not inside a cross-join context.
     */
    final String crossJoinParent;

    /**
     * True when the expression being generated is in <em>tail position</em> within
     * a user-defined lambda body.  When set, user function calls are emitted as
     * {@code fn_apply_tco(…)} instead of {@code fn_apply(…)}, enabling the
     * trampoline loop in {@code LambdaRegistry.fn_apply} to perform TCO without
     * growing the JVM call stack.
     */
    final boolean isTailPosition;

    /**
     * When non-null, the Java variable name (e.g. {@code "$c"}) that was bound by
     * the most recent {@code @$var} ContextBinding before a GroupByExpr.  Inside
     * {@code visitGroupByExpr}, this variable is excluded from the captured-parameter
     * list and instead rebound to the group element ({@code __ge<N>}) in the value
     * section, so that aggregation functions see the whole group rather than a single
     * element captured from the outer iteration.
     */
    final String primaryContextVar;

    /**
     * When non-null, the Java variable name (e.g. {@code "$o"}) for the position
     * bound by {@code #$pos} that was stashed into each sort-tuple's second element.
     * The {@code applyStep} ObjectConstructor case reads this to generate a block
     * lambda that unpacks {@code [element, $o]} tuples before constructing objects.
     */
    final String tuplePos;

    GenCtx(String ctxVar, String rootVar, GenState state) {
        this(ctxVar, rootVar, state, List.of(), false, false, null, false, null, null);
    }

    private GenCtx(String ctxVar, String rootVar, GenState state, List<String> parentVars,
                   boolean inArrayConstructorStep, boolean arrayConstructorPreserve,
                   String crossJoinParent, boolean isTailPosition,
                   String primaryContextVar, String tuplePos) {
        this.ctxVar     = ctxVar;
        this.rootVar    = rootVar;
        this.state      = state;
        this.parentVars = parentVars;
        this.inArrayConstructorStep = inArrayConstructorStep;
        this.arrayConstructorPreserve = arrayConstructorPreserve;
        this.crossJoinParent = crossJoinParent;
        this.isTailPosition = isTailPosition;
        this.primaryContextVar = primaryContextVar;
        this.tuplePos   = tuplePos;
    }

    GenCtx withCtx(String newCtx) {
        return new GenCtx(newCtx, rootVar, state, parentVars, inArrayConstructorStep, arrayConstructorPreserve, crossJoinParent, false, primaryContextVar, tuplePos);
    }

    /** Returns a new context with {@code newCtx} as context and the current ctxVar pushed as parent. */
    GenCtx withCtxAndParent(String newCtx) {
        List<String> newParents = new java.util.ArrayList<>(parentVars);
        newParents.add(ctxVar);
        return new GenCtx(newCtx, rootVar, state, newParents, inArrayConstructorStep, arrayConstructorPreserve, crossJoinParent, false, primaryContextVar, tuplePos);
    }

    /** Returns a new context with the given parent vars list. */
    GenCtx withParents(List<String> newParentVars) {
        return new GenCtx(ctxVar, rootVar, state, newParentVars, inArrayConstructorStep, arrayConstructorPreserve, crossJoinParent, isTailPosition, primaryContextVar, tuplePos);
    }

    /** Returns a new context with inArrayConstructorStep flag set. */
    GenCtx withInArrayConstructorStep() {
        return new GenCtx(ctxVar, rootVar, state, parentVars, true, arrayConstructorPreserve, crossJoinParent, false, primaryContextVar, tuplePos);
    }

    /** Returns a new context with arrayConstructorPreserve flag set. */
    GenCtx withArrayConstructorPreserve() {
        return new GenCtx(ctxVar, rootVar, state, parentVars, inArrayConstructorStep, true, crossJoinParent, false, primaryContextVar, tuplePos);
    }

    /** Returns a new context with the cross-join parent variable set. */
    GenCtx withCrossJoinParent(String cjp) {
        return new GenCtx(ctxVar, rootVar, state, parentVars, inArrayConstructorStep, arrayConstructorPreserve, cjp, isTailPosition, primaryContextVar, tuplePos);
    }

    /** Returns a new context with the tail-position flag set to {@code tp}. */
    GenCtx withTailPosition(boolean tp) {
        return new GenCtx(ctxVar, rootVar, state, parentVars, inArrayConstructorStep, arrayConstructorPreserve, crossJoinParent, tp, primaryContextVar, tuplePos);
    }

    /** Returns a new context with the primary-context variable set (for GroupBy value rebinding). */
    GenCtx withPrimaryContextVar(String pcv) {
        return new GenCtx(ctxVar, rootVar, state, parentVars, inArrayConstructorStep, arrayConstructorPreserve, crossJoinParent, isTailPosition, pcv, tuplePos);
    }

    /** Returns a new context with the tuple-position variable set (for position-sort tuple unpacking). */
    GenCtx withTuplePos(String tp) {
        return new GenCtx(ctxVar, rootVar, state, parentVars, inArrayConstructorStep, arrayConstructorPreserve, crossJoinParent, isTailPosition, primaryContextVar, tp);
    }
}
