package org.json_kula.jsonata_jvm.translator;

import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a JSONata AST into a complete, compilable Java 21 source file.
 *
 * <p>The generated class:
 * <ul>
 *   <li>is placed in the package and has the class name supplied by the caller;</li>
 *   <li>implements {@code org.json_kula.jsonata_jvm.JsonataExpression};</li>
 *   <li>is stateless and therefore thread-safe;</li>
 *   <li>delegates all JSONata operations to
 *       {@link JsonataRuntime} via a static-wildcard import.</li>
 * </ul>
 *
 * <h2>Code-generation strategy</h2>
 * <p>The visitor produces Java <em>expressions</em> for every AST node.
 * {@link Block} and {@link VariableBinding} nodes — which require statement-level
 * code — are compiled into self-contained private helper methods so that call
 * sites remain single expressions.
 *
 * <h2>Predicate and lambda contexts</h2>
 * <p>Predicates ({@code expr[cond]}) and functional arguments
 * ({@code function($x){body}}) are emitted as Java lambda expressions.
 * Each introduces a fresh, compiler-unique context-variable name to avoid
 * shadowing the enclosing {@code __ctx}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AstNode ast = Optimizer.optimize(Parser.parse("Account.Name"));
 * String src  = Translator.translate(ast, "org.json_kula.jsonata_jvm.generated", "AccountName");
 * // pass src to JsonataExpressionLoader.load(src) to get a live instance
 * }</pre>
 */
public final class Translator implements AstNode.Visitor<String, GenCtx> {

    /**
     * Maps JSONata built-in function names (without the {@code $} prefix) to
     * their {@link JsonataRuntime} method names.  When a {@link VariableRef}
     * with one of these names is encountered outside a local scope (i.e., used
     * as a first-class function value — e.g. in a {@code ~>} chain), the
     * translator wraps it in an inline lambda so it can be stored as a
     * {@code JsonNode} and later invoked via {@link JsonataRuntime#fn_apply}.
     */
    private static final Map<String, String> BUILTIN_LAMBDA_WRAPPERS = Map.ofEntries(
            Map.entry("uppercase",  "fn_uppercase"),
            Map.entry("lowercase",  "fn_lowercase"),
            Map.entry("trim",       "fn_trim"),
            Map.entry("string",     "fn_string"),
            Map.entry("number",     "fn_number"),
            Map.entry("boolean",    "fn_boolean"),
            Map.entry("not",        "fn_not"),
            Map.entry("length",     "fn_length"),
            Map.entry("exists",     "fn_exists"),
            Map.entry("keys",       "fn_keys"),
            Map.entry("values",     "fn_values"),
            Map.entry("count",      "fn_count"),
            Map.entry("sum",        "fn_sum"),
            Map.entry("max",        "fn_max"),
            Map.entry("min",        "fn_min"),
            Map.entry("reverse",    "fn_reverse"),
            Map.entry("flatten",    "fn_flatten"),
            Map.entry("distinct",   "fn_distinct"),
            Map.entry("shuffle",    "fn_shuffle"),
            Map.entry("spread",     "fn_spread"),
            Map.entry("merge",      "fn_merge")
    );

