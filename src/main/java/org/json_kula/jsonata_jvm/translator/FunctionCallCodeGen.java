package org.json_kula.jsonata_jvm.translator;

import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Code generation for function calls and lambda expressions.
 *
 * <p>All methods are package-private static helpers that take a {@link Translator}
 * as their first argument to delegate visitor calls back through the translator.
 */
final class FunctionCallCodeGen {

    private FunctionCallCodeGen() {}

    /** Generates a call to a user-defined variable function: {@code $myFn(args)}. */
    static String genUserFunctionCall(Translator t, FunctionCall n, List<String> args, GenCtx ctx) {
        String arrayLiteral = args.isEmpty()
                ? "new JsonNode[0]"
                : "new JsonNode[]{" + String.join(", ", args) + "}";
        if (ctx.state.isLocal(n.name())) {
            // Determine the Java expression that holds the lambda token.
            final String fnRef;
            if (ctx.state.holderVars.contains(n.name())) {
                fnRef = "$" + n.name() + "Ref[0]";
            } else {
                String alias = ctx.state.getAlias(n.name());
                fnRef = alias != null ? alias : "$" + n.name();
            }
            // Single-arg: pass directly; multi-arg: pack into array so the lambda
            // can unpack each parameter via the multi-param inline-lambda pattern.
            if (args.size() <= 1) {
                return "fn_apply(" + fnRef + ", " + (args.isEmpty() ? "NULL" : args.get(0)) + ")";
            } else {
                return "fn_apply(" + fnRef + ", array(" + String.join(", ", args) + "))";
            }
        }
        // Not a local — look up as an externally bound function.
        return "callBoundFunction(\"" + n.name() + "\", " + arrayLiteral + ")";
    }

    /**
     * Generates a sort call.  If the second argument is a Lambda, emit it as
     * an inline Java lambda.  Otherwise, emit a plain sort.
     */
    static String genSort(Translator t, FunctionCall n, List<String> args, GenCtx ctx) {
        if (args.size() == 1) return "fn_sort(" + args.get(0) + ")";
        // Second arg expected to be a key function
        return genHigherOrder(t, "fn_sort", n, args, ctx, 0, 1);
    }

    /**
     * Generates a call to a higher-order function ({@code fn_map}, {@code fn_filter},
     * etc.) where the argument at {@code fnArgIndex} may be a {@link Lambda} node.
     * If it is a Lambda, the body is inlined as a Java lambda expression so that
     * outer-scope variables (including {@code __root} and {@code __ctx}) are
     * captured correctly.
     *
     * <p>When the lambda has more than one parameter <em>and</em> the runtime
     * method is {@code fn_map} or {@code fn_filter}, the indexed variant is used
     * instead ({@code fn_map_indexed} / {@code fn_filter_indexed}), which passes a
     * {@code [value, index, array]} tuple.  A tuple-unpacking helper is generated
     * via {@link #genUnpackLambda} so each named parameter receives the right value.
     */
    static String genHigherOrder(Translator t, String rtMethod,
                                  FunctionCall n, List<String> args,
                                  GenCtx ctx,
                                  int seqArgIndex, int fnArgIndex) {
        String seqExpr = args.get(seqArgIndex);
        AstNode fnArg  = n.args().get(fnArgIndex);

        if (fnArg instanceof Lambda lam && lam.params().size() > 1
                && (rtMethod.equals("fn_map") || rtMethod.equals("fn_filter"))) {
            // Multi-param $map / $filter: use the indexed variant that passes
            // [value, index, array] so $i and $a parameters are available.
            String indexedMethod = rtMethod.equals("fn_map") ? "fn_map_indexed" : "fn_filter_indexed";
            String lambdaExpr = genUnpackLambda(t, lam, ctx, 3);
            return indexedMethod + "(" + seqExpr + ", " + lambdaExpr + ")";
        }

        String lambdaExpr;
        if (fnArg instanceof Lambda lam) {
            lambdaExpr = inlineLambda(t, lam, ctx);
        } else {
            // fn is some expression that evaluates to a lambdaNode — wrap it
            String fnExpr = fnArg.accept(t, ctx);
            lambdaExpr = "(__elem -> fn_apply(" + fnExpr + ", __elem))";
        }
        return rtMethod + "(" + seqExpr + ", " + lambdaExpr + ")";
    }

