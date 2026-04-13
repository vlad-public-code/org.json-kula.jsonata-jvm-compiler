package org.json_kula.jsonata_jvm.translator;

import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Code generation for block expressions and variable bindings.
 *
 * <p>All methods are package-private static helpers that take a {@link Translator}
 * as their first argument to delegate visitor calls back through the translator.
 */
final class BlockCodeGen {

    private BlockCodeGen() {}

    static String visitBlock(Translator t, Block n, GenCtx ctx) {
        List<AstNode> exprs = n.expressions();

        // Empty block: return MISSING (undefined)
        if (exprs.isEmpty()) return "MISSING";

        // Single expression: skip the helper method overhead.
        // Exception: a VariableBinding whose value is a self-referential lambda needs
        // the holder-array pattern so that the lambda body can call itself by name
        // (e.g. "$match := function($s,$o){... next: function(){$match(...)}}").
        // Without the holder pattern the single-expression shortcut would never add
        // $match to scope, causing the translator to fall back to the built-in fn_match.
        if (exprs.size() == 1) {
            AstNode only = exprs.get(0);
            boolean needsHolder = only instanceof VariableBinding vb
                && vb.value() instanceof Lambda lam
                && ScopeAnalyzer.containsVarRef(lam.body(), vb.name());
            if (!needsHolder) return only.accept(t, ctx);
        }

        // Multiple expressions: emit as a private helper method (a "block method").
        int id = ctx.state.nextId();
        String methodName = "__block" + id;

        // Collect the names defined by this block's own VariableBindings,
        // including names from chained (right-associative) assignments like $a := $b := 5.
        Set<String> blockLocalNames = new LinkedHashSet<>();
        for (AstNode expr : exprs) {
            collectBindingNames(expr, blockLocalNames);
        }

        // Pre-pass: find every variable that needs an array-holder.
        // This covers both self-recursive lambdas AND forward references — i.e. a
        // lambda that is defined at position i but calls a variable bound at j > i.
        // All holder arrays are pre-declared at the top of the helper method so that
        // lambdas defined earlier can safely capture them before they are assigned.
        Set<String> holderNeeded = ScopeAnalyzer.computeHolderNeeded(exprs, blockLocalNames);

        // Find outer-scope locals that are free variables in this block's body.
        // They must be passed as extra method parameters so the helper method
        // can reference them (outer Java locals are not accessible across methods).
        List<String> capturedVars = ScopeAnalyzer.collectFreeOuterVars(exprs, blockLocalNames, ctx.state);

        // Build extra parameter declarations (method signature) and call arguments.
        StringBuilder extraParamDecls = new StringBuilder();
        StringBuilder extraCallArgs   = new StringBuilder();
        for (String v : capturedVars) {
            if (ctx.state.holderVars.contains(v)) {
                // Pass the holder array itself so the block method can access $vRef[0]
                extraParamDecls.append(", JsonNode[] $").append(v).append("Ref");
                extraCallArgs.append(", $").append(v).append("Ref");
            } else {
                String alias = ctx.state.getAlias(v);
                String javaName = alias != null ? alias : "$" + v;
                extraParamDecls.append(", JsonNode ").append(javaName);
                extraCallArgs.append(", ").append(javaName);
            }
        }

        // If any expression in the block references %, pass the outer parentVars as
        // extra parameters so % resolves correctly inside the helper method.
        boolean hasParentRef = exprs.stream().anyMatch(ScopeAnalyzer::containsParentStep);
        List<String> outerParentVars = ctx.parentVars;
        List<String> parentParamNames = new java.util.ArrayList<>();
        StringBuilder parentParamDecls = new StringBuilder();
        StringBuilder parentCallArgs   = new StringBuilder();
        if (hasParentRef && !outerParentVars.isEmpty()) {
            for (int pi = 0; pi < outerParentVars.size(); pi++) {
                String pn = "__pp" + id + "_" + pi;
                parentParamNames.add(pn);
                parentParamDecls.append(", JsonNode ").append(pn);
                parentCallArgs.append(", ").append(outerParentVars.get(pi));
            }
        }

        // The block method uses fixed parameter names __root / __ctx so that
        // the body generation context is stable regardless of how the block
        // was called (e.g. from inside an inline lambda with ctxVar = "$x").
        GenCtx innerCtx = new GenCtx("__ctx", "__root", ctx.state).withParents(parentParamNames);

        // Activate all holder variables BEFORE compiling the body so that
        // visitVariableRef emits $nameRef[0] for every holder reference,
        // including forward references to variables not yet assigned.
        // Non-holder variables are added to scope lazily inside emitVarBinding,
        // AFTER their RHS is generated.  This ensures the RHS sees the outer
        // alias (e.g. the lambda-parameter alias $step__1), not the uninitialized
        // inner local $step, when a variable shadows a same-named outer binding.
        ctx.state.holderVars.addAll(holderNeeded);
        ctx.state.pushScope();
        for (String name : holderNeeded) ctx.state.addLocalVar(name);

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\nprivate JsonNode ").append(methodName)
              .append("(JsonNode __root, JsonNode __ctx")
              .append(extraParamDecls)
              .append(parentParamDecls)
              .append(") throws RuntimeEvaluationException {\n");

            // Pre-declare all holder arrays at the very top so lambdas defined
            // earlier in the block can capture them before assignment.
            for (String name : holderNeeded) {
                sb.append("    JsonNode[] $").append(name).append("Ref = {MISSING};\n");
            }

            Set<String> declared = new java.util.HashSet<>();
            for (int i = 0; i < exprs.size() - 1; i++) {
                AstNode expr = exprs.get(i);
                if (expr instanceof VariableBinding vb) {
                    emitVarBinding(t, vb, sb, innerCtx, declared);
                } else {
                    sb.append("    ").append(expr.accept(t, innerCtx)).append(";\n");
                }
            }

            // The last expression of the block is in tail position when the block
            // itself is in tail position (i.e. when it is the body of a lambda).
            GenCtx lastCtx = ctx.isTailPosition ? innerCtx.withTailPosition(true) : innerCtx;
            AstNode last = exprs.get(exprs.size() - 1);
            if (last instanceof VariableBinding vb) {
                emitVarBinding(t, vb, sb, lastCtx, declared);
                sb.append("    return $").append(vb.name()).append(";\n");
            } else {
                sb.append("    return ").append(last.accept(t, lastCtx)).append(";\n");
            }

            sb.append("}\n");
            ctx.state.helperMethods.append(sb);
        } finally {
            ctx.state.popScope();
            ctx.state.holderVars.removeAll(holderNeeded);
        }

