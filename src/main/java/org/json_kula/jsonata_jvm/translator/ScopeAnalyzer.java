package org.json_kula.jsonata_jvm.translator;

import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static AST analysis utilities for the code generator.
 *
 * <p>These methods analyse the AST to determine which variables need
 * array-holder patterns, which outer-scope locals are free variables in a
 * block, and whether a given variable name appears free inside a subtree.
 */
final class ScopeAnalyzer {

    private ScopeAnalyzer() {}

    /**
     * Returns the set of variable names in {@code exprs} that require an
     * array-holder pattern.  This covers two cases:
     * <ol>
     *   <li><b>Self-recursive lambdas</b> — the lambda body directly or
     *       indirectly refers to the variable being defined.</li>
     *   <li><b>Forward references</b> — a lambda at position {@code i} in the
     *       block refers to a variable that is only bound at position {@code j > i}.
     *       The array holder is pre-declared before any binding so the earlier
     *       lambda can capture it safely; the holder is filled in when the later
     *       binding is eventually executed.</li>
     * </ol>
     */
    static Set<String> computeHolderNeeded(List<AstNode> exprs, Set<String> blockLocalNames) {
        Set<String> result = new HashSet<>();

        // Build an index: variable name → position in binding list.
        List<VariableBinding> bindings = exprs.stream()
                .filter(e -> e instanceof VariableBinding)
                .map(e -> (VariableBinding) e)
                .toList();
        Map<String, Integer> bindingIndex = new HashMap<>();
        for (int i = 0; i < bindings.size(); i++) bindingIndex.put(bindings.get(i).name(), i);

        for (int i = 0; i < bindings.size(); i++) {
            VariableBinding vb = bindings.get(i);
            if (!(vb.value() instanceof Lambda lam)) continue;

            // Case 1: self-recursive — body references the variable itself.
            if (containsVarRef(lam.body(), vb.name())) result.add(vb.name());

            // Case 2: forward reference — body references a variable bound later.
            Set<String> refs  = new LinkedHashSet<>();
            Set<String> bound = new HashSet<>(lam.params()); // params are locally bound
            collectFreeVarsInto(lam.body(), refs, bound);
            for (String ref : refs) {
                Integer j = bindingIndex.get(ref);
                if (j != null && j > i) result.add(ref); // ref is defined after this lambda
            }
        }
        return result;
    }

    /**
     * Returns the outer-scope local variable names that appear as free variables
     * in {@code exprs}, i.e. names that are in scope externally but not defined
     * by the block itself.  The result is ordered for deterministic code gen.
     */
    static List<String> collectFreeOuterVars(List<AstNode> exprs,
                                              Set<String> blockLocals,
                                              GenState state) {
        // Build the set of all outer-scope locals (everything in scope).
        // We deliberately do NOT remove blockLocals here: a block-local binding
        // may shadow an outer variable of the same name while still referencing
        // it in its own RHS (e.g. "$step := ($step ? $step : 1)" where the outer
        // $step is a lambda parameter).  Such references are only detected when
        // the outer name is retained in outerLocals.
        Set<String> outerLocals = new LinkedHashSet<>();
        for (Set<String> scope : state.scopeStack) outerLocals.addAll(scope);

        if (outerLocals.isEmpty()) return List.of();

        // Walk the AST of the block body to find which outer locals are actually
        // referenced.  We start with an empty bound set and add names as their
        // bindings are encountered so that the RHS of "$x := $x + 1" correctly
        // detects the outer "$x" as a free variable before "$x" itself is bound.
        Set<String> used  = new LinkedHashSet<>();
        Set<String> bound = new HashSet<>();
        for (AstNode expr : exprs) {
            collectFreeVarsInto(expr, used, bound);
            if (expr instanceof VariableBinding vb) bound.add(vb.name());
        }
        used.retainAll(outerLocals);
        return new ArrayList<>(used);
    }