    /**
     * Generates {@code fn_reduce(arr, fn, init)}.
     *
     * <p>The runtime passes a {@code [acc, elem]} tuple to the lambda on each
     * iteration.  For a single-param lambda the whole tuple is passed as-is (the
     * caller rarely cares about the signature detail in that case).  For a
     * multi-param lambda a tuple-unpacking helper is generated so {@code $prev}
     * and {@code $curr} — or however the parameters are named — each receive their
     * correct value.
     */
    static String genReduce(Translator t, FunctionCall n, List<String> args, GenCtx ctx) {
        String arrExpr  = args.get(0);
        AstNode fnArg   = n.args().get(1);
        String initExpr = args.size() > 2 ? args.get(2) : "MISSING";
        String lambdaExpr;
        if (fnArg instanceof Lambda lam && lam.params().size() > 1) {
            // Unpack the [acc, elem] pair into named parameters.
            lambdaExpr = genUnpackLambda(t, lam, ctx, 2);
        } else if (fnArg instanceof Lambda lam) {
            lambdaExpr = inlineLambda(t, lam, ctx);
        } else {
            // fn is an expression that evaluates to a lambdaNode (e.g. a variable holding
            // a user-defined function).  Wrap it so fn_reduce receives a JsonataLambda.
            lambdaExpr = "(__elem -> fn_apply(" + fnArg.accept(t, ctx) + ", __elem))";
        }
        return "fn_reduce(" + arrExpr + ", " + lambdaExpr + ", " + initExpr + ")";
    }

    /**
     * Generates {@code fn_each(obj, fn)} where the lambda receives a
     * {@code [value, key, object]} tuple (value first, per JSONata spec).
     * Always uses {@link #genUnpackLambda} so named parameters like {@code $v}
     * and {@code $k} resolve to the correct tuple slots.
     */
    static String genEach(Translator t, FunctionCall n, List<String> args, GenCtx ctx) {
        String objExpr = args.get(0);
        AstNode fnArg  = n.args().get(1);
        String lambdaExpr;
        if (fnArg instanceof Lambda lam) {
            lambdaExpr = genUnpackLambda(t, lam, ctx, 3);
        } else {
            String fnExpr = fnArg.accept(t, ctx);
            lambdaExpr = "(__elem -> fn_apply(" + fnExpr + ", __elem))";
        }
        return "fn_each(" + objExpr + ", " + lambdaExpr + ")";
    }

    /**
     * Generates {@code fn_sift(obj, fn)} where the lambda receives a
     * {@code [value, key, object]} tuple.  A tuple-unpacking helper is always
     * generated for Lambda arguments so that named parameters like {@code $v}
     * and {@code $k} resolve to the correct tuple slots.
     */
    static String genSift(Translator t, FunctionCall n, List<String> args, GenCtx ctx) {
        String objExpr = args.get(0);
        AstNode fnArg  = n.args().get(1);
        String lambdaExpr;
        if (fnArg instanceof Lambda lam) {
            lambdaExpr = genUnpackLambda(t, lam, ctx, 3);
        } else {
            String fnExpr = fnArg.accept(t, ctx);
            lambdaExpr = "(__elem -> fn_apply(" + fnExpr + ", __elem))";
        }
        return "fn_sift(" + objExpr + ", " + lambdaExpr + ")";
    }

    /**
     * Generates a private helper method that receives a tuple {@link
     * com.fasterxml.jackson.databind.node.ArrayNode ArrayNode} and unpacks its
     * elements into the lambda's named parameters before evaluating the body.
     * Used for multi-param lambdas passed to higher-order functions where the
     * runtime packs multiple values (e.g. {@code [value, index, array]}) into a
     * single {@code JsonNode}.
     *
     * @param t        the translator (visitor)
     * @param lam      the lambda whose parameters and body to use
     * @param ctx      code-gen context
     * @param tupleLen number of tuple positions the runtime provides
     * @return a {@code this::methodName} reference that can be used as a
     *         {@link org.json_kula.jsonata_jvm.runtime.JsonataLambda}
     */
    static String genUnpackLambda(Translator t, Lambda lam, GenCtx ctx, int tupleLen) {
        int id = ctx.state.nextId();
        String methodName = "__unpack" + id;

        ctx.state.pushScope();
        lam.params().forEach(ctx.state::addLocalVar);
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\nprivate JsonNode ").append(methodName)
              .append("(JsonNode __el) throws RuntimeEvaluationException {\n");
            for (int i = 0; i < lam.params().size(); i++) {
                if (i < tupleLen) {
                    sb.append("    JsonNode $").append(lam.params().get(i))
                      .append(" = __el.get(").append(i).append(");\n");
                } else {
                    sb.append("    JsonNode $").append(lam.params().get(i)).append(" = MISSING;\n");
                }
            }
            String bodyExpr = lam.body().accept(t, ctx.withCtx("__el"));
            sb.append("    return ").append(bodyExpr).append(";\n");
            sb.append("}\n");
            ctx.state.helperMethods.append(sb);
        } finally {
            ctx.state.popScope();
        }

