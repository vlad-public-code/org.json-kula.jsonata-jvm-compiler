package org.json_kula.jsonata_jvm.parser.ast;

import java.util.List;

/**
 * Sealed interface representing every node type in a parsed JSONata AST.
 *
 * <p>All node types are nested records/interfaces so that the full type
 * hierarchy lives in one file and callers can use exhaustive {@code switch}
 * expressions over the permitted subtypes.
 *
 * <p>Visitor support is provided via the two-parameter {@link Visitor}{@code <R,C>}
 * interface; the context parameter {@code C} threads arbitrary per-traversal
 * state through the visit calls without requiring instance variables.
 */
public sealed interface AstNode permits
        AstNode.StringLiteral,
        AstNode.NumberLiteral,
        AstNode.BooleanLiteral,
        AstNode.NullLiteral,
        AstNode.ContextRef,
        AstNode.RootRef,
        AstNode.VariableRef,
        AstNode.FieldRef,
        AstNode.WildcardStep,
        AstNode.DescendantStep,
        AstNode.ArrayConstructor,
        AstNode.ObjectConstructor,
        AstNode.PathExpr,
        AstNode.PredicateExpr,
        AstNode.ArraySubscript,
        AstNode.Parenthesized,
        AstNode.BinaryOp,
        AstNode.UnaryMinus,
        AstNode.FunctionCall,
        AstNode.Lambda,
        AstNode.VariableBinding,
        AstNode.ConditionalExpr,
        AstNode.Block,
        AstNode.RangeExpr,
        AstNode.SortExpr,
        AstNode.GroupByExpr,
        AstNode.ChainExpr,
        AstNode.TransformExpr,
        AstNode.ForceArray {

    // =========================================================================
    // Literals
    // =========================================================================

    /** A string literal, e.g. {@code "hello"} or {@code 'hello'}. */
    record StringLiteral(String value) implements AstNode {}

    /** A numeric literal, e.g. {@code 42}, {@code 3.14}, {@code 1e10}. */
    record NumberLiteral(double value) implements AstNode {}

    /** {@code true} or {@code false}. */
    record BooleanLiteral(boolean value) implements AstNode {}

    /** The literal {@code null}. */
    record NullLiteral() implements AstNode {}

    // =========================================================================
    // References
    // =========================================================================

    /**
     * The bare {@code $} token — refers to the current context value
     * (or marks a lambda parameter position).
     */
    record ContextRef() implements AstNode {}

    /**
     * The {@code $$} token — refers to the root of the input document.
     */
    record RootRef() implements AstNode {}

    /**
     * A variable reference such as {@code $name}.
     *
     * @param name the variable name, without the leading {@code $}
     */
    record VariableRef(String name) implements AstNode {}

    // =========================================================================
    // Path steps
    // =========================================================================

    /**
     * A bare field name in a path, e.g. {@code Account} in {@code Account.Name}.
     *
     * @param name the field name (may include any Unicode characters if backtick-quoted)
     */
    record FieldRef(String name) implements AstNode {}

    /** The {@code *} wildcard — matches all fields of an object. */
    record WildcardStep() implements AstNode {}

    /** The {@code **} recursive-descent wildcard — matches all descendants. */
    record DescendantStep() implements AstNode {}

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Array constructor: {@code [expr, expr, ...]}.
     *
     * @param elements the element expressions (may be empty)
     */
    record ArrayConstructor(List<AstNode> elements) implements AstNode {}

    /**
     * A single key-value pair inside an object constructor.
     *
     * @param key   expression that produces the key string
     * @param value expression that produces the value
     */
    record KeyValuePair(AstNode key, AstNode value) {}

    /**
     * Object constructor: {@code { key: value, ... }}.
     *
     * @param pairs the key-value pairs (may be empty)
     */
    record ObjectConstructor(List<KeyValuePair> pairs) implements AstNode {}

    // =========================================================================
    // Path expressions
    // =========================================================================

    /**
     * A chain of path steps separated by {@code .}.
     *
     * <p>Each element is a path step: a {@link FieldRef}, {@link WildcardStep},
     * {@link DescendantStep}, {@link PredicateExpr}, {@link ArraySubscript}, or
     * another nested expression.
     *
     * @param steps at least two steps
     */
    record PathExpr(List<AstNode> steps) implements AstNode {}

    /**
     * A predicate filter applied to a sequence: {@code expr[predicate]}.
     *
     * @param source    the input expression
     * @param predicate the filter condition (or an integer index)
     */
    record PredicateExpr(AstNode source, AstNode predicate) implements AstNode {}

    /**
     * An array subscript using the {@code [index]} notation where {@code index}
     * is a numeric expression. Logically equivalent to a predicate but emitted
     * separately for clarity during translation.
     *
     * @param source the input expression
     * @param index  the numeric index expression
     */
    record ArraySubscript(AstNode source, AstNode index) implements AstNode {}

    // =========================================================================
    // Operators
    // =========================================================================

    /**
     * A binary infix operation such as {@code a + b}, {@code a = b}, {@code a and b}.
     *
     * @param op    the operator string (e.g. {@code "+"}, {@code "and"}, {@code "!="})
     * @param left  left operand
     * @param right right operand
     */
    record BinaryOp(String op, AstNode left, AstNode right) implements AstNode {}

    /**
     * Unary negation: {@code -expr}.
     *
     * @param operand the expression being negated
     */
    record UnaryMinus(AstNode operand) implements AstNode {}

    // =========================================================================
    // Functions and lambdas
    // =========================================================================

    /**
     * A function invocation: {@code $func(arg1, arg2, ...)}.
     *
     * @param name the function name (without leading {@code $})
     * @param args the argument expressions (may be empty)
     */
    record FunctionCall(String name, List<AstNode> args) implements AstNode {}

    /**
     * A lambda (anonymous function): {@code function($x, $y) { body }}.
     *
     * @param params parameter names without the leading {@code $}
     * @param body   the function body
     */
    record Lambda(List<String> params, AstNode body) implements AstNode {}

    // =========================================================================
    // Variable binding
    // =========================================================================

    /**
     * A variable assignment: {@code $name := expr}.
     *
     * @param name  the variable name without the leading {@code $}
     * @param value the expression whose result is bound
     */
    record VariableBinding(String name, AstNode value) implements AstNode {}

    // =========================================================================
    // Control flow
    // =========================================================================

    /**
     * Conditional expression: {@code condition ? then : else}.
     * The else branch is optional (null means absent).
     *
     * @param condition the guard expression
     * @param then      the true branch
     * @param otherwise the false branch, or {@code null} if omitted
     */
    record ConditionalExpr(AstNode condition, AstNode then, AstNode otherwise) implements AstNode {}

    /**
     * A block of expressions enclosed in parentheses: {@code (expr1; expr2; ...)}.
     * Evaluates each expression in order; the block's value is the last expression.
     *
     * @param expressions at least one expression
     */
    record Block(List<AstNode> expressions) implements AstNode {}

    // =========================================================================
    // Range, sort, group-by
    // =========================================================================

    /**
     * A range expression: {@code [from..to]}.
     *
     * @param from the lower bound
     * @param to   the upper bound
     */
    record RangeExpr(AstNode from, AstNode to) implements AstNode {}

    /**
     * A single sort key inside a sort expression.
     *
     * @param key       the expression used as sort key
     * @param descending {@code true} for descending ({@code >}), {@code false} for ascending ({@code <})
     */
    record SortKey(AstNode key, boolean descending) {}

    /**
     * A sort expression: {@code expr^(key1, >key2, ...)}.
     *
     * @param source the sequence to sort
     * @param keys   the ordered list of sort criteria
     */
    record SortExpr(AstNode source, List<SortKey> keys) implements AstNode {}

    /**
     * A group-by / reduce expression: {@code expr{key: value}}.
     *
     * @param source the input sequence
     * @param pairs  the aggregation key-value pairs
     */
    record GroupByExpr(AstNode source, List<KeyValuePair> pairs) implements AstNode {}

    // =========================================================================
    // Chaining and transform
    // =========================================================================

    /**
     * The pipe-chain operator: {@code expr ~> $func}.
     *
     * <p>Applies each function in the chain to the result of the previous step.
     *
     * @param steps the ordered sequence of chain steps (left to right)
     */
    record ChainExpr(List<AstNode> steps) implements AstNode {}

    /**
     * A transform expression using the pipe ({@code |}) syntax:
     * {@code expr | pattern | update |}.
     *
     * @param source  the source expression
     * @param pattern the pattern selector
     * @param update  the update object constructor
     */
    record TransformExpr(AstNode source, AstNode pattern, AstNode update) implements AstNode {}

    /**
     * The force-array postfix operator {@code expr[]}.
     *
     * <p>Forces the result of the path expression to be an array even when
     * only one value was selected.  Stands in contrast to JSONata's normal
     * singleton-collapsing behaviour where a one-element sequence is
     * returned as a bare value.
     *
     * @param source the expression whose result must be an array
     */
    record ForceArray(AstNode source) implements AstNode {}

    /**
     * Marks an expression that was written inside explicit parentheses in the source.
     *
     * <p>Parentheses are normally transparent (they don't change runtime semantics),
     * but they <em>do</em> affect how a following subscript {@code [n]} is applied:
     * <ul>
     *   <li>{@code a.b[n]} — subscript applied per-element (bound to step {@code b}).</li>
     *   <li>{@code (a.b)[n]} — subscript applied to the whole collected sequence.</li>
     * </ul>
     * Wrapping the inner expression in this node lets the parser record that the
     * expression was parenthesised so that {@code parseSubscriptOrPredicate} can
     * choose the correct binding strategy.
     *
     * @param inner the wrapped expression
     */
    record Parenthesized(AstNode inner) implements AstNode {}

    // =========================================================================
    // Visitor
    // =========================================================================

    /**
     * Two-parameter visitor over the full {@link AstNode} hierarchy.
     *
     * <p>The context parameter {@code C} allows callers to thread arbitrary
     * per-traversal state (symbol tables, type environments, output builders,
     * etc.) without relying on mutable visitor fields. This makes the visitor
     * safe to use in concurrent or multi-pass pipelines.
     *
     * @param <R> the return type of each visit method
     * @param <C> the type of the context value threaded through the traversal
     */
    interface Visitor<R, C> {
        R visitStringLiteral(StringLiteral node, C ctx);
        R visitNumberLiteral(NumberLiteral node, C ctx);
        R visitBooleanLiteral(BooleanLiteral node, C ctx);
        R visitNullLiteral(NullLiteral node, C ctx);
        R visitContextRef(ContextRef node, C ctx);
        R visitRootRef(RootRef node, C ctx);
        R visitVariableRef(VariableRef node, C ctx);
        R visitFieldRef(FieldRef node, C ctx);
        R visitWildcardStep(WildcardStep node, C ctx);
        R visitDescendantStep(DescendantStep node, C ctx);
        R visitArrayConstructor(ArrayConstructor node, C ctx);
        R visitObjectConstructor(ObjectConstructor node, C ctx);
        R visitPathExpr(PathExpr node, C ctx);
        R visitPredicateExpr(PredicateExpr node, C ctx);
        R visitArraySubscript(ArraySubscript node, C ctx);
        R visitBinaryOp(BinaryOp node, C ctx);
        R visitUnaryMinus(UnaryMinus node, C ctx);
        R visitFunctionCall(FunctionCall node, C ctx);
        R visitLambda(Lambda node, C ctx);
        R visitVariableBinding(VariableBinding node, C ctx);
        R visitConditionalExpr(ConditionalExpr node, C ctx);
        R visitBlock(Block node, C ctx);
        R visitRangeExpr(RangeExpr node, C ctx);
        R visitSortExpr(SortExpr node, C ctx);
        R visitGroupByExpr(GroupByExpr node, C ctx);
        R visitChainExpr(ChainExpr node, C ctx);
        R visitTransformExpr(TransformExpr node, C ctx);
        R visitParenthesized(Parenthesized node, C ctx);
        R visitForceArray(ForceArray node, C ctx);
    }

    /**
     * Dispatches this node to the appropriate {@code visit*} method.
     *
     * @param visitor the visitor to dispatch to
     * @param ctx     the context value to pass through
     * @param <R>     the return type
     * @param <C>     the context type
     * @return whatever the visitor's method returns
     */
    default <R, C> R accept(Visitor<R, C> visitor, C ctx) {
        return switch (this) {
            case StringLiteral  n -> visitor.visitStringLiteral(n, ctx);
            case NumberLiteral  n -> visitor.visitNumberLiteral(n, ctx);
            case BooleanLiteral n -> visitor.visitBooleanLiteral(n, ctx);
            case NullLiteral    n -> visitor.visitNullLiteral(n, ctx);
            case ContextRef     n -> visitor.visitContextRef(n, ctx);
            case RootRef        n -> visitor.visitRootRef(n, ctx);
            case VariableRef    n -> visitor.visitVariableRef(n, ctx);
            case FieldRef       n -> visitor.visitFieldRef(n, ctx);
            case WildcardStep   n -> visitor.visitWildcardStep(n, ctx);
            case DescendantStep n -> visitor.visitDescendantStep(n, ctx);
            case ArrayConstructor  n -> visitor.visitArrayConstructor(n, ctx);
            case ObjectConstructor n -> visitor.visitObjectConstructor(n, ctx);
            case PathExpr       n -> visitor.visitPathExpr(n, ctx);
            case PredicateExpr  n -> visitor.visitPredicateExpr(n, ctx);
            case ArraySubscript n -> visitor.visitArraySubscript(n, ctx);
            case BinaryOp       n -> visitor.visitBinaryOp(n, ctx);
            case UnaryMinus     n -> visitor.visitUnaryMinus(n, ctx);
            case FunctionCall   n -> visitor.visitFunctionCall(n, ctx);
            case Lambda         n -> visitor.visitLambda(n, ctx);
            case VariableBinding n -> visitor.visitVariableBinding(n, ctx);
            case ConditionalExpr n -> visitor.visitConditionalExpr(n, ctx);
            case Block          n -> visitor.visitBlock(n, ctx);
            case RangeExpr      n -> visitor.visitRangeExpr(n, ctx);
            case SortExpr       n -> visitor.visitSortExpr(n, ctx);
            case GroupByExpr    n -> visitor.visitGroupByExpr(n, ctx);
            case ChainExpr      n -> visitor.visitChainExpr(n, ctx);
            case TransformExpr  n -> visitor.visitTransformExpr(n, ctx);
            case Parenthesized  n -> visitor.visitParenthesized(n, ctx);
            case ForceArray     n -> visitor.visitForceArray(n, ctx);
        };
    }
}