    /**
     * Built-in functions that take 2 arguments, used as first-class values.
     * When referenced as a variable (e.g. {@code $reduce(arr, $append)}), the
     * generated lambda unpacks a packed {@code [arg1, arg2]} array.
     */
    private static final Map<String, String> BUILTIN_BINARY_LAMBDA_WRAPPERS = Map.ofEntries(
            Map.entry("append", "fn_append")
    );

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Translates {@code ast} into a Java 21 source file, embedding
     * {@code sourceExpression} so that {@link JsonataExpression#getSourceJsonata()}
     * returns the original JSONata text.
     *
     * @param ast              the root AST node (should be optimizer-cleaned)
     * @param pkg              Java package name (e.g. {@code "org.json_kula.jsonata_jvm.generated"}),
     *                         or empty string for the default package
     * @param className        simple class name (e.g. {@code "MyExpression"})
     * @param sourceExpression the original JSONata expression string; stored verbatim
     *                         in the generated class and returned by
     *                         {@link JsonataExpression#getSourceJsonata()}
     * @return the complete Java source text
     */
    public static String translate(AstNode ast, String pkg, String className,
                                    String sourceExpression) {
        GenState state  = new GenState();
        GenCtx   ctx    = new GenCtx("__ctx", "__root", state);
        Translator t    = new Translator();

        String bodyExpr = ast.accept(t, ctx);

        // Constants are collected while the body is generated, so they are read afterwards.
        return ClassAssembler.buildClass(pkg, className, bodyExpr, state.helperMethods.toString(),
                state.localDeclarations.toString(), sourceExpression, state.constantDeclarations());
    }

    /**
     * Convenience overload that leaves {@code getSourceJsonata()} returning
     * an empty string. Useful when the original expression text is unavailable.
     *
     * @param ast       the root AST node
     * @param pkg       Java package name, or empty string for the default package
     * @param className simple class name
     * @return the complete Java source text
     */
    public static String translate(AstNode ast, String pkg, String className) {
        return translate(ast, pkg, className, "");
    }

    // =========================================================================
    // Visitor — literals and references
    // =========================================================================

    @Override
    public String visitStringLiteral(StringLiteral n, GenCtx ctx) {
        return ctx.state.constant("text(" + ClassAssembler.javaString(n.value()) + ")");
    }

    @Override
    public String visitNumberLiteral(NumberLiteral n, GenCtx ctx) {
        double v = n.value();
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return ctx.state.constant("number(" + (long) v + "L)");
        }
        return "number(" + v + ")";
    }

    @Override
    public String visitBooleanLiteral(BooleanLiteral n, GenCtx ctx) {
        return "bool(" + n.value() + ")";
    }

    @Override
    public String visitNullLiteral(NullLiteral n, GenCtx ctx) {
        return "NULL";
    }

    @Override
    public String visitRegexLiteral(RegexLiteral n, GenCtx ctx) {
        return "regexNode(" + ClassAssembler.javaString(n.pattern()) + ", " + ClassAssembler.javaString(n.flags()) + ")";
    }

    @Override
    public String visitContextRef(ContextRef n, GenCtx ctx) {
        return ctx.ctxVar;
    }

    @Override
    public String visitRootRef(RootRef n, GenCtx ctx) {
        return ctx.rootVar;
    }

    @Override
    public String visitVariableRef(VariableRef n, GenCtx ctx) {
        if (ctx.state.isLocal(n.name())) {
            // Recursive holder: emit $nameRef[0] so the lambda can self-reference.
            if (ctx.state.holderVars.contains(n.name())) return "$" + n.name() + "Ref[0]";
            // Multi-param alias: emit the id-suffixed Java name.
            String alias = ctx.state.getAlias(n.name());
            return alias != null ? alias : "$" + n.name();
        }
        // Built-in function used as a first-class value (e.g. in a ~> chain):
        // wrap it as an inline lambda so it can be stored / applied later.
        String wrapper = BUILTIN_LAMBDA_WRAPPERS.get(n.name());
        if (wrapper != null) return "lambdaNode((__bArg -> " + wrapper + "(__bArg)), 1)";
        String binaryWrapper = BUILTIN_BINARY_LAMBDA_WRAPPERS.get(n.name());
        if (binaryWrapper != null) return "lambdaNode((__bArg -> " + binaryWrapper
                + "(__bArg.isArray() ? __bArg.get(0) : __bArg,"
                + " __bArg.isArray() && __bArg.size() > 1 ? __bArg.get(1) : MISSING)), 2)";
        return "resolveBinding(\"" + n.name() + "\")";
    }

    @Override
    public String visitFieldRef(FieldRef n, GenCtx ctx) {
        return "field(" + ctx.ctxVar + ", " + ClassAssembler.javaString(n.name()) + ")";
    }

    @Override
    public String visitWildcardStep(WildcardStep n, GenCtx ctx) {
        return "wildcard(" + ctx.ctxVar + ")";
    }

    @Override
    public String visitDescendantStep(DescendantStep n, GenCtx ctx) {
        return "descendant(" + ctx.ctxVar + ")";
    }

    @Override
    public String visitParentStep(ParentStep n, GenCtx ctx) {
        // When visitParentStep is called, parentVars in ctx should contain the
        // immediate parent expression. This is set up by compilePathSteps when
        // generating nested mapStep lambdas with parent tracking.
        if (ctx.parentVars.isEmpty()) {
            throw new RuntimeTranslatorException("S0217", "Parent operator % used with no parent context");
        }
        return ctx.parentVars.get(ctx.parentVars.size() - 1);
    }

    @Override
    public String visitPositionBinding(PositionBinding n, GenCtx ctx) {
        // PositionBinding is handled structurally in compilePathSteps.
        throw new RuntimeTranslatorException(null, "PositionBinding must appear inside a PathExpr");
    }

    @Override
    public String visitContextBinding(ContextBinding n, GenCtx ctx) {
        // ContextBinding is handled structurally in compilePathSteps.
        throw new RuntimeTranslatorException(null, "ContextBinding must appear inside a PathExpr");
    }

    // =========================================================================
    // Visitor — path expressions
    // =========================================================================

    @Override
    public String visitPathExpr(PathExpr n, GenCtx ctx) {
        return PathCodeGen.visitPathExpr(this, n, ctx);
    }


    @Override
    public String visitForceArray(ForceArray n, GenCtx ctx) {
        // If the source path ends with an ArrayConstructor step, mapConstructorStep
        // (preserve mode) will already return the right [[...]] structure — no
        // forceArray wrapper needed.
        if (PathCodeGen.pathEndsWithArrayConstructor(n.source())) {
            return n.source().accept(this, ctx.withArrayConstructorPreserve());
        }
        return "forceArray(" + n.source().accept(this, ctx) + ")";
    }


    @Override
    public String visitPredicateExpr(PredicateExpr n, GenCtx ctx) {
        return PathCodeGen.visitPredicateExpr(this, n, ctx);
    }


    @Override
    public String visitArraySubscript(ArraySubscript n, GenCtx ctx) {
        // Direct (non-path-step) subscript — applies to the whole array/sequence.
        // Used for arr[n], $[n], (expr)[n], etc.
        // When the source chain contains a ForceArray (e.g. $#$pos[][$pos<3]^($)[-1]),
        // the [] force-array semantics must propagate to the subscript result so a
        // single-element result remains an array rather than being unwrapped.
        String srcExpr = n.source().accept(this, ctx);
        String idxExpr = n.index().accept(this, ctx);
        String sub = "subscript(" + srcExpr + ", " + idxExpr + ")";
        return sourceChainContainsForceArray(n.source()) ? "forceArray(" + sub + ")" : sub;
    }

    /** Returns {@code true} if {@code node} or any node in its source chain is a {@link ForceArray}. */
    private static boolean sourceChainContainsForceArray(AstNode node) {
        return switch (node) {
            case ForceArray fa -> true;
            case SortExpr se   -> sourceChainContainsForceArray(se.source());
            case PredicateExpr pe -> sourceChainContainsForceArray(pe.source());
            case PathExpr path -> path.steps().stream().anyMatch(Translator::sourceChainContainsForceArray);
            default -> false;
        };
    }

    @Override
    public String visitParenthesized(Parenthesized n, GenCtx ctx) {
        // Parentheses are transparent at the expression level; they only affect
        // subscript binding, which is handled at parse time via this wrapper node.
        return n.inner().accept(this, ctx);
    }

    // =========================================================================
    // Numeric specialisation — TypedExpr
    // =========================================================================

    /**
     * A Java expression paired with a type flag.
     * When {@code numeric=true} the expression evaluates to a {@code double} where
     * {@link Double#NaN} is the "missing" sentinel.
     * When {@code numeric=false} the expression evaluates to a {@code JsonNode}.
     */
    private record TypedExpr(String code, boolean numeric) {

        /** Returns code that evaluates to a {@code double}, extracting/validating when needed. */
        String asDouble(String op, boolean isLeft) {
            if (numeric) return code;
            return isLeft ? "numValL(" + code + ", \"" + op + "\")"
                          : "numValR(" + code + ", \"" + op + "\")";
        }

        /** Returns code that evaluates to a {@code JsonNode}, wrapping when needed. */
        String asJsonNode() {
            return numeric ? "numWrap(" + code + ")" : code;
        }
    }

    private static boolean isArithOp(String op) {
        return switch (op) { case "+", "-", "*", "/", "%" -> true; default -> false; };
    }

    /**
     * Visits an AST subtree and returns a {@link TypedExpr}.
     *
     * <p>Arithmetic nodes ({@link BinaryOp} with any of the five arithmetic operators,
     * {@link UnaryMinus}, {@link NumberLiteral}) yield {@code numeric=true}: their code
     * produces a {@code double} where {@link Double#NaN} is the missing sentinel.
     * All other nodes fall back to the normal visitor and return {@code numeric=false}.
     *
     * <p>This avoids allocating an intermediate {@code JsonNode} for every sub-expression
     * inside an arithmetic tree. Only the outermost boundary needs one {@code numWrap()} call.
     */
    private TypedExpr exprTyped(AstNode node, GenCtx ctx) {
        return switch (node) {
            case BinaryOp bo when isArithOp(bo.op()) -> {
                TypedExpr l = exprTyped(bo.left(),  ctx);
                TypedExpr r = exprTyped(bo.right(), ctx);
                String lc = l.asDouble(bo.op(), true);
                String rc = r.asDouble(bo.op(), false);
                String code = switch (bo.op()) {
                    case "+" -> "(" + lc + " + " + rc + ")";
                    case "-" -> "(" + lc + " - " + rc + ")";
                    case "*" -> "mul_d(" + lc + ", " + rc + ")";
                    case "/" -> "div_d(" + lc + ", " + rc + ")";
                    case "%" -> "mod_d(" + lc + ", " + rc + ")";
                    default  -> throw new IllegalStateException();
                };
                yield new TypedExpr(code, true);
            }
            case UnaryMinus um -> {
                TypedExpr inner = exprTyped(um.operand(), ctx);
                String code = inner.numeric()
                        ? "(-" + inner.code() + ")"
                        : "neg_dn(" + inner.code() + ")";
                yield new TypedExpr(code, true);
            }
            case NumberLiteral nl -> {
                double v = nl.value();
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    yield new TypedExpr(nl.accept(this, ctx), false);
                }
                String lit = (v == Math.floor(v) && Math.abs(v) < 1e15)
                        ? (long) v + "L"
                        : String.valueOf(v);
                yield new TypedExpr(lit, true);
            }
            default -> new TypedExpr(node.accept(this, ctx), false);
        };
    }