    /** Recursively collects variable references that are free (not locally bound). */
    static void collectFreeVarsInto(AstNode node, Set<String> used, Set<String> bound) {
        if (node == null) return;
        switch (node) {
            case VariableRef vr -> { if (!bound.contains(vr.name())) used.add(vr.name()); }
            case FunctionCall fc -> {
                if (!bound.contains(fc.name())) used.add(fc.name());
                fc.args().forEach(a -> collectFreeVarsInto(a, used, bound));
            }
            case Lambda lam -> {
                Set<String> inner = new HashSet<>(bound);
                inner.addAll(lam.params());
                collectFreeVarsInto(lam.body(), used, inner);
            }
            case Block blk -> {
                Set<String> inner = new HashSet<>(bound);
                for (AstNode e : blk.expressions()) {
                    collectFreeVarsInto(e, used, inner);
                    if (e instanceof VariableBinding vb) inner.add(vb.name());
                }
            }
            case VariableBinding vb    -> collectFreeVarsInto(vb.value(), used, bound);
            case BinaryOp op           -> { collectFreeVarsInto(op.left(), used, bound);
                                            collectFreeVarsInto(op.right(), used, bound); }
            case UnaryMinus um         -> collectFreeVarsInto(um.operand(), used, bound);
            case ConditionalExpr ce    -> { collectFreeVarsInto(ce.condition(), used, bound);
                                            collectFreeVarsInto(ce.then(), used, bound);
                                            if (ce.otherwise() != null) collectFreeVarsInto(ce.otherwise(), used, bound); }
            case PathExpr pe           -> pe.steps().forEach(s -> collectFreeVarsInto(s, used, bound));
            case ArrayConstructor ac   -> ac.elements().forEach(e -> collectFreeVarsInto(e, used, bound));
            case ObjectConstructor oc  -> oc.pairs().forEach(p -> { collectFreeVarsInto(p.key(), used, bound);
                                                                      collectFreeVarsInto(p.value(), used, bound); });
            case PredicateExpr pe      -> { collectFreeVarsInto(pe.source(), used, bound);
                                            collectFreeVarsInto(pe.predicate(), used, bound); }
            case ArraySubscript as     -> { collectFreeVarsInto(as.source(), used, bound);
                                            collectFreeVarsInto(as.index(), used, bound); }
            case RangeExpr re          -> { collectFreeVarsInto(re.from(), used, bound);
                                            collectFreeVarsInto(re.to(), used, bound); }
            case SortExpr se           -> { collectFreeVarsInto(se.source(), used, bound);
                                            se.keys().forEach(k -> collectFreeVarsInto(k.key(), used, bound)); }
            case GroupByExpr gbe       -> { collectFreeVarsInto(gbe.source(), used, bound);
                                            gbe.pairs().forEach(p -> { collectFreeVarsInto(p.key(), used, bound);
                                                                        collectFreeVarsInto(p.value(), used, bound); }); }
            case ChainExpr ce          -> ce.steps().forEach(s -> collectFreeVarsInto(s, used, bound));
            case TransformExpr te      -> { collectFreeVarsInto(te.source(), used, bound);
                                            collectFreeVarsInto(te.pattern(), used, bound);
                                            collectFreeVarsInto(te.update(), used, bound); }
            case ForceArray fa         -> collectFreeVarsInto(fa.source(), used, bound);
            case Parenthesized p       -> collectFreeVarsInto(p.inner(), used, bound);
            case ElvisExpr ee          -> { collectFreeVarsInto(ee.left(), used, bound);
                                            collectFreeVarsInto(ee.right(), used, bound); }
            case CoalesceExpr ce2      -> { collectFreeVarsInto(ce2.left(), used, bound);
                                            collectFreeVarsInto(ce2.right(), used, bound); }
            case PartialApplication pa -> {
                if (!bound.contains(pa.name())) used.add(pa.name());
                pa.args().forEach(a -> collectFreeVarsInto(a, used, bound));
            }
            default                    -> {} // leaf nodes: literals, FieldRef, WildcardStep, PartialPlaceholder, etc.
        }
    }

    /**
     * Returns {@code true} if {@code node} (or any sub-expression, respecting
     * lambda parameter bindings) contains a reference to {@code name} as a free
     * variable.  Used to detect recursive self-references in lambda bindings.
     */
    static boolean containsVarRef(AstNode node, String name) {
        Set<String> used  = new HashSet<>();
        Set<String> bound = new HashSet<>();
        collectFreeVarsInto(node, used, bound);
        return used.contains(name);
    }

    /**
     * Returns {@code true} if {@code node} contains a {@link AstNode.ParentStep}
     * anywhere in the subtree (including nested paths within constructors).
     */
    static boolean containsParentStep(AstNode node) {
        if (node == null) return false;
        return switch (node) {
            case AstNode.ParentStep ps           -> true;
            case AstNode.PathExpr pe             -> pe.steps().stream().anyMatch(ScopeAnalyzer::containsParentStep);
            case AstNode.ObjectConstructor oc    -> oc.pairs().stream()
                    .anyMatch(p -> containsParentStep(p.key()) || containsParentStep(p.value()));
            case AstNode.ArrayConstructor ac     -> ac.elements().stream().anyMatch(ScopeAnalyzer::containsParentStep);
            case AstNode.PredicateExpr pe        -> containsParentStep(pe.source()) || containsParentStep(pe.predicate());
            case AstNode.BinaryOp bo             -> containsParentStep(bo.left()) || containsParentStep(bo.right());
            case AstNode.ConditionalExpr ce      -> containsParentStep(ce.condition()) || containsParentStep(ce.then())
                    || (ce.otherwise() != null && containsParentStep(ce.otherwise()));
            case AstNode.Block blk               -> blk.expressions().stream().anyMatch(ScopeAnalyzer::containsParentStep);
            case AstNode.FunctionCall fc         -> fc.args().stream().anyMatch(ScopeAnalyzer::containsParentStep);
            case AstNode.Lambda lam              -> containsParentStep(lam.body());
            default                              -> false;
        };
    }
}
