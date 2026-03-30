package org.json_kula.jsonata_jvm.translator;

import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
public final class Translator implements AstNode.Visitor<String, Translator.GenCtx> {

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

        return buildClass(pkg, className, bodyExpr, state.helperMethods.toString(),
                sourceExpression);
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
    // Context objects threaded through the visitor
    // =========================================================================

    /** Mutable state shared across all {@link GenCtx} instances in one translation. */
    static final class GenState {
        int counter;
        final StringBuilder helperMethods = new StringBuilder();

        /**
         * Stack of locally-bound variable name sets, one entry per active scope
         * (block or lambda body). Used by {@link #isLocal} to decide whether a
         * {@code VariableRef} should resolve to a Java local variable or to a
         * runtime binding lookup.
         */
        private final Deque<Set<String>> scopeStack = new ArrayDeque<>();

        int nextId() { return counter++; }

        /** Opens a new lexical scope. */
        void pushScope() { scopeStack.push(new HashSet<>()); }

        /** Closes the innermost lexical scope. */
        void popScope() { if (!scopeStack.isEmpty()) scopeStack.pop(); }

        /** Adds {@code name} to the innermost open scope. */
        void addLocalVar(String name) { if (!scopeStack.isEmpty()) scopeStack.peek().add(name); }

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
    }

    /**
     * Immutable per-node context.  A new instance is created whenever the
     * current-context variable name ({@code ctxVar}) changes (e.g. inside a
     * predicate lambda).
     */
    static final class GenCtx {
        final String ctxVar;   // Java variable holding the current context value
        final String rootVar;  // Java variable holding the document root
        final GenState state;

        GenCtx(String ctxVar, String rootVar, GenState state) {
            this.ctxVar  = ctxVar;
            this.rootVar = rootVar;
            this.state   = state;
        }

        GenCtx withCtx(String newCtx) {
            return new GenCtx(newCtx, rootVar, state);
        }
    }

    // =========================================================================
    // Visitor — literals and references
    // =========================================================================

    @Override
    public String visitStringLiteral(StringLiteral n, GenCtx ctx) {
        return "text(" + javaString(n.value()) + ")";
    }