        return methodName + "(" + ctx.rootVar + ", " + ctx.ctxVar + extraCallArgs + parentCallArgs + ")";
    }

    /**
     * Emits a single {@link VariableBinding} as a {@code JsonNode $name = ...;}
     * statement.  If {@code name} is in {@code ctx.state.holderVars} the holder
     * array was already pre-declared at the top of the block method; we only need
     * to write the assignment and the corresponding {@code $nameRef[0] = $name;}
     * update so that lambdas that captured the holder see the real value.
     */
    static void emitVarBinding(Translator t, VariableBinding vb, StringBuilder sb, GenCtx ctx) {
        emitVarBinding(t, vb, sb, ctx, new java.util.HashSet<>());
    }

    static void emitVarBinding(Translator t, VariableBinding vb, StringBuilder sb, GenCtx ctx,
                                Set<String> declared) {
        // Special case: desugared function call with % as callee and no parent context.
        // Emitting MISSING allows fn_apply to throw T1006 ("not a function") at runtime
        // rather than S0217 during compilation.
        if (vb.name().startsWith("__call_") && vb.value() instanceof AstNode.ParentStep
                && ctx.parentVars.isEmpty()) {
            if (declared.add(vb.name())) {
                sb.append("    JsonNode $").append(vb.name()).append(" = MISSING;\n");
            } else {
                sb.append("    $").append(vb.name()).append(" = MISSING;\n");
            }
            return;
        }
        // For chained assignment $a := $b := val: emit the inner binding first,
        // then use the inner variable as the value of the outer binding.
        if (vb.value() instanceof VariableBinding innerVb) {
            emitVarBinding(t, innerVb, sb, ctx, declared);
            String innerRef = "$" + innerVb.name();
            if (declared.add(vb.name())) {
                sb.append("    JsonNode $").append(vb.name()).append(" = ").append(innerRef).append(";\n");
            } else {
                sb.append("    $").append(vb.name()).append(" = ").append(innerRef).append(";\n");
            }
            if (ctx.state.holderVars.contains(vb.name())) {
                sb.append("    $").append(vb.name()).append("Ref[0] = $").append(vb.name()).append(";\n");
            }
            // Lazily register in scope so subsequent statements resolve to this local.
            ctx.state.addLocalVar(vb.name());
            return;
        }
        String valExpr = vb.value().accept(t, ctx);
        if (declared.add(vb.name())) {
            // First declaration of this variable in this block
            sb.append("    JsonNode $").append(vb.name()).append(" = ").append(valExpr).append(";\n");
        } else {
            // Re-binding: just reassign (no re-declaration)
            sb.append("    $").append(vb.name()).append(" = ").append(valExpr).append(";\n");
        }
        if (ctx.state.holderVars.contains(vb.name())) {
            sb.append("    $").append(vb.name()).append("Ref[0] = $").append(vb.name()).append(";\n");
        }
        // Lazily register in scope so subsequent statements resolve to this local
        // (non-holders were not pre-added to scope; the lazy registration also ensures
        // that the RHS of THIS binding sees the outer alias, not the uninitialized local).
        ctx.state.addLocalVar(vb.name());
    }

    /**
     * Recursively collects variable names from a VariableBinding chain.
     * Handles chained assignments like {@code $a := $b := 5} where the outer binding's
     * value is itself a VariableBinding.
     */
    private static void collectBindingNames(AstNode expr, Set<String> names) {
        if (expr instanceof VariableBinding vb) {
            names.add(vb.name());
            collectBindingNames(vb.value(), names); // recurse into value for chains
        }
    }
}
