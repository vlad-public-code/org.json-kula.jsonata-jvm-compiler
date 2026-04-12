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
        if (exprs.size() == 1) return exprs.get(0).accept(t, ctx);

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

        // The block method uses fixed parameter names __root / __ctx so that
        // the body generation context is stable regardless of how the block
        // was called (e.g. from inside an inline lambda with ctxVar = "$x").
        GenCtx innerCtx = new GenCtx("__ctx", "__root", ctx.state);

        // Activate all holder variables BEFORE compiling the body so that
        // visitVariableRef emits $nameRef[0] for every holder reference,
        // including forward references to variables not yet assigned.
        ctx.state.holderVars.addAll(holderNeeded);
        ctx.state.pushScope();
        for (String name : blockLocalNames) ctx.state.addLocalVar(name);

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\nprivate JsonNode ").append(methodName)
              .append("(JsonNode __root, JsonNode __ctx")
              .append(extraParamDecls)
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

            AstNode last = exprs.get(exprs.size() - 1);
            if (last instanceof VariableBinding vb) {
                emitVarBinding(t, vb, sb, innerCtx, declared);
                sb.append("    return $").append(vb.name()).append(";\n");
            } else {
                sb.append("    return ").append(last.accept(t, innerCtx)).append(";\n");
            }

            sb.append("}\n");
            ctx.state.helperMethods.append(sb);
        } finally {
            ctx.state.popScope();
            ctx.state.holderVars.removeAll(holderNeeded);
        }

        return methodName + "(" + ctx.rootVar + ", " + ctx.ctxVar + extraCallArgs + ")";
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