    // =========================================================================
    // Visitor — operators
    // =========================================================================

    @Override
    public String visitBinaryOp(BinaryOp n, GenCtx ctx) {
        GenCtx opCtx = ctx.withTailPosition(false);
        if (isArithOp(n.op())) {
            return exprTyped(n, opCtx).asJsonNode();
        }
        String left  = n.left().accept(this, opCtx);
        String right = n.right().accept(this, opCtx);
        return switch (n.op()) {
            case "&"   -> "concat("   + left + ", " + right + ")";
            case "="   -> "eq("       + left + ", " + right + ")";
            case "!="  -> "ne("       + left + ", " + right + ")";
            case "<"   -> "lt("       + left + ", " + right + ")";
            case "<="  -> "le("       + left + ", " + right + ")";
            case ">"   -> "gt("       + left + ", " + right + ")";
            case ">="  -> "ge("       + left + ", " + right + ")";
            case "and" -> "and_("     + left + ", () -> " + right + ")";
            case "or"  -> "or_("      + left + ", () -> " + right + ")";
            case "in"  -> "in_("      + left + ", " + right + ")";
            default    -> throw new RuntimeTranslatorException(null, "Unknown operator: " + n.op());
        };
    }

    @Override
    public String visitUnaryMinus(UnaryMinus n, GenCtx ctx) {
        return exprTyped(n, ctx.withTailPosition(false)).asJsonNode();
    }

    // =========================================================================
    // Visitor — conditional
    // =========================================================================

    @Override
    public String visitConditionalExpr(ConditionalExpr n, GenCtx ctx) {
        // The condition is never in tail position (its result is used as a boolean).
        String cond = n.condition().accept(this, ctx.withTailPosition(false));
        // Both branches inherit the enclosing tail-position flag: whichever branch
        // is taken, its value is returned directly — that is still a tail position.
        String then = n.then().accept(this, ctx);
        String otherwise = n.otherwise() != null ? n.otherwise().accept(this, ctx) : "MISSING";
        return "(isTruthy(" + cond + ") ? " + then + " : " + otherwise + ")";
    }

    @Override
    public String visitElvisExpr(ElvisExpr n, GenCtx ctx) {
        // left ?: right  →  elvis(left, right)
        // Using the runtime helper avoids evaluating 'left' twice.
        return "elvis(" + n.left().accept(this, ctx) + ", " + n.right().accept(this, ctx) + ")";
    }

    @Override
    public String visitCoalesceExpr(CoalesceExpr n, GenCtx ctx) {
        // left ?? right  →  coalesce(left, right)
        // Using the runtime helper avoids evaluating 'left' twice.
        return "coalesce(" + n.left().accept(this, ctx) + ", " + n.right().accept(this, ctx) + ")";
    }

    @Override
    public String visitPartialPlaceholder(PartialPlaceholder n, GenCtx ctx) {
        if (ctx.state.partialPhVar == null)
            throw new RuntimeTranslatorException("T1008", "The ?? operator should only appear in a function call");
        if (ctx.state.partialPhNeedIdx) {
            return ctx.state.partialPhVar + ".get(" + ctx.state.partialPhIdx++ + ")";
        }
        return ctx.state.partialPhVar;
    }

    @Override
    public String visitPartialApplication(PartialApplication n, GenCtx ctx) {
        long phCount = n.args().stream().filter(a -> a instanceof PartialPlaceholder).count();
        int id = ctx.state.nextId();

        String phVar;
        boolean needIdx;
        if (phCount == 1) {
            phVar = "__ph" + id;
            needIdx = false;
        } else {
            phVar = "__pak" + id;
            needIdx = true;
        }

        // Save / set partial-placeholder state so visitPartialPlaceholder emits the right var.
        String  savedPhVar    = ctx.state.partialPhVar;
        boolean savedNeedIdx  = ctx.state.partialPhNeedIdx;
        int     savedPhIdx    = ctx.state.partialPhIdx;
        ctx.state.partialPhVar    = phVar;
        ctx.state.partialPhNeedIdx = needIdx;
        ctx.state.partialPhIdx    = 0;

        // Delegate to visitFunctionCall with a synthetic FunctionCall node so that the
        // same built-in / user-function routing logic is reused.  Placeholder args will
        // be visited and emit the placeholder variable via visitPartialPlaceholder.
        String callBody = visitFunctionCall(new FunctionCall(n.name(), n.args()), ctx);

        ctx.state.partialPhVar    = savedPhVar;
        ctx.state.partialPhNeedIdx = savedNeedIdx;
        ctx.state.partialPhIdx    = savedPhIdx;

        return "lambdaNode((" + phVar + " -> " + callBody + "), 1)";
    }

    // =========================================================================
    // Visitor — function calls and lambdas
    // =========================================================================

    /**
     * Attempts to emit a fused runtime call that avoids intermediate ArrayNode allocations.
     * Returns {@code null} when the pattern is not recognized or is unsafe to fuse.
     *
     * <p>Fused patterns:
     * <ul>
     *   <li>{@code $count(arr[pred])} → {@code fn_count_filter(arr, elem -> pred)}</li>
     *   <li>{@code $count(arr.field)} → {@code fn_count_field(arr, "field")}</li>
     *   <li>{@code $sum/average/max/min(arr.field)} → {@code fn_sum_field(arr, "field")} etc.</li>
     *   <li>{@code $sum/average/max/min(arr.f1.f2)} → {@code fn_sum_field(arr, "f1", "f2")} etc.</li>
     * </ul>
     */
    /** True for literal nodes, whose value cannot depend on the element being tested. */
    private static boolean isLiteral(AstNode node) {
        return node instanceof StringLiteral || node instanceof NumberLiteral
                || node instanceof BooleanLiteral || node instanceof NullLiteral;
    }

    private String tryFusedCall(FunctionCall n, GenCtx ctx, GenCtx argCtx) {
        AstNode arg0 = n.args().get(0);

        if ("count".equals(n.name())) {
            // $count(arr[pred]) — must not have ForceArray, RangeExpr predicate, or % reference
            if (arg0 instanceof PredicateExpr pe
                    && !(pe.source() instanceof ForceArray)
                    && !(pe.predicate() instanceof RangeExpr)
                    && !ScopeAnalyzer.containsParentStep(pe.predicate())) {
                // $count(seq[field = <literal>]) — collapses to a field read and a comparison.
                if (pe.predicate() instanceof BinaryOp op && "=".equals(op.op())
                        && op.left() instanceof FieldRef field && isLiteral(op.right())) {
                    return "fn_count_field_eq(" + pe.source().accept(this, argCtx) + ", "
                            + ClassAssembler.javaString(field.name()) + ", "
                            + op.right().accept(this, argCtx) + ")";
                }
                String srcExpr = pe.source().accept(this, argCtx);
                String elemVar = "__cf" + ctx.state.nextId();
                String predExpr = pe.predicate().accept(this, argCtx.withCtx(elemVar));
                return "fn_count_filter(" + srcExpr + ", " + elemVar + " -> " + predExpr + ")";
            }
            // $count(arr.field) — 2-step path, avoids materializing the field array
            if (arg0 instanceof PathExpr pe
                    && pe.steps().size() == 2
                    && pe.steps().get(1) instanceof FieldRef fr
                    && !ScopeAnalyzer.containsParentStep(pe)) {
                String srcExpr = pe.steps().get(0).accept(this, argCtx);
                return "fn_count_field(" + srcExpr + ", " + ClassAssembler.javaString(fr.name()) + ")";
            }
        }

        // $sum/average/max/min(src.field) — 2-step path, second step is a plain FieldRef
        String fusedFn = switch (n.name()) {
            case "sum"     -> "fn_sum_field";
            case "average" -> "fn_average_field";
            case "max"     -> "fn_max_field";
            case "min"     -> "fn_min_field";
            default        -> null;
        };
        if (fusedFn != null && arg0 instanceof PathExpr pe && !ScopeAnalyzer.containsParentStep(pe)) {
            if (pe.steps().size() == 2 && pe.steps().get(1) instanceof FieldRef fr) {
                String srcExpr = pe.steps().get(0).accept(this, argCtx);
                return fusedFn + "(" + srcExpr + ", " + ClassAssembler.javaString(fr.name()) + ")";
            }
            // $sum/average/max/min(src.f1.f2) — 3-step path (e.g. $sum($orders.lines.qty))
            if (pe.steps().size() == 3
                    && pe.steps().get(1) instanceof FieldRef fr1
                    && pe.steps().get(2) instanceof FieldRef fr2) {
                String srcExpr = pe.steps().get(0).accept(this, argCtx);
                return fusedFn + "(" + srcExpr + ", " + ClassAssembler.javaString(fr1.name())
                        + ", " + ClassAssembler.javaString(fr2.name()) + ")";
            }
        }

        return null;
    }