    @Override
    public String visitNumberLiteral(NumberLiteral n, GenCtx ctx) {
        double v = n.value();
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return "number(" + (long) v + "L)";
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
    public String visitContextRef(ContextRef n, GenCtx ctx) {
        return ctx.ctxVar;
    }

    @Override
    public String visitRootRef(RootRef n, GenCtx ctx) {
        return ctx.rootVar;
    }

    @Override
    public String visitVariableRef(VariableRef n, GenCtx ctx) {
        // If the variable was defined by a VariableBinding in an enclosing block or
        // lambda parameter list, it exists as a Java local variable.  Otherwise it
        // must be resolved from the active runtime bindings.
        return ctx.state.isLocal(n.name())
                ? "$" + n.name()
                : "resolveBinding(\"" + n.name() + "\")";
    }

    @Override
    public String visitFieldRef(FieldRef n, GenCtx ctx) {
        return "field(" + ctx.ctxVar + ", " + javaString(n.name()) + ")";
    }

    @Override
    public String visitWildcardStep(WildcardStep n, GenCtx ctx) {
        return "wildcard(" + ctx.ctxVar + ")";
    }

    @Override
    public String visitDescendantStep(DescendantStep n, GenCtx ctx) {
        return "descendant(" + ctx.ctxVar + ")";
    }

    // =========================================================================
    // Visitor — path expressions
    // =========================================================================

    @Override
    public String visitPathExpr(PathExpr n, GenCtx ctx) {
        List<AstNode> steps = n.steps();
        // Check whether the first step is a ForceArray marker.
        // If so, use its inner source as the actual first step and wrap the
        // final result in forceArray() to prevent singleton collapsing.
        AstNode firstStep = steps.get(0);
        boolean forceArr = firstStep instanceof ForceArray;
        if (forceArr) firstStep = ((ForceArray) firstStep).source();
        // First step uses the outer context.
        String expr = stepExpr(firstStep, ctx);
        // Each subsequent step receives the accumulated result as its input.
        for (int i = 1; i < steps.size(); i++) {
            expr = applyStep(expr, steps.get(i), ctx);
        }
        return forceArr ? "forceArray(" + expr + ")" : expr;
    }

    @Override
    public String visitForceArray(ForceArray n, GenCtx ctx) {
        return "forceArray(" + n.source().accept(this, ctx) + ")";
    }

    /** Generates an expression for the FIRST step in a path (uses {@code ctx.ctxVar}). */
    private String stepExpr(AstNode step, GenCtx ctx) {
        return switch (step) {
            case FieldRef fr      -> "field(" + ctx.ctxVar + ", " + javaString(fr.name()) + ")";
            case WildcardStep ws  -> "wildcard(" + ctx.ctxVar + ")";
            case DescendantStep ds-> "descendant(" + ctx.ctxVar + ")";
            case ContextRef cr    -> ctx.ctxVar;
            case RootRef rr       -> ctx.rootVar;
            default               -> step.accept(this, ctx);
        };
    }

    /**
     * Generates an expression that applies {@code step} to {@code prevExpr}
     * (used for steps 2 and onwards in a path).
     */
    private String applyStep(String prevExpr, AstNode step, GenCtx ctx) {
        return switch (step) {
            case FieldRef fr       -> "field(" + prevExpr + ", " + javaString(fr.name()) + ")";
            case WildcardStep ws   -> "wildcard(" + prevExpr + ")";
            case DescendantStep ds -> "descendant(" + prevExpr + ")";
            case ContextRef cr     -> prevExpr;
            case RootRef rr        -> ctx.rootVar;
            case PredicateExpr pe  -> {
                // Range subscript: arr[[from..to]] — select elements by index range
                if (pe.predicate() instanceof RangeExpr re) {
                    String fromExpr = re.from().accept(this, ctx);
                    String toExpr   = re.to().accept(this, ctx);
                    yield "rangeSubscript(" + prevExpr + ", " + fromExpr + ", " + toExpr + ")";
                }
                // source is prevExpr; predicate uses a fresh element variable
                String elemVar = "__el" + ctx.state.nextId();
                String predExpr = pe.predicate().accept(this, ctx.withCtx(elemVar));
                yield "filter(" + prevExpr + ", " + elemVar + " -> " + predExpr + ")";
            }
            case ArraySubscript as -> {
                // Path-step subscript — apply per-element via mapStep so that
                // a.b[n] maps [n] over each element rather than the whole sequence.
                String tmpCtx  = "__c" + ctx.state.nextId();
                String srcExpr = as.source().accept(this, ctx.withCtx(tmpCtx));
                String idxExpr = as.index().accept(this, ctx.withCtx(tmpCtx));
                yield "mapStep(" + prevExpr + ", " + tmpCtx + " -> subscript(" + srcExpr + ", " + idxExpr + "))";
            }
            case ArrayConstructor ac -> {
                // e.g. Email.[address] — map per element, collect without flattening
                // so each constructed array stays as a single element of the result.
                String tmpCtx  = "__c" + ctx.state.nextId();
                String stepExpr = ac.accept(this, ctx.withCtx(tmpCtx));
                yield "mapConstructorStep(" + prevExpr + ", " + tmpCtx + " -> " + stepExpr + ")";
            }
            case ObjectConstructor oc -> {
                // e.g. Phone.{type: number} — map per element, collect without flattening
                // so each constructed object stays as a single element of the result.
                String tmpCtx  = "__c" + ctx.state.nextId();
                String stepExpr = oc.accept(this, ctx.withCtx(tmpCtx));
                yield "mapConstructorStep(" + prevExpr + ", " + tmpCtx + " -> " + stepExpr + ")";
            }
            default -> {
                // For any other step type: rebind __ctx to prevExpr inside a lambda.
                String tmpCtx = "__c" + ctx.state.nextId();
                String stepExpr = step.accept(this, ctx.withCtx(tmpCtx));
                yield "applyStep(" + prevExpr + ", " + tmpCtx + " -> " + stepExpr + ")";
            }
        };
    }

    @Override
    public String visitPredicateExpr(PredicateExpr n, GenCtx ctx) {
        String srcExpr = n.source().accept(this, ctx);
        // Range subscript: arr[[from..to]] — select elements by index range
        if (n.predicate() instanceof RangeExpr re) {
            String fromExpr = re.from().accept(this, ctx);
            String toExpr   = re.to().accept(this, ctx);
            return "rangeSubscript(" + srcExpr + ", " + fromExpr + ", " + toExpr + ")";
        }
        String elemVar  = "__el" + ctx.state.nextId();
        String predExpr = n.predicate().accept(this, ctx.withCtx(elemVar));
        return "filter(" + srcExpr + ", " + elemVar + " -> " + predExpr + ")";
    }

    @Override
    public String visitArraySubscript(ArraySubscript n, GenCtx ctx) {
        // Direct (non-path-step) subscript — applies to the whole array/sequence.
        // Used for arr[n], $[n], (expr)[n], etc.
        String srcExpr = n.source().accept(this, ctx);
        String idxExpr = n.index().accept(this, ctx);
        return "subscript(" + srcExpr + ", " + idxExpr + ")";
    }

    @Override
    public String visitParenthesized(Parenthesized n, GenCtx ctx) {
        // Parentheses are transparent at the expression level; they only affect
        // subscript binding, which is handled at parse time via this wrapper node.
        return n.inner().accept(this, ctx);
    }

    // =========================================================================
    // Visitor — operators
    // =========================================================================

    @Override
    public String visitBinaryOp(BinaryOp n, GenCtx ctx) {
        String left  = n.left().accept(this, ctx);
        String right = n.right().accept(this, ctx);
        return switch (n.op()) {
            case "+"   -> "add("      + left + ", " + right + ")";
            case "-"   -> "subtract(" + left + ", " + right + ")";
            case "*"   -> "multiply(" + left + ", " + right + ")";
            case "/"   -> "divide("   + left + ", " + right + ")";
            case "%"   -> "modulo("   + left + ", " + right + ")";
            case "&"   -> "concat("   + left + ", " + right + ")";
            case "="   -> "eq("       + left + ", " + right + ")";
            case "!="  -> "ne("       + left + ", " + right + ")";
            case "<"   -> "lt("       + left + ", " + right + ")";
            case "<="  -> "le("       + left + ", " + right + ")";
            case ">"   -> "gt("       + left + ", " + right + ")";
            case ">="  -> "ge("       + left + ", " + right + ")";
            case "and" -> "and_("     + left + ", " + right + ")";
            case "or"  -> "or_("      + left + ", " + right + ")";
            case "in"  -> "in_("      + left + ", " + right + ")";
            default    -> throw new IllegalStateException("Unknown operator: " + n.op());
        };
    }

    @Override
    public String visitUnaryMinus(UnaryMinus n, GenCtx ctx) {
        return "negate(" + n.operand().accept(this, ctx) + ")";
    }

    // =========================================================================
    // Visitor — conditional
    // =========================================================================

    @Override
    public String visitConditionalExpr(ConditionalExpr n, GenCtx ctx) {
        String cond = n.condition().accept(this, ctx);
        String then = n.then().accept(this, ctx);
        String otherwise = n.otherwise() != null ? n.otherwise().accept(this, ctx) : "NULL";
        return "(isTruthy(" + cond + ") ? " + then + " : " + otherwise + ")";
    }

    // =========================================================================
    // Visitor — function calls and lambdas
    // =========================================================================

    @Override
    public String visitFunctionCall(FunctionCall n, GenCtx ctx) {
        List<String> args = n.args().stream().map(a -> a.accept(this, ctx)).toList();
        return switch (n.name()) {
            // Type coercion
            case "string"          -> "fn_string("  + oneArg(args) + ")";
            case "number"          -> "fn_number("  + oneArg(args) + ")";
            case "boolean"         -> "fn_boolean(" + oneArg(args) + ")";
            case "not"             -> "fn_not("     + oneArg(args) + ")";
            case "type"            -> "fn_type("    + oneArg(args) + ")";
            case "exists"          -> "fn_exists("  + oneArg(args) + ")";
            // Numeric
            case "floor"           -> "fn_floor("   + oneArg(args) + ")";
            case "ceil"            -> "fn_ceil("    + oneArg(args) + ")";
            case "round"           -> "fn_round("   + oneArg(args) + ")";
            case "abs"             -> "fn_abs("     + oneArg(args) + ")";
            case "sqrt"            -> "fn_sqrt("    + oneArg(args) + ")";
            case "power"           -> "fn_power("   + args.get(0) + ", " + args.get(1) + ")";
            // String
            case "uppercase"       -> "fn_uppercase("      + oneArg(args) + ")";
            case "lowercase"       -> "fn_lowercase("      + oneArg(args) + ")";
            case "trim"            -> "fn_trim("           + oneArg(args) + ")";
            case "length"          -> "fn_length("         + oneArg(args) + ")";
            case "substring"       -> args.size() == 2
                    ? "fn_substring(" + args.get(0) + ", " + args.get(1) + ")"
                    : "fn_substring(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            case "substringBefore" -> "fn_substringBefore(" + args.get(0) + ", " + args.get(1) + ")";
            case "substringAfter"  -> "fn_substringAfter("  + args.get(0) + ", " + args.get(1) + ")";
            case "contains"        -> "fn_contains(" + args.get(0) + ", " + args.get(1) + ")";
            case "split"           -> "fn_split("   + args.get(0) + ", " + args.get(1) + ")";
            case "join"            -> args.size() == 1
                    ? "fn_join(" + args.get(0) + ", NULL)"
                    : "fn_join(" + args.get(0) + ", " + args.get(1) + ")";
            // Sequence / array
            case "count"    -> "fn_count("   + oneArg(args) + ")";
            case "sum"      -> "fn_sum("     + oneArg(args) + ")";
            case "max"      -> "fn_max("     + oneArg(args) + ")";
            case "min"      -> "fn_min("     + oneArg(args) + ")";
            case "average"  -> "fn_average(" + oneArg(args) + ")";
            case "append"   -> "fn_append("  + args.get(0) + ", " + args.get(1) + ")";
            case "reverse"  -> "fn_reverse(" + oneArg(args) + ")";
            case "distinct" -> "fn_distinct(" + oneArg(args) + ")";
            case "flatten"  -> "fn_flatten(" + oneArg(args) + ")";
            case "sort"     -> genSort(n, args, ctx);
            case "map"      -> genHigherOrder("fn_map",    n, args, ctx, 0, 1);
            case "filter"   -> genHigherOrder("fn_filter", n, args, ctx, 0, 1);
            case "each"     -> genHigherOrder("fn_each",   n, args, ctx, 0, 1);
            case "reduce"   -> genReduce(n, args, ctx);
            case "single"   -> genHigherOrder("fn_single", n, args, ctx, 0, 1);
            case "sift"     -> genSift(n, args, ctx);
            // Object
            case "keys"     -> "fn_keys("   + oneArg(args) + ")";
            case "values"   -> "fn_values(" + oneArg(args) + ")";
            case "merge"    -> "fn_merge("  + oneArg(args) + ")";
            // Date/time
            case "now"      -> "fn_now()";
            case "millis"   -> "fn_millis()";
            // Error
            case "error"    -> "fn_error("  + oneArg(args) + ")";
            default         -> genUserFunctionCall(n, args, ctx);
        };
    }

    /** Generates a call to a user-defined variable function: {@code $myFn(args)}. */
    private String genUserFunctionCall(FunctionCall n, List<String> args, GenCtx ctx) {
        String arrayLiteral = args.isEmpty()
                ? "new JsonNode[0]"
                : "new JsonNode[]{" + String.join(", ", args) + "}";
        if (ctx.state.isLocal(n.name())) {
            // Lambda stored in a local variable — call via the runtime lambda wrapper.
            return "fn_apply($" + n.name() + ", " + (args.isEmpty() ? "NULL" : args.get(0)) + ")";
        }
        // Not a local — look up as an externally bound function.
        return "callBoundFunction(\"" + n.name() + "\", " + arrayLiteral + ")";
    }

    /**
     * Generates a sort call.  If the second argument is a Lambda, emit it as
     * an inline Java lambda.  Otherwise, emit a plain sort.
     */
    private String genSort(FunctionCall n, List<String> args, GenCtx ctx) {
        if (args.size() == 1) return "fn_sort(" + args.get(0) + ")";
        // Second arg expected to be a key function
        return genHigherOrder("fn_sort", n, args, ctx, 0, 1);
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
    private String genHigherOrder(String rtMethod,
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
            String lambdaExpr = genUnpackLambda(lam, ctx, 3);
            return indexedMethod + "(" + seqExpr + ", " + lambdaExpr + ")";
        }

        String lambdaExpr;
        if (fnArg instanceof Lambda lam) {
            lambdaExpr = inlineLambda(lam, ctx);
        } else {
            // fn is some expression that evaluates to a lambdaNode — wrap it
            String fnExpr = fnArg.accept(this, ctx);
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
    private String genReduce(FunctionCall n, List<String> args, GenCtx ctx) {
        String arrExpr  = args.get(0);
        AstNode fnArg   = n.args().get(1);
        String initExpr = args.size() > 2 ? args.get(2) : "MISSING";
        String lambdaExpr;
        if (fnArg instanceof Lambda lam && lam.params().size() > 1) {
            // Unpack the [acc, elem] pair into named parameters.
            lambdaExpr = genUnpackLambda(lam, ctx, 2);
        } else {
            lambdaExpr = (fnArg instanceof Lambda lam)
                    ? inlineLambda(lam, ctx) : fnArg.accept(this, ctx);
        }
        return "fn_reduce(" + arrExpr + ", " + lambdaExpr + ", " + initExpr + ")";
    }

    /**
     * Generates {@code fn_sift(obj, fn)} where the lambda receives a
     * {@code [value, key, object]} tuple.  A tuple-unpacking helper is always
     * generated for Lambda arguments so that named parameters like {@code $v}
     * and {@code $k} resolve to the correct tuple slots.
     */
    private String genSift(FunctionCall n, List<String> args, GenCtx ctx) {
        String objExpr = args.get(0);
        AstNode fnArg  = n.args().get(1);
        String lambdaExpr;
        if (fnArg instanceof Lambda lam) {
            lambdaExpr = genUnpackLambda(lam, ctx, 3);
        } else {
            String fnExpr = fnArg.accept(this, ctx);
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
     * @param lam      the lambda whose parameters and body to use
     * @param ctx      code-gen context
     * @param tupleLen number of tuple positions the runtime provides
     * @return a {@code this::methodName} reference that can be used as a
     *         {@link org.json_kula.jsonata_jvm.runtime.JsonataLambda}
     */
    private String genUnpackLambda(Lambda lam, GenCtx ctx, int tupleLen) {
        int id = ctx.state.nextId();
        String methodName = "__unpack" + id;

        ctx.state.pushScope();
        lam.params().forEach(ctx.state::addLocalVar);
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\nprivate JsonNode ").append(methodName)
              .append("(JsonNode __el) throws JsonataEvaluationException {\n");
            for (int i = 0; i < lam.params().size(); i++) {
                if (i < tupleLen) {
                    sb.append("    JsonNode $").append(lam.params().get(i))
                      .append(" = __el.get(").append(i).append(");\n");
                } else {
                    sb.append("    JsonNode $").append(lam.params().get(i)).append(" = MISSING;\n");
                }
            }
            String bodyExpr = lam.body().accept(this, ctx.withCtx("__el"));
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
     * JsonataRuntime#MISSING}.
     */
    private String inlineLambda(Lambda lam, GenCtx ctx) {
        if (lam.params().isEmpty()) {
            String bodyExpr = lam.body().accept(this, ctx);
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
                String bodyExpr = lam.body().accept(this, ctx);
                return "(" + p1 + " -> " + bodyExpr + ")";
            } finally {
                ctx.state.popScope();
            }
        }
        // Multi-param: generate a helper method.
        return genLambdaMethod(lam, ctx);
    }

    @Override
    public String visitLambda(Lambda n, GenCtx ctx) {
        // Standalone lambda (assigned to a variable or used as a chain step).
        // Wrap as a lambdaNode so it can be stored and called later.
        String methodRef = genLambdaMethod(n, ctx);
        return "lambdaNode(" + methodRef + ")";
    }

    /**
     * Generates a private helper method for a lambda and returns a Java method
     * reference expression that can be used as a {@link JsonataLambda}.
     */
    private String genLambdaMethod(Lambda lam, GenCtx ctx) {
        int id = ctx.state.nextId();
        String methodName = "__lambda" + id;

        ctx.state.pushScope();
        lam.params().forEach(ctx.state::addLocalVar);
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\nprivate JsonNode ").append(methodName)
              .append("(JsonNode __el) throws JsonataEvaluationException {\n");
            // Bind each parameter; extras default to MISSING.
            for (int i = 0; i < lam.params().size(); i++) {
                // Single-arg interface: we only receive __el; map subsequent params to MISSING.
                if (i == 0) {
                    sb.append("    JsonNode $").append(lam.params().get(i)).append(" = __el;\n");
                } else {
                    sb.append("    JsonNode $").append(lam.params().get(i)).append(" = MISSING;\n");
                }
            }
            String bodyExpr = lam.body().accept(this, ctx.withCtx("__el"));
            sb.append("    return ").append(bodyExpr).append(";\n");
            sb.append("}\n");
            ctx.state.helperMethods.append(sb);
        } finally {
            ctx.state.popScope();
        }

        return "this::" + methodName;
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
        List<AstNode> exprs = n.expressions();

        // Single expression: skip the helper method overhead.
        if (exprs.size() == 1) return exprs.get(0).accept(this, ctx);

        // Multiple expressions: emit as a private helper method (a "block method").
        int id = ctx.state.nextId();
        String methodName = "__block" + id;

        // Open a scope for variables defined in this block so that VariableRef
        // nodes within the same block resolve to Java local variables rather than
        // runtime binding lookups.
        ctx.state.pushScope();
        for (AstNode expr : exprs) {
            if (expr instanceof VariableBinding vb) ctx.state.addLocalVar(vb.name());
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\nprivate JsonNode ").append(methodName)
              .append("(JsonNode ").append(ctx.rootVar).append(", JsonNode ").append(ctx.ctxVar)
              .append(") throws JsonataEvaluationException {\n");

            for (int i = 0; i < exprs.size() - 1; i++) {
                AstNode expr = exprs.get(i);
                if (expr instanceof VariableBinding vb) {
                    String valExpr = vb.value().accept(this, ctx);
                    sb.append("    JsonNode $").append(vb.name()).append(" = ").append(valExpr).append(";\n");
                } else {
                    // Side-effect expression: evaluate but discard.
                    sb.append("    ").append(expr.accept(this, ctx)).append(";\n");
                }
            }

            AstNode last = exprs.get(exprs.size() - 1);
            if (last instanceof VariableBinding vb) {
                String valExpr = vb.value().accept(this, ctx);
                sb.append("    JsonNode $").append(vb.name()).append(" = ").append(valExpr).append(";\n");
                sb.append("    return $").append(vb.name()).append(";\n");
            } else {
                sb.append("    return ").append(last.accept(this, ctx)).append(";\n");
            }

            sb.append("}\n");
            ctx.state.helperMethods.append(sb);
        } finally {
            ctx.state.popScope();
        }

        return methodName + "(" + ctx.rootVar + ", " + ctx.ctxVar + ")";
    }

    // =========================================================================
    // Visitor — constructors
    // =========================================================================

    @Override
    public String visitArrayConstructor(ArrayConstructor n, GenCtx ctx) {
        if (n.elements().isEmpty()) return "array()";
        List<String> elems = n.elements().stream().map(e -> e.accept(this, ctx)).toList();
        return "array(" + String.join(", ", elems) + ")";
    }

    @Override
    public String visitObjectConstructor(ObjectConstructor n, GenCtx ctx) {
        if (n.pairs().isEmpty()) return "object()";
        List<String> parts = new ArrayList<>();
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
        String srcExpr = n.source().accept(this, ctx);
        // Build a composite key-and-direction sort.
        // For simplicity, chain sorts by each key (last applied = highest priority).
        // A future implementation can produce a multi-key comparator.
        String result = srcExpr;
        for (int i = n.keys().size() - 1; i >= 0; i--) {
            SortKey sk = n.keys().get(i);
            String keyVar  = "__sk" + ctx.state.nextId();
            String keyExpr = sk.key().accept(this, ctx.withCtx(keyVar));
            // Use the runtime sort; wrap result in reverse() if descending.
            String sorted = "fn_sort(" + result + ", " + keyVar + " -> " + keyExpr + ")";
            result = sk.descending() ? "fn_reverse(" + sorted + ")" : sorted;
        }
        return result;
    }

    @Override
    public String visitGroupByExpr(GroupByExpr n, GenCtx ctx) {
        // Generate a reduce that builds an object keyed by the key expression.
        String srcExpr = n.source().accept(this, ctx);
        if (n.pairs().isEmpty()) return srcExpr;

        // For each pair, generate a key lambda and value lambda.
        KeyValuePair first = n.pairs().get(0);
        String elemVar = "__ge" + ctx.state.nextId();
        String keyExpr = first.key().accept(this, ctx.withCtx(elemVar));
        String valExpr = first.value().accept(this, ctx.withCtx(elemVar));

        // Emit as a private helper method that builds the grouped object.
        int id = ctx.state.nextId();
        String methodName = "__groupBy" + id;

        StringBuilder sb = new StringBuilder();
        sb.append("\nprivate JsonNode ").append(methodName)
          .append("(JsonNode __src, JsonNode ").append(ctx.rootVar)
          .append(") throws JsonataEvaluationException {\n");
        sb.append("    com.fasterxml.jackson.databind.node.ObjectNode __result = ")
          .append("com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();\n");
        sb.append("    java.util.List<JsonNode> __items = new java.util.ArrayList<>();\n");
        sb.append("    if (__src.isArray()) { for (JsonNode __it : __src) __items.add(__it); }\n");
        sb.append("    else if (!__src.isMissingNode()) __items.add(__src);\n");
        sb.append("    for (JsonNode ").append(elemVar).append(" : __items) {\n");
        sb.append("        String __key = fn_string(").append(keyExpr).append(").textValue();\n");
        sb.append("        JsonNode __val = ").append(valExpr).append(";\n");
        sb.append("        if (__result.has(__key)) {\n");
        sb.append("            JsonNode __existing = __result.get(__key);\n");
        sb.append("            com.fasterxml.jackson.databind.node.ArrayNode __arr;\n");
        sb.append("            if (__existing.isArray()) {\n");
        sb.append("                __arr = (com.fasterxml.jackson.databind.node.ArrayNode) __existing;\n");
        sb.append("            } else {\n");
        sb.append("                __arr = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(__existing);\n");
        sb.append("                __result.set(__key, __arr);\n");
        sb.append("            }\n");
        // Flatten the new value into the accumulator, matching appendToSequence semantics.
        sb.append("            if (__val.isArray()) { __val.forEach(__arr::add); } else { __arr.add(__val); }\n");
        sb.append("        } else { __result.set(__key, __val); }\n");
        sb.append("    }\n");
        sb.append("    return __result;\n");
        sb.append("}\n");
        ctx.state.helperMethods.append(sb);

        return methodName + "(" + srcExpr + ", " + ctx.rootVar + ")";
    }

    @Override
    public String visitChainExpr(ChainExpr n, GenCtx ctx) {
        // a ~> $f ~> $g  →  fn_apply($g, fn_apply($f, a))
        String expr = n.steps().get(0).accept(this, ctx);
        for (int i = 1; i < n.steps().size(); i++) {
            String fnExpr = n.steps().get(i).accept(this, ctx);
            expr = "fn_apply(" + fnExpr + ", " + expr + ")";
        }
        return expr;
    }

    @Override
    public String visitTransformExpr(TransformExpr n, GenCtx ctx) {
        // |source|pattern|update| — apply update object to matching nodes.
        // Simplified: merge update onto nodes matching pattern.
        String srcExpr     = n.source().accept(this, ctx);
        String patternExpr = n.pattern().accept(this, ctx);
        String updateExpr  = n.update().accept(this, ctx);
        String elemVar     = "__te" + ctx.state.nextId();
        // Filter source by pattern, then merge update onto each match.
        return "fn_map(filter(" + srcExpr + ", " + elemVar + " -> "
                + patternExpr + "), " + elemVar + " -> fn_merge(array(" + elemVar
                + ", " + updateExpr + ")))";
    }

    // =========================================================================
    // Class assembly
    // =========================================================================

    private static String buildClass(String pkg, String className,
                                      String bodyExpr, String helperMethods,
                                      String sourceExpression) {
        String pkgDecl = pkg.isEmpty() ? "" : "package " + pkg + ";\n\n";
        return pkgDecl
                + "import com.fasterxml.jackson.databind.JsonNode;\n"
                + "import com.fasterxml.jackson.databind.ObjectMapper;\n"
                + "import com.fasterxml.jackson.databind.node.MissingNode;\n"
                + "import org.json_kula.jsonata_jvm.JsonataBindings;\n"
                + "import org.json_kula.jsonata_jvm.JsonataBoundFunction;\n"
                + "import org.json_kula.jsonata_jvm.JsonataEvaluationException;\n"
                + "import org.json_kula.jsonata_jvm.JsonataExpression;\n"
                + "import org.json_kula.jsonata_jvm.runtime.JsonataLambda;\n"
                + "import static org.json_kula.jsonata_jvm.runtime.JsonataRuntime.*;\n"
                + "import java.util.concurrent.ConcurrentHashMap;\n"
                + "\n"
                + "public final class " + className + " implements JsonataExpression {\n"
                + "\n"
                + "    private static final ObjectMapper __MAPPER = new ObjectMapper();\n"
                + "    private static final String __SOURCE = " + javaString(sourceExpression) + ";\n"
                + "\n"
                + "    private final ConcurrentHashMap<String, JsonNode> __values = new ConcurrentHashMap<>();\n"
                + "    private final ConcurrentHashMap<String, JsonataBoundFunction> __functions = new ConcurrentHashMap<>();\n"
                + "\n"
                + "    @Override\n"
                + "    public String getSourceJsonata() { return __SOURCE; }\n"
                + "\n"
                + "    @Override\n"
                + "    public void assign(String __name, JsonNode __value) { __values.put(__name, __value); }\n"
                + "\n"
                + "    @Override\n"
                + "    public void registerFunction(String __name, JsonataBoundFunction __fn) { __functions.put(__name, __fn); }\n"
                + "\n"
                + "    @Override\n"
                + "    public JsonNode evaluate(String __json) throws JsonataEvaluationException {\n"
                + "        return evaluate(__json, null);\n"
                + "    }\n"
                + "\n"
                + "    @Override\n"
                + "    public JsonNode evaluate(String __json, JsonataBindings __perEval) throws JsonataEvaluationException {\n"
                + "        beginEvaluation(__values, __functions, __perEval);\n"
                + "        try {\n"
                + "            final JsonNode __root = __MAPPER.readTree(__json);\n"
                + "            if (__root == null) throw new JsonataEvaluationException(\"Invalid JSON\");\n"
                + "            final JsonNode __ctx = __root;\n"
                + "            JsonNode __result = " + bodyExpr + ";\n"
                + "            return __result.isMissingNode() ? NULL : __result;\n"
                + "        } catch (JsonataEvaluationException __e) {\n"
                + "            throw __e;\n"
                + "        } catch (Exception __e) {\n"
                + "            throw new JsonataEvaluationException(__e.getMessage(), __e);\n"
                + "        } finally {\n"
                + "            endEvaluation();\n"
                + "        }\n"
                + "    }\n"
                + helperMethods
                + "}\n";
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /** Wraps {@code s} as a Java string literal with proper escaping. */
    private static String javaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String oneArg(List<String> args) {
        return args.isEmpty() ? "NULL" : args.get(0);
    }
}
