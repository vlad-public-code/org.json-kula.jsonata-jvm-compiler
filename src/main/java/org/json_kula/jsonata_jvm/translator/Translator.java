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

        return ClassAssembler.buildClass(pkg, className, bodyExpr, state.helperMethods.toString(),
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
    // Visitor — literals and references
    // =========================================================================

    @Override
    public String visitStringLiteral(StringLiteral n, GenCtx ctx) {
        return "text(" + ClassAssembler.javaString(n.value()) + ")";
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
        if (wrapper != null) return "lambdaNode((__bArg -> " + wrapper + "(__bArg)))";
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
            throw new IllegalStateException("Parent operator % used with no parent context");
        }
        return ctx.parentVars.get(ctx.parentVars.size() - 1);
    }

    @Override
    public String visitPositionBinding(PositionBinding n, GenCtx ctx) {
        // PositionBinding is handled structurally in compilePathSteps.
        throw new IllegalStateException("PositionBinding must appear inside a PathExpr");
    }

    @Override
    public String visitContextBinding(ContextBinding n, GenCtx ctx) {
        // ContextBinding is handled structurally in compilePathSteps.
        throw new IllegalStateException("ContextBinding must appear inside a PathExpr");
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
        // Handle ParentStep as first step: navigate up via parentVars and
        // adjust the parentVars for subsequent steps.
        String expr;
        int startFrom = 1;
        if (firstStep instanceof ParentStep) {
            if (ctx.parentVars.isEmpty()) {
                throw new IllegalStateException("Parent operator % used with no parent context");
            }
            expr = ctx.parentVars.get(ctx.parentVars.size() - 1);
            List<String> newParents = ctx.parentVars.size() > 1
                    ? new ArrayList<>(ctx.parentVars.subList(0, ctx.parentVars.size() - 1))
                    : new ArrayList<>();
            ctx = ctx.withParents(newParents);
        } else {
            expr = stepExpr(firstStep, ctx);
        }
        // Use recursive compile to handle ContextBinding, PositionBinding, ParentStep.
        String result = compilePathSteps(steps, startFrom, expr, ctx);
        return forceArr ? "forceArray(" + result + ")" : result;
    }

    /**
     * Recursively compiles path steps from index {@code from} onwards.
     * Parent context is tracked via {@code ctx.parentVars} (a stack of Java variable
     * names for each preceding path level, innermost last).
     */
    private String compilePathSteps(List<AstNode> steps, int from, String prevExpr, GenCtx ctx) {
        if (from >= steps.size()) return prevExpr;

        AstNode step = steps.get(from);

        if (step instanceof ContextBinding cb) {
            String varName = "$" + cb.varName();
            ctx.state.pushScope();
            ctx.state.addLocalVar(cb.varName());
            try {
                // Check if next step is a Parenthesized — if so, evaluate from ROOT (cross-join)
                String innerExpr;
                int nextFrom;
                if (from + 1 < steps.size() && steps.get(from + 1) instanceof Parenthesized p) {
                    // Cross-join: the parenthesized expression evaluates from document root
                    innerExpr = p.inner().accept(this, ctx.withCtx(ctx.rootVar));
                    nextFrom = from + 2;
                } else {
                    innerExpr = varName;
                    nextFrom = from + 1;
                }
                // Inside the lambda, $varName is the current element and its parent context starts fresh.
                GenCtx innerCtx = ctx.withCtx(varName).withParents(new ArrayList<>(List.of(varName)));
                String restExpr = compilePathSteps(steps, nextFrom, innerExpr, innerCtx);
                return "mapStep(" + prevExpr + ", " + varName + " -> " + restExpr + ")";
            } finally {
                ctx.state.popScope();
            }
        }

        if (step instanceof PositionBinding pb) {
            // eachIndexed passes [element, index] pairs to the lambda.
            // We inline the lambda using a block expression to unpack the pair
            // and then compile the remaining steps.
            String elemVar = "__pe"   + ctx.state.nextId();
            String idxVar  = "$" + pb.varName();
            ctx.state.pushScope();
            ctx.state.addLocalVar(pb.varName());
            try {
                // Push current context as parent for the elem
                List<String> newParents = new ArrayList<>(ctx.parentVars);
                newParents.add(prevExpr);
                GenCtx innerCtx = ctx.withCtx(elemVar).withParents(newParents);
                String restExpr = compilePathSteps(steps, from + 1, elemVar, innerCtx);
                // Inline lambda: (__pair -> { elem = pair.get(0); $var = pair.get(1); return rest; })
                String lambdaBody = "(__pair -> { JsonNode " + elemVar + " = __pair.isArray() ? __pair.get(0) : __pair; "
                        + "JsonNode " + idxVar + " = __pair.isArray() ? __pair.get(1) : number(0L); "
                        + "return " + restExpr + "; })";
                return "eachIndexed(" + prevExpr + ", " + lambdaBody + ")";
            } finally {
                ctx.state.popScope();
            }
        }

        if (step instanceof ParentStep) {
            // Navigate up one level in the parent vars stack.
            List<String> parents = ctx.parentVars;
            if (parents.isEmpty()) {
                throw new IllegalStateException("Parent operator % used with no parent context in path");
            }
            String parentExpr = parents.get(parents.size() - 1);
            List<String> newParents = parents.size() > 1
                    ? new ArrayList<>(parents.subList(0, parents.size() - 1))
                    : new ArrayList<>();
            return compilePathSteps(steps, from + 1, parentExpr, ctx.withParents(newParents));
        }

        // Normal step.
        // If any remaining step contains a ParentStep, generate a mapStep lambda
        // that introduces a named variable for the current element so it becomes
        // accessible as a "parent" reference (via %) in inner expressions.
        if (needsParentTracking(steps, from) && step instanceof FieldRef fr) {
            String elemVar = "__el" + ctx.state.nextId();
            // The previous context (prevExpr) becomes the parent of the new elements.
            // We represent this by adding the CURRENT ctx variable to parentVars
            // (since the outer mapStep's lambda variable holds each parent element).
            // However, prevExpr may be a sequence, not a single element. We use ctx.ctxVar
            // as the "parent" only if we're already inside a mapStep lambda.
            // When generating: mapStep(field(prevExpr, name), elemVar -> rest)
            // inside the lambda, the parent of elemVar is the element from prevExpr.
            // But prevExpr is a sequence; we need a per-element parent variable.
            // Solution: we must wrap the field navigation in a mapStep that also captures the parent.
            // Actually: mapStep(prevExpr, __parent -> mapStep(field(__parent, name), elemVar -> rest))
            // This double-wrapping makes __parent accessible as the parent.
            String parentVar = "__par" + ctx.state.nextId();
            List<String> newParents = new ArrayList<>(ctx.parentVars);
            newParents.add(parentVar);
            String fieldExpr = "field(" + parentVar + ", " + ClassAssembler.javaString(fr.name()) + ")";
            GenCtx innerCtx = ctx.withCtx(elemVar).withParents(newParents);
            String restExpr = compilePathSteps(steps, from + 1, elemVar, innerCtx);
            // Outer mapStep over prevExpr, inner mapStep over the field result
            return "mapStep(" + prevExpr + ", " + parentVar + " -> mapStep(" + fieldExpr + ", " + elemVar + " -> " + restExpr + "))";
        }

        String newExpr = applyStep(prevExpr, step, ctx);
        return compilePathSteps(steps, from + 1, newExpr, ctx);
    }

    /**
     * Returns true if any step from {@code from} onwards (or nested within it)
     * contains a {@link ParentStep} that would require parent variable tracking.
     */
    private static boolean needsParentTracking(List<AstNode> steps, int from) {
        for (int i = from; i < steps.size(); i++) {
            if (ScopeAnalyzer.containsParentStep(steps.get(i))) return true;
        }
        return false;
    }

    @Override
    public String visitForceArray(ForceArray n, GenCtx ctx) {
        return "forceArray(" + n.source().accept(this, ctx) + ")";
    }

    /** Generates an expression for the FIRST step in a path (uses {@code ctx.ctxVar}). */
    private String stepExpr(AstNode step, GenCtx ctx) {
        return switch (step) {
            case FieldRef fr ->
                "field(" + ctx.ctxVar + ", " + ClassAssembler.javaString(fr.name()) + ")";
            // A quoted string as the leading path step is a field reference by name,
            // e.g. "foo".**.bar navigates to the field named "foo" on the context.
            case StringLiteral sl ->
                "field(" + ctx.ctxVar + ", " + ClassAssembler.javaString(sl.value()) + ")";
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
            case FieldRef fr -> {
                yield "field(" + prevExpr + ", " + ClassAssembler.javaString(fr.name()) + ")";
            }
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
                // e.g. Email.[address] — map per element, each result becomes a separate sequence element.
                // The inArrayConstructorStep flag tells the inner array constructor not to wrap
                String tmpCtx  = "__c" + ctx.state.nextId();
                String stepExpr = ac.accept(this, ctx.withCtx(tmpCtx).withInArrayConstructorStep());
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
                yield "mapStep(" + prevExpr + ", " + tmpCtx + " -> " + stepExpr + ")";
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
            case "and" -> "and_("     + left + ", () -> " + right + ")";
            case "or"  -> "or_("      + left + ", () -> " + right + ")";
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
            throw new IllegalStateException("PartialPlaceholder encountered outside PartialApplication");
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

        return "lambdaNode((" + phVar + " -> " + callBody + "))";
    }

    // =========================================================================
    // Visitor — function calls and lambdas
    // =========================================================================

    @Override
    public String visitFunctionCall(FunctionCall n, GenCtx ctx) {
        List<String> args = n.args().stream().map(a -> a.accept(this, ctx)).toList();
        return switch (n.name()) {
            // Type coercion  (all have the '-' context-default modifier)
            case "string"          -> args.size() <= 1
                    ? "fn_string(" + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")"
                    : "fn_string(" + args.get(0) + ", " + args.get(1) + ")";
            case "number"          -> "fn_number("  + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "boolean"         -> "fn_boolean(" + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "not"             -> "fn_not("     + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "type"            -> "fn_type("    + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "exists"          -> "fn_exists("  + ClassAssembler.oneArg(args) + ")";
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
            case "uppercase"       -> "fn_uppercase("      + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "lowercase"       -> "fn_lowercase("      + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "trim"            -> "fn_trim("           + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "length"          -> "fn_length("         + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "substring"       -> args.size() == 2
                    ? "fn_substring(" + args.get(0) + ", " + args.get(1) + ")"
                    : "fn_substring(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            case "substringBefore" -> args.size() == 1
                    ? "fn_substringBefore(" + ctx.ctxVar + ", " + args.get(0) + ")"
                    : "fn_substringBefore(" + args.get(0) + ", " + args.get(1) + ")";
            case "substringAfter"  -> args.size() == 1
                    ? "fn_substringAfter(" + ctx.ctxVar + ", " + args.get(0) + ")"
                    : "fn_substringAfter(" + args.get(0) + ", " + args.get(1) + ")";
            case "contains"        -> args.size() == 1
                    ? "fn_contains(" + ctx.ctxVar + ", " + args.get(0) + ")"
                    : "fn_contains(" + args.get(0) + ", " + args.get(1) + ")";
            case "split"   -> args.size() == 1
                    ? "fn_split(" + ctx.ctxVar + ", " + args.get(0) + ")"
                    : args.size() == 2
                    ? "fn_split(" + args.get(0) + ", " + args.get(1) + ")"
                    : "fn_split(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            case "match"   -> args.size() == 1
                    ? "fn_match(" + ctx.ctxVar + ", " + args.get(0) + ")"
                    : args.size() == 2
                    ? "fn_match(" + args.get(0) + ", " + args.get(1) + ")"
                    : "fn_match(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            case "replace" -> args.size() == 2
                    ? "fn_replace(" + ctx.ctxVar + ", " + args.get(0) + ", " + args.get(1) + ")"
                    : args.size() == 3
                    ? "fn_replace(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")"
                    : "fn_replace(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ", " + args.get(3) + ")";
            case "join"            -> args.size() == 1
                    ? "fn_join(" + args.get(0) + ", MISSING)"
                    : "fn_join(" + args.get(0) + ", " + args.get(1) + ")";
            case "pad"             -> args.size() == 2
                    ? "fn_pad(" + args.get(0) + ", " + args.get(1) + ")"
                    : "fn_pad(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            case "eval"            -> args.size() == 1
                    ? "fn_eval(" + args.get(0) + ")"
                    : "fn_eval(" + args.get(0) + ", " + args.get(1) + ")";
            case "base64encode"    -> "fn_base64encode("    + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "base64decode"    -> "fn_base64decode("    + ClassAssembler.ctxArg(args, ctx.ctxVar) + ")";
            case "encodeUrlComponent" -> "fn_encodeUrlComponent(" + ClassAssembler.oneArg(args) + ")";
            case "decodeUrlComponent" -> "fn_decodeUrlComponent(" + ClassAssembler.oneArg(args) + ")";
            case "encodeUrl"       -> "fn_encodeUrl("       + ClassAssembler.oneArg(args) + ")";
            case "decodeUrl"       -> "fn_decodeUrl("       + ClassAssembler.oneArg(args) + ")";
            // Sequence / array
            case "count"    -> "fn_count("   + ClassAssembler.oneArg(args) + ")";
            case "sum"      -> args.size() > 1 ? "fn_arity_error(\"sum\", 1, " + args.size() + ")"
                    : "fn_sum("     + ClassAssembler.oneArg(args) + ")";
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
            case "map"      -> FunctionCallCodeGen.genHigherOrder(this, "fn_map",    n, args, ctx, 0, 1);
            case "filter"   -> FunctionCallCodeGen.genHigherOrder(this, "fn_filter", n, args, ctx, 0, 1);
            case "each"     -> FunctionCallCodeGen.genEach(this, n, args, ctx);
            case "reduce"   -> FunctionCallCodeGen.genReduce(this, n, args, ctx);
            case "single"   -> FunctionCallCodeGen.genHigherOrder(this, "fn_single", n, args, ctx, 0, 1);
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
                case 1  -> "fn_fromMillis(" + args.get(0) + ")";
                case 2  -> "fn_fromMillis(" + args.get(0) + ", " + args.get(1) + ")";
                default -> "fn_fromMillis(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")";
            };
            case "toMillis" -> args.size() == 1
                    ? "fn_toMillis(" + args.get(0) + ")"
                    : "fn_toMillis(" + args.get(0) + ", " + args.get(1) + ")";
            // Error
            case "error"    -> "fn_error("  + ClassAssembler.oneArg(args) + ")";
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
        return "lambdaNode(" + FunctionCallCodeGen.buildInlineLambda(this, n, ctx) + ")";
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
                return elem.accept(this, ctx);
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
            int from = (int) ((NumberLiteral) re.from()).value();
            int to = (int) ((NumberLiteral) re.to()).value();
            return "rangeFlatten(" + from + ", " + to + ")";
        }
        if (e instanceof ArrayConstructor) {
            return "preserveArray(" + e.accept(this, ctx) + ")";
        }
        return e.accept(this, ctx);
    }

    @Override
    public String visitObjectConstructor(ObjectConstructor n, GenCtx ctx) {
        if (n.pairs().isEmpty()) return "object()";
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
            sb.append("        String __k = fn_string(").append(kExpr).append(").textValue();\n");
            sb.append("        if (!").append(grpVar).append(".containsKey(__k)) {")
              .append(" ").append(grpVar).append(".put(__k, ")
              .append("com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()); }\n");
            sb.append("        ").append(grpVar).append(".get(__k).add(").append(elemVar).append(");\n");
            sb.append("    }\n");
            sb.append("    for (java.util.Map.Entry<String, com.fasterxml.jackson.databind.node.ArrayNode> ")
              .append(entVar).append(" : ").append(grpVar).append(".entrySet()) {\n");
            sb.append("        com.fasterxml.jackson.databind.node.ArrayNode __grpArr = ").append(entVar).append(".getValue();\n");
            sb.append("        JsonNode ").append(elemVar).append(" = __grpArr.size() == 1 ? __grpArr.get(0) : __grpArr;\n");
            sb.append("        JsonNode __v = ").append(vExpr).append(";\n");
            sb.append("        if (!__v.isMissingNode()) __result.set(").append(entVar).append(".getKey(), __v);\n");
            sb.append("    }\n");
        }
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
                + ", " + delExpr + "))";
    }

    // =========================================================================
    // Class assembly (thin delegations to ClassAssembler)
    // =========================================================================

    private static String buildClass(String pkg, String className,
                                      String bodyExpr, String helperMethods,
                                      String sourceExpression) {
        return ClassAssembler.buildClass(pkg, className, bodyExpr, helperMethods, sourceExpression);
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