        return "this::" + methodName;
    }

    /**
     * Inlines a {@link Lambda} as a Java lambda expression, binding its first
     * parameter to the element and any remaining parameters to {@link
     * org.json_kula.jsonata_jvm.runtime.JsonataRuntime#MISSING}.
     */
    static String inlineLambda(Translator t, Lambda lam, GenCtx ctx) {
        if (lam.params().isEmpty()) {
            String bodyExpr = lam.body().accept(t, ctx);
            return "(__ignored -> " + bodyExpr + ")";
        }
        // First parameter = the element; bind remaining to MISSING in the body ctx.
        // We generate a thunk-style method for multi-param lambdas to keep
        // the call site readable, and inline for single-param.
        if (lam.params().size() == 1) {
            String p1 = "$" + lam.params().get(0);
            ctx.state.pushScope();
            ctx.state.addLocalVar(lam.params().get(0));
            try {
                String bodyExpr = lam.body().accept(t, ctx);
                return "(" + p1 + " -> " + bodyExpr + ")";
            } finally {
                ctx.state.popScope();
            }
        }
        // Multi-param: generate a helper method.
        return genLambdaMethod(t, lam, ctx);
    }

    /**
     * Builds an inline Java lambda expression for a JSONata {@link Lambda}.
     *
     * <ul>
     *   <li>Zero params: {@code (__ignored -> body)}</li>
     *   <li>One param: {@code ($p -> body)} — the param is added to scope so
     *       {@link Translator#visitVariableRef} resolves it as a Java local, and any
     *       enclosing block-local variables are captured naturally.</li>
     *   <li>Multi-param: {@code (__pkN -> { JsonNode $p0__N = ...; ... return body; })}
     *       — args are packed by the caller into an {@code ArrayNode}; the lambda
     *       unpacks each slot.  Id-suffixed Java names are used to prevent
     *       shadowing outer-scope variables with the same JSONata name.</li>
     * </ul>
     */
    static String buildInlineLambda(Translator t, Lambda lam, GenCtx ctx) {
        if (lam.params().isEmpty()) {
            String body = lam.body().accept(t, ctx);
            return "(__ignored -> " + body + ")";
        }
        if (lam.params().size() == 1) {
            String p = "$" + lam.params().get(0);
            ctx.state.pushScope();
            ctx.state.addLocalVar(lam.params().get(0));
            try {
                String body = lam.body().accept(t, ctx.withCtx(p));
                return "(" + p + " -> " + body + ")";
            } finally {
                ctx.state.popScope();
            }
        }
        // Multi-param: pack args into an ArrayNode at the call site, unpack here.
        // Use id-suffixed names to avoid shadowing identically-named outer locals.
        int id = ctx.state.nextId();
        String packed = "__pk" + id;
        ctx.state.pushScope();
        List<String> javaNames = new ArrayList<>();
        for (String param : lam.params()) {
            String javaName = "$" + param + "__" + id;
            javaNames.add(javaName);
            ctx.state.addLocalVarWithAlias(param, javaName);
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("(").append(packed).append(" -> {\n");
            sb.append("    JsonNode ").append(javaNames.get(0)).append(" = ")
              .append(packed).append(".isArray() ? ").append(packed).append(".get(0) : ").append(packed).append(";\n");
            for (int i = 1; i < lam.params().size(); i++) {
                sb.append("    JsonNode ").append(javaNames.get(i)).append(" = ")
                  .append(packed).append(".isArray() && !").append(packed).append(".get(").append(i).append(").isMissingNode()")
                  .append(" ? ").append(packed).append(".get(").append(i).append(") : MISSING;\n");
            }
            String body = lam.body().accept(t, ctx);
            sb.append("    return ").append(body).append(";\n})");
            return sb.toString();
        } finally {
            ctx.state.popScope();
        }
    }

    /**
     * Generates a private helper method for a lambda and returns a Java method
     * reference expression that can be used as a {@link org.json_kula.jsonata_jvm.runtime.JsonataLambda}.
     * Used only by {@link #genUnpackLambda} and the multi-param fallback of
     * {@link #inlineLambda}.
     */
    static String genLambdaMethod(Translator t, Lambda lam, GenCtx ctx) {
        int id = ctx.state.nextId();
        String methodName = "__lambda" + id;

        ctx.state.pushScope();
        lam.params().forEach(ctx.state::addLocalVar);
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\nprivate JsonNode ").append(methodName)
              .append("(JsonNode __el) throws RuntimeEvaluationException {\n");
            for (int i = 0; i < lam.params().size(); i++) {
                if (i == 0) {
                    sb.append("    JsonNode $").append(lam.params().get(i)).append(" = __el;\n");
                } else {
                    sb.append("    JsonNode $").append(lam.params().get(i)).append(" = MISSING;\n");
                }
            }
            String bodyExpr = lam.body().accept(t, ctx.withCtx("__el"));
            sb.append("    return ").append(bodyExpr).append(";\n");
            sb.append("}\n");
            ctx.state.helperMethods.append(sb);
        } finally {
            ctx.state.popScope();
        }

        return "this::" + methodName;
    }
}