    /**
     * Built-ins that take a callback and generate it themselves (see {@link FunctionCallCodeGen}),
     * so the generic argument pass must leave a literal lambda alone.
     */
    private static final java.util.Set<String> CALLBACK_BUILTINS =
            java.util.Set.of("map", "filter", "single", "sift", "each", "sort", "reduce");

    /**
     * Placeholder for a callback argument the built-in generator translates itself. It is never
     * read; were it ever emitted, the generated class would fail to compile rather than
     * misbehave quietly.
     */
    private static final String UNTRANSLATED_CALLBACK = "__callback_translated_by_builtin__";

    @Override
    public String visitFunctionCall(FunctionCall n, GenCtx ctx) {
        // Arguments are never in tail position — only the call site itself may be.
        GenCtx argCtx = ctx.withTailPosition(false);
        // Fusion: detect patterns that can skip intermediate array allocation.
        // Must check before args are compiled because fusion generates different code.
        if (!ctx.state.isLocal(n.name()) && n.args().size() == 1) {
            String fused = tryFusedCall(n, ctx, argCtx);
            if (fused != null) return fused;
        }
        // The higher-order built-ins translate their callback themselves — from the AST, with the
        // arity and tuple shape they need. Translating it here as well would emit a second copy of
        // every helper method the callback needs, and only one copy would ever be called.
        boolean callbackTranslatedByBuiltin =
                !ctx.state.isLocal(n.name()) && CALLBACK_BUILTINS.contains(n.name());
        List<String> args = new ArrayList<>(n.args().size());
        for (AstNode arg : n.args()) {
            args.add(callbackTranslatedByBuiltin && arg instanceof Lambda
                    ? UNTRANSLATED_CALLBACK
                    : arg.accept(this, argCtx));
        }
        // Local variable bindings shadow built-in function names in JSONata.
        // If the name resolves to a local variable, call it as a user function
        // rather than falling through to the built-in dispatch switch below.
        if (ctx.state.isLocal(n.name())) {
            return FunctionCallCodeGen.genUserFunctionCall(this, n, args, ctx);
        }
        return switch (n.name()) {
            // Type coercion  (all have the '-' context-default modifier)
            case "string"          -> args.size() <= 1
                    ? "fn_string(" + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")"
                    : "fn_string(" + args.get(0) + ", " + args.get(1) + ")";
            case "number"          -> args.size() > 1
                    ? "fn_arity_error(\"number\", 1, " + args.size() + ")"
                    : "fn_number(" + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "boolean"         -> args.size() > 1
                    ? "fn_arity_error(\"boolean\", 1, " + args.size() + ")"
                    : "fn_boolean(" + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "not"             -> "fn_not("     + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "type"            -> "fn_type("    + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "exists"          -> args.size() != 1
                    ? "fn_arity_error(\"exists\", 1, " + args.size() + ")"
                    : "fn_exists("  + args.get(0) + ")";
            // Numeric  (floor/ceil/round/abs/sqrt all have the '-' modifier)
            case "floor"         -> "fn_floor("  + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "ceil"          -> "fn_ceil("   + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "round"         -> args.size() <= 1
                    ? "fn_round(" + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")"
                    : "fn_round(" + args.get(0) + ", " + args.get(1) + ")";
            case "abs"           -> "fn_abs("    + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "sqrt"          -> "fn_sqrt("   + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "power"         -> "fn_power("  + args.get(0) + ", " + args.get(1) + ")";
            case "random"        -> "fn_random()";
            case "formatBase"    -> args.size() == 1
                    ? "fn_formatBase(" + args.get(0) + ", MISSING)"
                    : "fn_formatBase(" + args.get(0) + ", " + args.get(1) + ")";
            case "formatNumber"  -> args.size() == 2
                    ? "fn_formatNumber(" + args.get(0) + ", " + args.get(1) + ")"
                    : "fn_formatNumber(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            case "formatInteger" -> "fn_formatInteger(" + args.get(0) + ", " + args.get(1) + ")";
            case "parseInteger"  -> "fn_parseInteger(" + args.get(0) + ", " + args.get(1) + ")";
            // String
            case "uppercase"       -> args.size() > 1
                    ? "fn_arity_error(\"uppercase\", 1, " + args.size() + ")"
                    : "fn_uppercase("      + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "lowercase"       -> args.size() > 1
                    ? "fn_arity_error(\"lowercase\", 1, " + args.size() + ")"
                    : "fn_lowercase("      + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "trim"            -> "fn_trim("           + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "length"          -> args.size() > 1
                    ? "fn_arity_error(\"length\", 1, " + args.size() + ")"
                    : args.isEmpty()
                    ? "fn_length_ctx(" + ctx.ctxVar + ")"
                    : "fn_length(" + args.get(0) + ")";
            case "substring"       -> args.size() > 3
                    ? "fn_arity_error(\"substring\", 3, " + args.size() + ")"
                    : args.size() == 2
                    ? "fn_substring(" + args.get(0) + ", " + args.get(1) + ")"
                    : "fn_substring(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            case "substringBefore" -> args.size() == 1
                    ? "fn_substringBefore_ctx(" + ctx.ctxVar + ", " + args.get(0) + ")"
                    : "fn_substringBefore(" + args.get(0) + ", " + args.get(1) + ")";
            case "substringAfter"  -> args.size() == 1
                    ? "fn_substringAfter_ctx(" + ctx.ctxVar + ", " + args.get(0) + ")"
                    : "fn_substringAfter(" + args.get(0) + ", " + args.get(1) + ")";
            case "contains"        -> args.size() == 1
                    ? "fn_contains(" + ctx.ctxVar + ", " + args.get(0) + ")"
                    : "fn_contains(" + args.get(0) + ", " + args.get(1) + ")";
            case "split"   -> args.size() == 1
                    ? "fn_split(" + args.get(0) + ", MISSING)"
                    : args.size() == 2
                    ? "fn_split(" + args.get(0) + ", " + args.get(1) + ")"
                    : "fn_split(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            case "match"   -> args.size() == 1
                    ? "fn_match(" + ctx.ctxVar + ", " + args.get(0) + ")"
                    : args.size() == 2
                    ? "fn_match(" + args.get(0) + ", " + args.get(1) + ")"
                    : "fn_match(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            case "replace" -> args.size() < 2
                    ? "fn_arity_error(\"replace\", 3, " + args.size() + ")"
                    : args.size() == 2
                    ? "fn_replace(" + ctx.ctxVar + ", " + args.get(0) + ", " + args.get(1) + ")"
                    : args.size() == 3
                    ? "fn_replace(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")"
                    : "fn_replace(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ", " + args.get(3) + ")";
            case "join"            -> args.isEmpty()
                    ? "fn_arity_error(\"join\", 1, 0)"
                    : args.size() == 1
                    ? "fn_join(" + args.get(0) + ", MISSING)"
                    : "fn_join(" + args.get(0) + ", " + args.get(1) + ")";
            case "pad"             -> args.size() == 2
                    ? "fn_pad(" + args.get(0) + ", " + args.get(1) + ")"
                    : "fn_pad(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            case "eval"            -> args.size() == 1
                    ? "fn_eval(" + args.get(0) + ", " + ctx.ctxVar + ")"
                    : "fn_eval(" + args.get(0) + ", " + args.get(1) + ")";
            case "base64encode"    -> "fn_base64encode("    + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "base64decode"    -> "fn_base64decode("    + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "encodeUrlComponent" -> "fn_encodeUrlComponent(" + ClassAssembler.oneArg(args) + ")";
            case "decodeUrlComponent" -> "fn_decodeUrlComponent(" + ClassAssembler.oneArg(args) + ")";
            case "encodeUrl"       -> "fn_encodeUrl("       + ClassAssembler.oneArg(args) + ")";
            case "decodeUrl"       -> "fn_decodeUrl("       + ClassAssembler.oneArg(args) + ")";
            // Sequence / array
            case "count"    -> args.size() > 1 ? "fn_arity_error(\"count\", 1, " + args.size() + ")"
                    : "fn_count("   + ClassAssembler.oneArg(args) + ")";
            case "sum"      -> args.size() > 1 ? "fn_arity_error(\"sum\", 1, " + args.size() + ")"
                    : args.isEmpty() ? "fn_arity_error(\"sum\", 1, 0)"
                    : "fn_sum("     + args.get(0) + ")";
            case "max"      -> args.size() > 1 ? "fn_arity_error(\"max\", 1, " + args.size() + ")"
                    : "fn_max("     + ClassAssembler.oneArg(args) + ")";
            case "min"      -> args.size() > 1 ? "fn_arity_error(\"min\", 1, " + args.size() + ")"
                    : "fn_min("     + ClassAssembler.oneArg(args) + ")";
            case "average"  -> args.size() > 1 ? "fn_arity_error(\"average\", 1, " + args.size() + ")"
                    : "fn_average(" + ClassAssembler.oneArg(args) + ")";
            case "append"   -> "fn_append("  + args.get(0) + ", " + args.get(1) + ")";
            case "reverse"  -> "fn_reverse(" + ClassAssembler.oneArg(args) + ")";
            case "distinct" -> "fn_distinct(" + ClassAssembler.oneArg(args) + ")";
            case "flatten"  -> "fn_flatten(" + ClassAssembler.oneArg(args) + ")";
            case "shuffle"  -> "fn_shuffle(" + ClassAssembler.oneArg(args) + ")";
            case "zip"      -> "fn_zip(" + String.join(", ", args) + ")";
            case "sort"     -> FunctionCallCodeGen.genSort(this, n, args, ctx);
            case "map"      -> n.args().size() < 2
                    ? "fn_arity_error(\"map\", 2, " + n.args().size() + ")"
                    : FunctionCallCodeGen.genHigherOrder(this, "fn_map", n, args, ctx, 0, 1);
            case "filter"   -> FunctionCallCodeGen.genHigherOrder(this, "fn_filter", n, args, ctx, 0, 1);
            case "each"     -> FunctionCallCodeGen.genEach(this, n, args, ctx);
            case "reduce"   -> FunctionCallCodeGen.genReduce(this, n, args, ctx);
            case "single"   -> FunctionCallCodeGen.genSingle(this, n, args, ctx);
            case "sift"     -> FunctionCallCodeGen.genSift(this, n, args, ctx);
            // Object
            case "keys"     -> "fn_keys("   + ClassAssembler.oneArg(args) + ")";
            case "values"   -> "fn_values(" + ClassAssembler.oneArg(args) + ")";
            case "lookup"   -> "fn_lookup(" + args.get(0) + ", " + args.get(1) + ")";
            case "spread"   -> "fn_spread(" + ClassAssembler.oneArg(args) + ")";
            case "merge"    -> "fn_merge("  + ClassAssembler.oneArg(args) + ")";
            case "assert"   -> "fn_assert(" + args.get(0) + ", " + (args.size() > 1 ? args.get(1) : "MISSING") + ")";
            // Date/time
            case "now" -> switch (args.size()) {
                case 0  -> "fn_now()";
                case 1  -> "fn_now(" + args.get(0) + ")";
                default -> "fn_now(" + args.get(0) + ", " + args.get(1) + ")";
            };
            case "millis"    -> "fn_millis()";
            case "fromMillis" -> switch (args.size()) {
                case 0  -> "fn_fromMillis(" + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
                case 1  -> "fn_fromMillis(" + args.get(0) + ")";
                case 2  -> "fn_fromMillis(" + args.get(0) + ", " + args.get(1) + ")";
                default -> "fn_fromMillis(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            };
            case "toMillis" -> args.size() == 1
                    ? "fn_toMillis(" + args.get(0) + ")"
                    : "fn_toMillis(" + args.get(0) + ", " + args.get(1) + ")";
            // Error
            case "error"    -> "fn_error("  + (args.isEmpty() ? "MISSING" : args.get(0)) + ")";
            default         -> FunctionCallCodeGen.genUserFunctionCall(this, n, args, ctx);
        };
    }

    /** Delegates to {@link FunctionCallCodeGen#genUnpackLambda}. */
    String genUnpackLambda(Lambda lam, GenCtx ctx, int tupleLen) {
        return FunctionCallCodeGen.genUnpackLambda(this, lam, ctx, tupleLen);
    }

    /** Delegates to {@link FunctionCallCodeGen#inlineLambda}. */
    String inlineLambda(Lambda lam, GenCtx ctx) {
        return FunctionCallCodeGen.inlineLambda(this, lam, ctx);
    }

    /** Delegates to {@link FunctionCallCodeGen#buildInlineLambda}. */
    String buildInlineLambda(Lambda lam, GenCtx ctx) {
        return FunctionCallCodeGen.buildInlineLambda(this, lam, ctx);
    }

    /** Delegates to {@link FunctionCallCodeGen#genLambdaMethod}. */
    String genLambdaMethod(Lambda lam, GenCtx ctx) {
        return FunctionCallCodeGen.genLambdaMethod(this, lam, ctx);
    }

    @Override
    public String visitLambda(Lambda n, GenCtx ctx) {
        // Standalone lambda (assigned to a variable or used as a chain step).
        // Generate as an inline Java lambda so enclosing block-local variables
        // are captured as closures — fixing the "cannot find symbol" errors that
        // arise when lambdas are emitted as separate private methods.
        // The declared parameter count travels with the value, so a built-in that receives this
        // function as an argument can pass it as many arguments as it takes.
        return "lambdaNode(" + FunctionCallCodeGen.buildInlineLambdaWithSig(this, n, ctx)
                + ", " + n.params().size() + ")";
    }

    @Override
    public String visitLambdaCall(LambdaCall n, GenCtx ctx) {
        return FunctionCallCodeGen.genLambdaCall(this, n, ctx);
    }

    // =========================================================================
    // Visitor — variable binding and blocks
    // =========================================================================

    @Override
    public String visitVariableBinding(VariableBinding n, GenCtx ctx) {
        // Standalone binding: evaluate and return the value (binding is ephemeral).
        return n.value().accept(this, ctx);
    }

    @Override
    public String visitBlock(Block n, GenCtx ctx) {
        return BlockCodeGen.visitBlock(this, n, ctx);
    }

    // =========================================================================
    // Visitor — constructors
    // =========================================================================

    @Override
    public String visitArrayConstructor(ArrayConstructor n, GenCtx ctx) {
        if (n.elements().isEmpty()) return "array()";
        if (n.elements().size() == 1) {
            AstNode elem = n.elements().get(0);
            if (ctx.inArrayConstructorStep && !(elem instanceof ArrayConstructor)) {
                return "forceArray(" + elem.accept(this, ctx) + ")";
            }
            String elemCode = elem.accept(this, ctx);
            if (elem instanceof ArrayConstructor) {
                // Nested array constructor — wrap the inner result with preserveArray
                // so the outer arrayOf keeps it as a single element
                return "arrayOf(preserveArray(" + elemCode + "))";
            }
            return "arrayOf(" + elemCode + ")";
        }
        List<String> elems = n.elements().stream()
            .map(e -> wrapArrayElement(e, ctx))
            .toList();
        return "arrayOf(" + String.join(", ", elems) + ")";
    }

    private String wrapArrayElement(AstNode e, GenCtx ctx) {
        if (e instanceof RangeExpr re) {
            if (re.from() instanceof NumberLiteral nFrom && re.to() instanceof NumberLiteral nTo) {
                // Constant range: use the fast RangeHolder path
                return "rangeFlatten(" + (int) nFrom.value() + ", " + (int) nTo.value() + ")";
            }
            // Non-constant range: generate runtime range() call; arrayOf flattens the ArrayNode result
            return re.accept(this, ctx);
        }
        if (e instanceof ArrayConstructor) {
            return "preserveArray(" + e.accept(this, ctx) + ")";
        }
        return e.accept(this, ctx);
    }

    @Override
    public String visitObjectConstructor(ObjectConstructor n, GenCtx ctx) {
        if (n.pairs().isEmpty()) return "object()";
        // Literal keys are the common case and need none of the per-key checking the general
        // constructor does, so they get a form that takes the names as plain strings.
        boolean allKeysLiteral = n.pairs().stream().allMatch(p -> p.key() instanceof StringLiteral);
        if (allKeysLiteral) {
            List<String> keys = new java.util.ArrayList<>();
            List<String> values = new java.util.ArrayList<>();
            for (KeyValuePair p : n.pairs()) {
                keys.add(ClassAssembler.javaString(((StringLiteral) p.key()).value()));
                values.add(p.value().accept(this, ctx));
            }
            String keyField = ctx.state.keyArray("{" + String.join(", ", keys) + "}");
            return "objectOf(" + keyField + ", new JsonNode[]{" + String.join(", ", values) + "})";
        }
        List<String> parts = new java.util.ArrayList<>();
        for (KeyValuePair p : n.pairs()) {
            parts.add(p.key().accept(this, ctx));
            parts.add(p.value().accept(this, ctx));
        }
        return "object(" + String.join(", ", parts) + ")";
    }

    // =========================================================================
    // Visitor — range, sort, group-by, chain, transform
    // =========================================================================

    @Override
    public String visitRangeExpr(RangeExpr n, GenCtx ctx) {
        return "range(" + n.from().accept(this, ctx) + ", " + n.to().accept(this, ctx) + ")";
    }

    @Override
    public String visitSortExpr(SortExpr n, GenCtx ctx) {
        // When sort keys reference % (parent), we need parent-tracked pair/triple navigation.
        boolean hasParentRef = n.keys().stream().anyMatch(k -> ScopeAnalyzer.containsParentStep(k.key()));
        if (hasParentRef && n.source() instanceof PathExpr sourcePath) {
            return compileSortWithParent(n, sourcePath, ctx);
        }
        String srcExpr = n.source().accept(this, ctx);
        String result = srcExpr;
        for (int i = n.keys().size() - 1; i >= 0; i--) {
            SortKey sk = n.keys().get(i);
            String keyVar  = "__sk" + ctx.state.nextId();
            String keyExpr = sk.key().accept(this, ctx.withCtx(keyVar));
            String sorted = "fn_sort(" + result + ", " + keyVar + " -> " + keyExpr + ")";
            result = sk.descending() ? "fn_reverse(" + sorted + ")" : sorted;
        }
        return "unwrap(" + result + ")";
    }

    /** Maximum leading ParentStep depth across all sort keys. */
    private static int maxParentDepth(List<SortKey> keys) {
        int max = 0;
        for (SortKey sk : keys) {
            if (sk.key() instanceof PathExpr pe) {
                int d = 0;
                for (AstNode step : pe.steps()) {
                    if (step instanceof ParentStep) d++;
                    else break;
                }
                max = Math.max(max, d);
            }
        }
        return max;
    }

    /**
     * Compiles a sort expression where sort keys reference the parent operator (%).
     * Navigates the source path keeping parent context by collecting element/parent
     * pairs (depth=1) or triples (depth=2), sorts those, then extracts the elements.
     */
    private String compileSortWithParent(SortExpr n, PathExpr sourcePath, GenCtx ctx) {
        int depth = maxParentDepth(n.keys());
        List<AstNode> steps = sourcePath.steps();
        if (depth < 1 || steps.size() < depth + 1) {
            // Fallback: standard sort (may fail at runtime for %)
            String srcExpr = sourcePath.accept(this, ctx);
            String result = srcExpr;
            for (int i = n.keys().size() - 1; i >= 0; i--) {
                SortKey sk = n.keys().get(i);
                String keyVar  = "__sk" + ctx.state.nextId();
                String keyExpr = sk.key().accept(this, ctx.withCtx(keyVar));
                String sorted = "fn_sort(" + result + ", " + keyVar + " -> " + keyExpr + ")";
                result = sk.descending() ? "fn_reverse(" + sorted + ")" : sorted;
            }
            return "unwrap(" + result + ")";
        }

        String tupleExpr;
        List<String> parentRefExprs; // how to reference parents from a tuple var

        if (depth == 1) {
            // Navigate all-but-last steps → parents; last step → elements
            String prefixExpr;
            if (steps.size() == 1) {
                prefixExpr = ctx.ctxVar;
            } else {
                PathExpr prefix = new PathExpr(new ArrayList<>(steps.subList(0, steps.size() - 1)));
                prefixExpr = prefix.accept(this, ctx);
            }
            AstNode elemStep = steps.get(steps.size() - 1);
            String parVar = "__par" + ctx.state.nextId();
            String elemExpr = PathCodeGen.applyStep(this, parVar, elemStep, ctx.withCtx(parVar));
            tupleExpr = "fn_collect_pairs(" + prefixExpr + ", " + parVar + " -> " + elemExpr + ")";
            // tuple.get(0) = element, tuple.get(1) = parent (%)
            parentRefExprs = List.of("TUPLE.get(1)");
        } else { // depth == 2
            // Navigate all-but-last-2 steps → grandparents; step[-2] → parents; step[-1] → elements
            String gpExpr;
            if (steps.size() == 2) {
                gpExpr = ctx.ctxVar;
            } else {
                PathExpr gp = new PathExpr(new ArrayList<>(steps.subList(0, steps.size() - 2)));
                gpExpr = gp.accept(this, ctx);
            }
            AstNode parentStep = steps.get(steps.size() - 2);
            AstNode elemStep   = steps.get(steps.size() - 1);
            String gpVar  = "__gp" + ctx.state.nextId();
            String parVar = "__par" + ctx.state.nextId();
            String parentExpr = PathCodeGen.applyStep(this, gpVar, parentStep, ctx.withCtx(gpVar));
            String elemExpr   = PathCodeGen.applyStep(this, parVar, elemStep, ctx.withCtx(parVar));
            tupleExpr = "fn_collect_triples(" + gpExpr + ", " + gpVar + " -> " + parentExpr
                    + ", " + parVar + " -> " + elemExpr + ")";
            // tuple.get(0) = element, tuple.get(1) = parent (%), tuple.get(2) = grandparent (%%)
            parentRefExprs = List.of("TUPLE.get(2)", "TUPLE.get(1)");
        }

        // Sort the tuples by each key (chained, lowest priority first)
        String result = tupleExpr;
        for (int i = n.keys().size() - 1; i >= 0; i--) {
            SortKey sk = n.keys().get(i);
            String tupleVar = "__tk" + ctx.state.nextId();
            // Build parentVars using tupleVar as the base: replace "TUPLE" placeholder
            List<String> pVars = new ArrayList<>();
            for (String ref : parentRefExprs) pVars.add(ref.replace("TUPLE", tupleVar));
            String keyExpr = sk.key().accept(this,
                    ctx.withCtx(tupleVar + ".get(0)").withParents(pVars));
            String sorted = "fn_sort(" + result + ", " + tupleVar + " -> " + keyExpr + ")";
            result = sk.descending() ? "fn_reverse(" + sorted + ")" : sorted;
        }
        // Extract elements from sorted tuples
        String extVar = "__tex" + ctx.state.nextId();
        return "unwrap(fn_map(" + result + ", " + extVar + " -> " + extVar + ".get(0)))";
    }

    @Override
    public String visitGroupByExpr(GroupByExpr n, GenCtx ctx) {
        // Generate a reduce that builds an object keyed by the key expression.
        String srcExpr = n.source().accept(this, ctx);
        if (n.pairs().isEmpty()) return srcExpr;

        // Find outer-scope locals referenced in the pair key/value expressions so they
        // can be passed as extra parameters to the generated helper method — this is
        // what makes closures like $AccName() work inside a group-by constructor.
        java.util.Set<String> outerLocals = new java.util.LinkedHashSet<>();
        for (java.util.Set<String> scope : ctx.state.scopeStack) outerLocals.addAll(scope);
        java.util.Set<String> usedLocals = new java.util.LinkedHashSet<>();
        java.util.Set<String> emptyBound = new java.util.HashSet<>();
        for (KeyValuePair p : n.pairs()) {
            ScopeAnalyzer.collectFreeVarsInto(p.key(), usedLocals, emptyBound);
            ScopeAnalyzer.collectFreeVarsInto(p.value(), usedLocals, emptyBound);
        }
        usedLocals.retainAll(outerLocals);
        java.util.List<String> capturedVars = new java.util.ArrayList<>(usedLocals);
        // When primaryContextVar is set (@$c[pred]{GroupBy} pattern), exclude it from
        // captured parameters — it will be rebound to the group element inside the helper.
        if (ctx.primaryContextVar != null) {
            String stripped = ctx.primaryContextVar.startsWith("$")
                    ? ctx.primaryContextVar.substring(1) : ctx.primaryContextVar;
            capturedVars.remove(stripped);
        }

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

        // Generate key/value expressions for every pair.
        // Both use the same elemVar as the context: keyExpr uses it as an individual element
        // (to determine group membership), valExpr uses it as the group sequence (the aggregated
        // elements for that group) — this matches JSONata's group-by semantics where the value
        // expression is evaluated once per group on the whole collected sequence.
        String elemVar = "__ge" + ctx.state.nextId();
        GenCtx elemCtx = ctx.withCtx(elemVar);
        java.util.List<String[]> pairExprs = new java.util.ArrayList<>();
        for (KeyValuePair p : n.pairs()) {
            pairExprs.add(new String[]{
                p.key().accept(this, elemCtx),
                p.value().accept(this, elemCtx)
            });
        }

        // Emit as a private helper method that builds the grouped object.
        int id = ctx.state.nextId();
        String methodName = "__groupBy" + id;

        StringBuilder sb = new StringBuilder();
        sb.append("\nprivate JsonNode ").append(methodName)
          .append("(JsonNode __src, JsonNode ").append(ctx.rootVar)
          .append(extraParamDecls)
          .append(") throws RuntimeEvaluationException {\n");
        sb.append("    com.fasterxml.jackson.databind.node.ObjectNode __result = ")
          .append("com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();\n");
        sb.append("    java.util.List<JsonNode> __items = new java.util.ArrayList<>();\n");
        sb.append("    if (__src.isArray()) { for (JsonNode __it : __src) __items.add(__it); }\n");
        sb.append("    else if (!__src.isMissingNode()) __items.add(__src);\n");
        // For each pair: group elements by key, then evaluate value once per group.
        for (int pi = 0; pi < pairExprs.size(); pi++) {
            String kExpr = pairExprs.get(pi)[0];
            String vExpr = pairExprs.get(pi)[1];
            String grpVar = "__grp" + pi;
            String entVar = "__ent" + pi;
            sb.append("    java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode> ").append(grpVar)
              .append(" = new java.util.LinkedHashMap<>();\n");
            sb.append("    for (JsonNode ").append(elemVar).append(" : __items) {\n");
            sb.append("        JsonNode __kNode").append(pi).append(" = ").append(kExpr).append(";\n");
            sb.append("        if (!__kNode").append(pi).append(".isMissingNode() && !__kNode").append(pi).append(".isTextual()) ");
            sb.append("throw new RuntimeEvaluationException(\"T1003\", \"The key of an object constructor must evaluate to a string\");\n");
            sb.append("        if (__kNode").append(pi).append(".isMissingNode()) continue;\n");
            sb.append("        String __k = __kNode").append(pi).append(".textValue();\n");
            sb.append("        if (!").append(grpVar).append(".containsKey(__k)) {")
              .append(" ").append(grpVar).append(".put(__k, ")
              .append("com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()); }\n");
            sb.append("        ").append(grpVar).append(".get(__k).add(").append(elemVar).append(");\n");
            sb.append("    }\n");
            sb.append("    for (java.util.Map.Entry<String, com.fasterxml.jackson.databind.node.ArrayNode> ")
              .append(entVar).append(" : ").append(grpVar).append(".entrySet()) {\n");
            sb.append("        com.fasterxml.jackson.databind.node.ArrayNode __grpArr = ").append(entVar).append(".getValue();\n");
            sb.append("        JsonNode ").append(elemVar).append(" = __grpArr.size() == 1 ? __grpArr.get(0) : __grpArr;\n");
            // When primaryContextVar is set, rebind it to the group element so that
            // aggregation functions in the value expression see the whole group.
            if (ctx.primaryContextVar != null) {
                sb.append("        JsonNode ").append(ctx.primaryContextVar)
                  .append(" = ").append(elemVar).append(";\n");
            }
            sb.append("        JsonNode __v = ").append(vExpr).append(";\n");
            sb.append("        if (!__v.isMissingNode()) {\n");
            sb.append("            if (__result.has(").append(entVar).append(".getKey())) ");
            sb.append("throw new RuntimeEvaluationException(\"D1009\", \"Multiple key definitions evaluate to same key: '\" + ").append(entVar).append(".getKey() + \"'\");\n");
            sb.append("            __result.set(").append(entVar).append(".getKey(), __v);\n");
            sb.append("        }\n");
            sb.append("    }\n");
        }
        // Return MISSING only when the source itself was absent; an empty but valid
        // source (e.g. an empty array) should yield an empty object {}.
        sb.append("    if (__src.isMissingNode()) return MISSING;\n");
        sb.append("    return __result;\n");
        sb.append("}\n");
        ctx.state.helperMethods.append(sb);

        return methodName + "(" + srcExpr + ", " + ctx.rootVar + extraCallArgs + ")";
    }

    @Override
    public String visitChainExpr(ChainExpr n, GenCtx ctx) {
        // a ~> $f ~> $g  →  fn_pipe(fn_pipe(a, $f), $g)
        // fn_pipe handles both value-piping ($f(a)) and function composition
        // ($f ~> $g when $f is itself a lambda produces a composed lambda).
        String expr = n.steps().get(0).accept(this, ctx);
        for (int i = 1; i < n.steps().size(); i++) {
            AstNode step = n.steps().get(i);
            String fnExpr = chainStepToLambda(step, ctx);
            expr = "fn_pipe(" + expr + ", " + fnExpr + ")";
        }
        return expr;
    }

    /**
     * Converts a chain step to a lambda token suitable for {@code fn_pipe}.
     *
     * <ul>
     *   <li>A {@link FunctionCall} is converted to a partial application where the
     *       piped value is prepended as the first argument (the {@code ?} placeholder).
     *       {@code $trim()} → {@code lambdaNode(__ph -> fn_trim(__ph))};
     *       {@code $substringAfter("@")} → {@code lambdaNode(__ph -> fn_substringAfter(__ph, "@"))}.</li>
     *   <li>All other nodes (VariableRef, TransformLambda, Lambda, …) are visited normally —
     *       they already produce lambda tokens.</li>
     * </ul>
     */
    private String chainStepToLambda(AstNode step, GenCtx ctx) {
        if (step instanceof FunctionCall fc) {
            // Prepend a PartialPlaceholder so the piped value becomes the first argument.
            java.util.List<AstNode> argsWithPipe = new java.util.ArrayList<>();
            argsWithPipe.add(new PartialPlaceholder());
            argsWithPipe.addAll(fc.args());
            return visitPartialApplication(new PartialApplication(fc.name(), argsWithPipe), ctx);
        }
        // ForceArray wrapping a FunctionCall: e.g. $data ~> $map($square)[]
        // The [] is applied to the result of the piped call, not to $map($square) alone.
        if (step instanceof ForceArray fa && fa.source() instanceof FunctionCall fc) {
            java.util.List<AstNode> argsWithPipe = new java.util.ArrayList<>();
            argsWithPipe.add(new PartialPlaceholder());
            argsWithPipe.addAll(fc.args());
            String innerPartial = visitPartialApplication(new PartialApplication(fc.name(), argsWithPipe), ctx);
            int id = ctx.state.nextId();
            String argVar = "__cfaArg" + id;
            return "lambdaNode(" + argVar + " -> forceArray(fn_apply(" + innerPartial + ", " + argVar + ")), 1)";
        }
        return step.accept(this, ctx);
    }

    @Override
    public String visitTransformExpr(TransformExpr n, GenCtx ctx) {
        String srcExpr  = n.source().accept(this, ctx);
        String locVar   = "__tl" + ctx.state.nextId();
        String locExpr  = n.pattern().accept(this, ctx.withCtx(locVar));
        String updVar   = "__tu" + ctx.state.nextId();
        String updExpr  = n.update().accept(this, ctx.withCtx(updVar));
        String delExpr  = n.delete() != null ? n.delete().accept(this, ctx) : "MISSING";
        return "fn_transform(" + srcExpr
                + ", " + locVar + " -> " + locExpr
                + ", " + updVar + " -> " + updExpr
                + ", " + delExpr + ")";
    }

    @Override
    public String visitTransformLambda(TransformLambda n, GenCtx ctx) {
        // Standalone |pattern|update[,delete]| — emit as a lambdaNode for use with ~>.
        String srcVar  = "__ts" + ctx.state.nextId();
        String locVar  = "__tl" + ctx.state.nextId();
        String locExpr = n.pattern().accept(this, ctx.withCtx(locVar));
        String updVar  = "__tu" + ctx.state.nextId();
        String updExpr = n.update().accept(this, ctx.withCtx(updVar));
        String delExpr = n.delete() != null ? n.delete().accept(this, ctx) : "MISSING";
        return "lambdaNode(" + srcVar + " -> fn_transform(" + srcVar
                + ", " + locVar + " -> " + locExpr
                + ", " + updVar + " -> " + updExpr
                + ", " + delExpr + "), 1)";
    }

    // =========================================================================
    // Class assembly (thin delegations to ClassAssembler)
    // =========================================================================

    private static String buildClass(String pkg, String className,
                                      String bodyExpr, String helperMethods,
                                      String localDeclarations, String sourceExpression) {
        return ClassAssembler.buildClass(pkg, className, bodyExpr, helperMethods, localDeclarations, sourceExpression);
    }

    // =========================================================================
    // Utilities (thin delegations to ClassAssembler)
    // =========================================================================

    /** Wraps {@code s} as a Java string literal with proper escaping. */
    private static String javaString(String s) {
        return ClassAssembler.javaString(s);
    }

    private static String oneArg(List<String> args) {
        return ClassAssembler.oneArg(args);
    }

    // =========================================================================
    // Scope analysis (thin delegations to ScopeAnalyzer)
    // =========================================================================

    private static java.util.Set<String> computeHolderNeeded(List<AstNode> exprs,
                                                               java.util.Set<String> blockLocalNames) {
        return ScopeAnalyzer.computeHolderNeeded(exprs, blockLocalNames);
    }

    private static List<String> collectFreeOuterVars(List<AstNode> exprs,
                                                      java.util.Set<String> blockLocals,
                                                      GenState state) {
        return ScopeAnalyzer.collectFreeOuterVars(exprs, blockLocals, state);
    }

    private static void collectFreeVarsInto(AstNode node,
                                             java.util.Set<String> used,
                                             java.util.Set<String> bound) {
        ScopeAnalyzer.collectFreeVarsInto(node, used, bound);
    }

    private static boolean containsVarRef(AstNode node, String name) {
        return ScopeAnalyzer.containsVarRef(node, name);
    }
}
