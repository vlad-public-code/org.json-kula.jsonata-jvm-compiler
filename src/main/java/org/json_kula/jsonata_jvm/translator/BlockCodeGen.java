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

        // Single expression: skip the helper method overhead.
        if (exprs.size() == 1) return exprs.get(0).accept(t, ctx);

        // Multiple expressions: emit as a private helper method (a "block method").
        int id = ctx.state.nextId();
        String methodName = "__block" + id;

        // Collect the names defined by this block's own VariableBindings.
        Set<String> blockLocalNames = new LinkedHashSet<>();
        for (AstNode expr : exprs) {
            if (expr instanceof VariableBinding vb) blockLocalNames.add(vb.name());
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
            String alias = ctx.state.getAlias(v);
            String javaName = alias != null ? alias : "$" + v;
            extraParamDecls.append(", JsonNode ").append(javaName);
            if (ctx.state.holderVars.contains(v)) {
                extraCallArgs.append(", $").append(v).append("Ref[0]");
            } else {
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
              .append(") throws JsonataEvaluationException {\n");

            // Pre-declare all holder arrays at the very top so lambdas defined
            // earlier in the block can capture them before assignment.
            for (String name : holderNeeded) {
                sb.append("    JsonNode[] $").append(name).append("Ref = {MISSING};\n");
            }

            for (int i = 0; i < exprs.size() - 1; i++) {
                AstNode expr = exprs.get(i);
                if (expr instanceof VariableBinding vb) {
                    emitVarBinding(t, vb, sb, innerCtx);
                } else {
                    sb.append("    ").append(expr.accept(t, innerCtx)).append(";\n");
                }
            }

            AstNode last = exprs.get(exprs.size() - 1);
            if (last instanceof VariableBinding vb) {
                emitVarBinding(t, vb, sb, innerCtx);
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
        String valExpr = vb.value().accept(t, ctx);
        sb.append("    JsonNode $").append(vb.name()).append(" = ").append(valExpr).append(";\n");
        if (ctx.state.holderVars.contains(vb.name())) {
            sb.append("    $").append(vb.name()).append("Ref[0] = $").append(vb.name()).append(";\n");
        }
    }
}
