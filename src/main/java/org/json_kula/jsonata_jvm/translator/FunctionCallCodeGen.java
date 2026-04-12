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
            // packArgs is used (not array) to avoid flattening array arguments.
            if (args.size() <= 1) {
                return "fn_apply(" + fnRef + ", " + (args.isEmpty() ? "NULL" : args.get(0)) + ")";
            } else {
                return "fn_apply(" + fnRef + ", packArgs(" + String.join(", ", args) + "))";
            }
        }
        // Not a local — look up as an externally bound function.
        return "callBoundFunction(\"" + n.name() + "\", " + arrayLiteral + ")";
    }

    /**
     * Generates a sort call.  If the second argument is a 2-param Lambda, it is
     * treated as a comparator ({@code fn_sort_comparator}); a 1-param Lambda is
     * treated as a key extractor ({@code fn_sort}).
     */
    static String genSort(Translator t, FunctionCall n, List<String> args, GenCtx ctx) {
        if (args.size() == 1) return "fn_sort(" + args.get(0) + ")";
        AstNode fnArg = n.args().get(1);
        if (fnArg instanceof Lambda lam && lam.params().size() >= 2) {
            // 2-param lambda is a comparator: function($a, $b){ $a > $b }
            String lambdaExpr = genUnpackLambda(t, lam, ctx, 2);
            return "fn_sort_comparator(" + args.get(0) + ", " + lambdaExpr + ")";
        }
        // 1-param or expression: key extractor
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
     * Generates {@code fn_single(arr)} or {@code fn_single(arr, predicate)} or
     * {@code fn_single_indexed(arr, predicate)} depending on argument count and lambda arity.
     */
    static String genSingle(Translator t, FunctionCall n, List<String> args, GenCtx ctx) {
        if (n.args().size() < 2) {
            // 1-arg: $single(arr) — no predicate
            String seqExpr = args.isEmpty() ? ctx.ctxVar : args.get(0);
            return "fn_single(" + seqExpr + ")";
        }
        String seqExpr = args.get(0);
        AstNode fnArg = n.args().get(1);
        if (fnArg instanceof Lambda lam && lam.params().size() > 1) {
            String lambdaExpr = genUnpackLambda(t, lam, ctx, 3);
            return "fn_single_indexed(" + seqExpr + ", " + lambdaExpr + ")";
        }
        String lambdaExpr;
        if (fnArg instanceof Lambda lam) {
            lambdaExpr = inlineLambda(t, lam, ctx);
        } else {
            String fnExpr = fnArg.accept(t, ctx);
            lambdaExpr = "(__elem -> fn_apply(" + fnExpr + ", __elem))";
        }
        return "fn_single(" + seqExpr + ", " + lambdaExpr + ")";
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
        if (fnArg instanceof Lambda lam && lam.params().size() < 2) {
            // D3050: reducer function must accept at least 2 parameters
            return "fn_throw(\"D3050: The second argument of $reduce must accept at least 2 parameters, got " + lam.params().size() + "\")";
        } else if (fnArg instanceof Lambda lam && lam.params().size() > 1) {
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
        // $each(fn) — 1-arg form: use context as the object
        String objExpr;
        int fnArgIdx;
        if (n.args().size() == 1) {
            objExpr = ctx.ctxVar;
            fnArgIdx = 0;
        } else {
            objExpr = args.get(0);
            fnArgIdx = 1;
        }
        AstNode fnArg = n.args().get(fnArgIdx);
        String lambdaExpr;
        if (fnArg instanceof Lambda lam) {
            lambdaExpr = genUnpackLambda(t, lam, ctx, 3);
        } else {
            // External function reference: fn_each passes [value, key, obj] tuple.
            // Pass only the value (element 0) to single-arg function references.
            String fnExpr = fnArg.accept(t, ctx);
            lambdaExpr = "(__elem -> fn_apply(" + fnExpr + ", __elem.get(0)))";
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
        String objExpr;
        int fnArgIdx;
        if (n.args().size() == 1) {
            objExpr = ctx.ctxVar;
            fnArgIdx = 0;
        } else {
            objExpr = args.get(0);
            fnArgIdx = 1;
        }
        AstNode fnArg = n.args().get(fnArgIdx);
        String lambdaExpr;
        if (fnArg instanceof Lambda lam) {
            lambdaExpr = genUnpackLambda(t, lam, ctx, 3);
        } else {
            // External function: pass just the value (tuple element 0) for 1-arity functions
            String fnExpr = fnArg.accept(t, ctx);
            lambdaExpr = "(__elem -> fn_apply(" + fnExpr + ", __elem.get(0)))";
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
    /**
     * Like {@link #buildInlineLambda} but prepends signature type checks when
     * the lambda has a non-null signature.
     */
    static String buildInlineLambdaWithSig(Translator t, Lambda lam, GenCtx ctx) {
        if (lam.signature() == null) return buildInlineLambda(t, lam, ctx);
        List<String> paramTypes = parseSignatureParams(lam.signature(), lam.params().size());
        // Only add checks if there are type-constrained params
        boolean hasChecks = false;
        for (String pt : paramTypes) if (buildTypeCheck(pt, "$x", 1) != null) { hasChecks = true; break; }
        if (!hasChecks) return buildInlineLambda(t, lam, ctx);

        // Build inline lambda with type checks prepended
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
            // Unpack args
            if (lam.params().isEmpty()) {
                // nothing to unpack
            } else if (lam.params().size() == 1) {
                sb.append("    JsonNode ").append(javaNames.get(0)).append(" = ")
                  .append(packed).append(".isArray() ? ").append(packed).append(".get(0) : ").append(packed).append(";\n");
            } else {
                sb.append("    JsonNode ").append(javaNames.get(0)).append(" = ")
                  .append(packed).append(".isArray() ? ").append(packed).append(".get(0) : ").append(packed).append(";\n");
                for (int i = 1; i < lam.params().size(); i++) {
                    sb.append("    JsonNode ").append(javaNames.get(i)).append(" = ")
                      .append(packed).append(".isArray() && ").append(packed).append(".size() > ").append(i)
                      .append(" ? ").append(packed).append(".get(").append(i).append(") : MISSING;\n");
                }
            }
            // Type checks
            for (int i = 0; i < paramTypes.size() && i < lam.params().size(); i++) {
                String check = buildTypeCheck(paramTypes.get(i), javaNames.get(i), i + 1);
                if (check != null) sb.append("    ").append(check).append("\n");
            }
            String body = lam.body().accept(t, ctx);
            sb.append("    return ").append(body).append(";\n})");
            return sb.toString();
        } finally {
            ctx.state.popScope();
        }
    }

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
                String body = lam.body().accept(t, ctx);
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
                  .append(packed).append(".isArray() && ").append(packed).append(".size() > ").append(i)
                  .append(" && !").append(packed).append(".get(").append(i).append(").isMissingNode()")
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

    /**
     * Generates code for an immediately-invoked lambda with a signature.
     * Handles type coercion/checking (T0410/T0412) and context binding ('-' flag).
     *
     * <p>Focus ('-') params consume positional args when available; they only fall
     * back to context when fewer explicit args are supplied than non-focus params
     * require.  When {@code numArgs < numParams}, non-focus params are satisfied
     * first (in position order), and any remainder fills focus params; unsatisfied
     * focus params receive the current context value.
     *
     * <p>Variadic ('+') specs cover multiple consecutive param positions: the spec
     * absorbs params until only enough remain to satisfy subsequent required specs.
     * Extra args beyond {@code numParams} are validated against the last variadic
     * spec; if none exists, T0410 is thrown.
     */
    static String genLambdaCall(Translator t, LambdaCall n, GenCtx ctx) {
        Lambda lam = n.lambda();
        String sig = lam.signature();
        List<AstNode> callArgs = n.args();
        List<String> params = lam.params();
        int numParams = params.size();
        int numArgs = callArgs.size();

        // Parse all signature specs (no param-count cap)
        List<String> allSpecs = parseSignatureParams(sig, Integer.MAX_VALUE);

        // Map each param position to the spec that should validate it
        String[] specPerParam = computeSpecPerParam(allSpecs, numParams);

        // Identify focus ('-') params
        boolean[] isFocus = new boolean[numParams];
        for (int i = 0; i < numParams; i++) {
            String spec = specPerParam[i];
            isFocus[i] = spec != null && (spec.equals("-") || spec.endsWith("-"));
        }

        // Find the last variadic spec (used for extra-arg validation)
        String lastVariadicSpec = null;
        for (String s : allSpecs) if (s.endsWith("+")) lastVariadicSpec = s;

        // If there are extra args and no variadic spec → always T0410
        if (numArgs > numParams && lastVariadicSpec == null) {
            // Evaluate args for their expressions (side-effect ordering), then throw
            return "fn_throw(\"T0410: Too many arguments supplied to lambda expression\")";
        }

        // Compute explicit arg expressions
        List<String> argExprs = new ArrayList<>();
        for (AstNode arg : callArgs) {
            argExprs.add(arg.accept(t, ctx));
        }

        // Build finalArgs (one entry per param):
        //   numArgs >= numParams → positional assignment for all params
        //   numArgs  < numParams → non-focus params consume args first (in position order),
        //                          then remaining args go to focus params; unsatisfied focus
        //                          params fall back to context, non-focus params to MISSING.
        List<String> finalArgs = new ArrayList<>();
        if (numArgs >= numParams) {
            for (int i = 0; i < numParams; i++) finalArgs.add(argExprs.get(i));
        } else {
            // Phase 1: assign args to non-focus params in position order
            int[] assignedArgIdx = new int[numParams];
            java.util.Arrays.fill(assignedArgIdx, -1);
            int queueIdx = 0;
            for (int i = 0; i < numParams && queueIdx < numArgs; i++) {
                if (!isFocus[i]) assignedArgIdx[i] = queueIdx++;
            }
            // Phase 2: remaining args fill focus params in position order
            for (int i = 0; i < numParams && queueIdx < numArgs; i++) {
                if (isFocus[i]) assignedArgIdx[i] = queueIdx++;
            }
            // Build finalArgs from assignment
            for (int i = 0; i < numParams; i++) {
                if (assignedArgIdx[i] >= 0) finalArgs.add(argExprs.get(assignedArgIdx[i]));
                else if (isFocus[i])         finalArgs.add(ctx.ctxVar);
                else                          finalArgs.add("MISSING");
            }
        }

        int numExtra = Math.max(0, numArgs - numParams);

        // Generate helper method
        int id = ctx.state.nextId();
        String methodName = "__lc" + id;
        StringBuilder sb = new StringBuilder();

        sb.append("\nprivate JsonNode ").append(methodName).append("(JsonNode ").append(ctx.rootVar);
        for (int i = 0; i < numParams; i++) sb.append(", JsonNode $").append(params.get(i));
        for (int e = 0; e < numExtra; e++)  sb.append(", JsonNode __extra").append(e);
        sb.append(") throws RuntimeEvaluationException {\n");

        // Type checks / coercions for named params
        for (int i = 0; i < numParams; i++) {
            String check = buildTypeCheck(specPerParam[i], "$" + params.get(i), i + 1);
            if (check != null) sb.append("    ").append(check).append("\n");
        }

        // Validate extra args against the last variadic spec
        if (numExtra > 0 && lastVariadicSpec != null) {
            for (int e = 0; e < numExtra; e++) {
                String check = buildTypeCheck(lastVariadicSpec, "__extra" + e, numParams + e + 1);
                if (check != null) sb.append("    ").append(check).append("\n");
            }
        }

        // Push scope and emit body
        ctx.state.pushScope();
        for (String p : params) ctx.state.addLocalVar(p);
        try {
            String bodyExpr = lam.body().accept(t, ctx.withCtx(params.isEmpty() ? ctx.ctxVar : "$" + params.get(0)));
            sb.append("    return ").append(bodyExpr).append(";\n");
        } finally {
            ctx.state.popScope();
        }
        sb.append("}\n");
        ctx.state.helperMethods.append(sb);

        // Call the helper
        StringBuilder call = new StringBuilder(methodName).append("(").append(ctx.rootVar);
        for (String a : finalArgs)                        call.append(", ").append(a);
        for (int i = numParams; i < numArgs; i++)         call.append(", ").append(argExprs.get(i));
        call.append(")");
        return call.toString();
    }

    /**
     * Maps each param position (0-based) to the signature spec that should validate it.
     * Variadic ('+') specs expand to cover multiple consecutive positions, absorbing
     * params while leaving enough room for subsequent required specs.
     */
    private static String[] computeSpecPerParam(List<String> allSpecs, int numParams) {
        String[] result = new String[numParams];
        int specIdx = 0;
        int paramIdx = 0;
        while (specIdx < allSpecs.size() && paramIdx < numParams) {
            String spec = allSpecs.get(specIdx);
            boolean variadic = spec.endsWith("+");

            // Count non-variadic specs remaining after this one (they each need ≥1 param slot)
            int remainingRequired = 0;
            for (int k = specIdx + 1; k < allSpecs.size(); k++) {
                if (!allSpecs.get(k).endsWith("+")) remainingRequired++;
            }

            result[paramIdx++] = spec;

            if (variadic) {
                // Absorb additional params while room remains for required downstream specs
                while (paramIdx < numParams && (numParams - paramIdx) > remainingRequired) {
                    result[paramIdx++] = spec;
                }
            }
            specIdx++;
        }
        return result;
    }

    /**
     * Parses a signature string like {@code <nn:a>} or {@code <a<n>>} and returns
     * the per-parameter type specs as a list. Strips the return type (after ':').
     */
    private static List<String> parseSignatureParams(String sig, int paramCount) {
        List<String> result = new ArrayList<>();
        if (sig == null || !sig.startsWith("<")) return result;
        // Remove outer < >
        String inner = sig.substring(1, sig.length() - 1);
        // Remove return type (after ':' at depth 0)
        int colonIdx = -1;
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            else if (c == ':' && depth == 0) { colonIdx = i; break; }
        }
        if (colonIdx >= 0) inner = inner.substring(0, colonIdx);

        // Parse type specs from inner
        int i = 0;
        while (i < inner.length() && result.size() < paramCount) {
            char c = inner.charAt(i);
            if (c == '(') {
                // Union type: find closing ')'
                int j = i + 1;
                int d = 1;
                while (j < inner.length() && d > 0) {
                    if (inner.charAt(j) == '(') d++;
                    else if (inner.charAt(j) == ')') d--;
                    j++;
                }
                String union = inner.substring(i, j);
                // Check for optional type param after union (e.g. (sn)<n>)
                if (j < inner.length() && inner.charAt(j) == '<') {
                    int k = j + 1;
                    int d2 = 1;
                    while (k < inner.length() && d2 > 0) {
                        if (inner.charAt(k) == '<') d2++;
                        else if (inner.charAt(k) == '>') d2--;
                        k++;
                    }
                    union += inner.substring(j, k);
                    j = k;
                }
                // Collect modifiers +?-
                StringBuilder mod = new StringBuilder();
                while (j < inner.length() && "+-?".indexOf(inner.charAt(j)) >= 0) { mod.append(inner.charAt(j++)); }
                result.add(union + mod);
                i = j;
            } else if (c == 'a' || c == 'o' || c == 'b' || c == 'n' || c == 's' || c == 'l' || c == 'j' || c == 'u') {
                StringBuilder spec = new StringBuilder(String.valueOf(c));
                i++;
                // Array type param: a<n>
                if (i < inner.length() && inner.charAt(i) == '<') {
                    int j = i + 1;
                    int d = 1;
                    while (j < inner.length() && d > 0) {
                        if (inner.charAt(j) == '<') d++;
                        else if (inner.charAt(j) == '>') d--;
                        j++;
                    }
                    spec.append(inner, i, j);
                    i = j;
                }
                // Modifiers +?-
                while (i < inner.length() && "+-?".indexOf(inner.charAt(i)) >= 0) spec.append(inner.charAt(i++));
                result.add(spec.toString());
            } else if (c == '-') {
                // Context-only param (no type)
                result.add("-");
                i++;
            } else {
                // Unknown type char (e.g. 'f' for function — not supported).
                // Skip any trailing angle-bracket type param so that e.g. f<n:n>
                // is consumed entirely and the 'n' inside is not parsed as a spec.
                i++;
                if (i < inner.length() && inner.charAt(i) == '<') {
                    int j = i + 1, d = 1;
                    while (j < inner.length() && d > 0) {
                        if (inner.charAt(j) == '<') d++;
                        else if (inner.charAt(j) == '>') d--;
                        j++;
                    }
                    i = j;
                }
            }
        }
        return result;
    }

    /**
     * Generates a runtime type-check statement for a parameter.
     * Returns null if no check is needed.
     */
    private static String buildTypeCheck(String ptype, String paramVar, int argNum) {
        if (ptype == null || ptype.isEmpty() || ptype.equals("-")) return null;
        // Strip modifiers to get base type
        String base = ptype.replaceAll("[+?\\-]$", "");
        if (base.isEmpty() || base.equals("-")) return null;
        // Optional param ('?'): if the value is provided but doesn't match the type,
        // pass it through unchanged — no T0410. Only focus ('-') still type-checks.
        boolean optional = ptype.endsWith("?");
        boolean focus    = ptype.endsWith("-");
        if (optional) {
            // For scalar types, ? suppresses all type errors (value passes through as-is).
            // For array types we still apply coercion below.
            if (base.equals("n") || base.equals("s") || base.equals("b")) return null;
        }
        String guard = focus ? "if (!" + paramVar + ".isMissingNode()) " : "";
        if (base.equals("n")) {
            return guard + "if (!" + paramVar + ".isMissingNode() && !" + paramVar + ".isNumber()) throw new RuntimeEvaluationException(\"T0410: Argument " + argNum + " of function is not a number\");";
        } else if (base.equals("s")) {
            return guard + "if (!" + paramVar + ".isMissingNode() && !" + paramVar + ".isTextual()) throw new RuntimeEvaluationException(\"T0410: Argument " + argNum + " of function is not a string\");";
        } else if (base.equals("b")) {
            return guard + "if (!" + paramVar + ".isMissingNode() && !" + paramVar + ".isBoolean()) throw new RuntimeEvaluationException(\"T0410: Argument " + argNum + " of function is not a boolean\");";
        } else if (base.startsWith("a<")) {
            // Typed array: coerce scalar → single-element array, then validate elements.
            // A non-array scalar is wrapped first; after wrapping, element types are checked.
            String elemType = base.substring(2, base.length() - 1);
            String elemCheck = getElemTypeCheck(elemType, "__ae");
            String coerce = "if (!" + paramVar + ".isMissingNode() && !" + paramVar + ".isArray()) "
                          + paramVar + " = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(" + paramVar + ");";
            if (elemCheck == null) {
                return coerce;
            }
            return coerce + "\n" +
                   "    if (!" + paramVar + ".isMissingNode()) {\n" +
                   "        for (com.fasterxml.jackson.databind.JsonNode __ae : " + paramVar + ") { if (" + elemCheck + ") throw new RuntimeEvaluationException(\"T0412: Array element is not of expected type\"); }\n" +
                   "    }";
        } else if (base.equals("a")) {
            // Plain array: coerce scalar → single-element array (no element type to check).
            return "if (!" + paramVar + ".isMissingNode() && !" + paramVar + ".isArray()) "
                 + paramVar + " = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(" + paramVar + ");";
        }
        return null;
    }

    private static String getElemTypeCheck(String elemType, String varName) {
        if (elemType.equals("n")) return "!" + varName + ".isNumber()";
        if (elemType.equals("s")) return "!" + varName + ".isTextual()";
        if (elemType.equals("b")) return "!" + varName + ".isBoolean()";
        return null;
    }
}
