package org.json_kula.jsonata_jvm.optimizer;

import org.junit.jupiter.api.Test;
import org.json_kula.jsonata_jvm.parser.ParseException;
import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 50 unit tests for {@link Optimizer#optimize(AstNode)}.
 *
 * <p>Tests are written in terms of parse-then-optimize so that they read
 * naturally as JSONata expressions.  Where the expected result is itself a
 * non-trivial AST, it is constructed directly to avoid depending on the parser.
 */
class OptimizerTest {

    /** Parse {@code expr}, optimize, and return the result. */
    private static AstNode opt(String expr) throws ParseException {
        return Optimizer.optimize(Parser.parse(expr));
    }

    // =========================================================================
    // Record flags carried on rebuilt nodes (J-2)
    // =========================================================================

    /**
     * Four AST records carry a flag beyond their children: {@code Lambda.signature},
     * {@code PredicateExpr.stage}, {@code FunctionCall.isVariable} and
     * {@code ArrayConstructor.pathHead}. The optimizer rebuilds a node whenever one of its
     * children is rewritten, and rebuilding through the short convenience constructor reset
     * the flag to its default — silently, and only for expressions that happened to contain a
     * constant fold.
     */
    @Test
    void optimize_rebuiltNodes_keepTheirFlags() {
        // Each child below contains a fold ("" & "a", 1+0) so the parent is genuinely rebuilt.
        AstNode foldableStr = new BinaryOp("&", new StringLiteral(""), new StringLiteral("a"));
        AstNode foldableNum = new BinaryOp("+", new NumberLiteral(1), new NumberLiteral(0));

        Lambda lambda = (Lambda) Optimizer.optimize(
                new Lambda(List.of("x"), foldableStr, "<s>"));
        assertNotSame(foldableStr, lambda.body());
        assertEquals("<s>", lambda.signature(), "Lambda.signature was dropped");

        PredicateExpr pred = (PredicateExpr) Optimizer.optimize(
                new PredicateExpr(new VariableRef("x"), foldableNum, true));
        assertTrue(pred.stage(), "PredicateExpr.stage was dropped");

        FunctionCall call = (FunctionCall) Optimizer.optimize(
                new FunctionCall("g", List.of(foldableNum), true));
        assertTrue(call.isVariable(), "FunctionCall.isVariable was dropped");

        ArrayConstructor arr = (ArrayConstructor) Optimizer.optimize(
                new ArrayConstructor(List.of(foldableNum), true));
        assertTrue(arr.pathHead(), "ArrayConstructor.pathHead was dropped");
    }

    // =========================================================================
    // Terminals — must survive the optimizer unchanged
    // =========================================================================

    @Test
    void optimize_stringLiteral_unchanged() throws ParseException {
        assertEquals(new StringLiteral("hi"), opt("\"hi\""));
    }

    @Test
    void optimize_numberLiteral_unchanged() throws ParseException {
        assertEquals(new NumberLiteral(7), opt("7"));
    }

    @Test
    void optimize_trueLiteral_unchanged() throws ParseException {
        assertEquals(new BooleanLiteral(true), opt("true"));
    }

    @Test
    void optimize_falseLiteral_unchanged() throws ParseException {
        assertEquals(new BooleanLiteral(false), opt("false"));
    }

    @Test
    void optimize_nullLiteral_unchanged() throws ParseException {
        assertEquals(new NullLiteral(), opt("null"));
    }

    @Test
    void optimize_contextRef_unchanged() throws ParseException {
        assertEquals(new ContextRef(), opt("$"));
    }

    @Test
    void optimize_variableRef_unchanged() throws ParseException {
        assertEquals(new VariableRef("x"), opt("$x"));
    }

    @Test
    void optimize_fieldRef_unchanged() throws ParseException {
        assertEquals(new FieldRef("Account"), opt("Account"));
    }

    // =========================================================================
    // Unary minus
    // =========================================================================

    @Test
    void optimize_unaryMinusOnNumber_foldedToNegativeLiteral() throws ParseException {
        // Parser already produces NumberLiteral(-5) for "-5", but -($x) stays.
        // Test with -($count) which stays as UnaryMinus, then -(-(expr)).
        AstNode ast = Optimizer.optimize(new UnaryMinus(new NumberLiteral(5)));
        assertEquals(new NumberLiteral(-5), ast);
    }

    @Test
    void optimize_doubleUnaryMinus_notEliminated() throws ParseException {
        // -(-$x) is NOT $x: unary minus is only defined for numbers, so `-(-"a")` must raise
        // D1002 (reference jsonata 2.2.2). Folding it away returned "a".
        AstNode inner = new VariableRef("x");
        AstNode ast = Optimizer.optimize(new UnaryMinus(new UnaryMinus(inner)));
        assertEquals(new UnaryMinus(new UnaryMinus(inner)), ast);
    }

    @Test
    void optimize_negativeNumberLiteral_parsedDirectly() throws ParseException {
        // The parser folds -42 → NumberLiteral(-42); optimizer keeps it as-is.
        assertEquals(new NumberLiteral(-42), opt("-42"));
    }

    // =========================================================================
    // Arithmetic constant folding
    // =========================================================================

    @Test
    void optimize_addTwoNumbers_folded() throws ParseException {
        assertEquals(new NumberLiteral(5), opt("2 + 3"));
    }

    @Test
    void optimize_subtractNumbers_folded() throws ParseException {
        assertEquals(new NumberLiteral(7), opt("10 - 3"));
    }

    @Test
    void optimize_multiplyNumbers_folded() throws ParseException {
        assertEquals(new NumberLiteral(20), opt("4 * 5"));
    }

    @Test
    void optimize_divideNumbers_folded() throws ParseException {
        assertEquals(new NumberLiteral(2.5), opt("5 / 2"));
    }

    @Test
    void optimize_moduloNumbers_folded() throws ParseException {
        assertEquals(new NumberLiteral(1), opt("7 % 3"));
    }

    @Test
    void optimize_nestedArithmetic_fullyFolded() throws ParseException {
        // (2 + 3) * 4  →  20
        assertEquals(new NumberLiteral(20), opt("(2 + 3) * 4"));
    }

    @Test
    void optimize_divisionByZero_notFolded() throws ParseException {
        // Division by zero must NOT be folded — leave for the runtime.
        AstNode ast = opt("1 / 0");
        assertInstanceOf(BinaryOp.class, ast);
        BinaryOp op = (BinaryOp) ast;
        assertEquals("/", op.op());
    }

    @Test
    void optimize_moduloByZero_notFolded() throws ParseException {
        AstNode ast = opt("5 % 0");
        assertInstanceOf(BinaryOp.class, ast);
    }

    // =========================================================================
    // Arithmetic identity / absorption
    // =========================================================================

    // None of `x+0`, `x-0`, `x*1`, `x*0`, `x/1` may be folded: each assumes the non-literal
    // operand is a number, which the optimizer cannot know. Against reference jsonata 2.2.2,
    // `nope * 0` is *undefined* (not 0) and `"a" + 0` raises T2001 (not "a").

    @Test
    void optimize_addZeroRight_notEliminated() throws ParseException {
        assertEquals(new BinaryOp("+", new VariableRef("x"), new NumberLiteral(0)), opt("$x + 0"));
    }

    @Test
    void optimize_addZeroLeft_notEliminated() throws ParseException {
        assertEquals(new BinaryOp("+", new NumberLiteral(0), new VariableRef("x")), opt("0 + $x"));
    }

    @Test
    void optimize_subtractZero_notEliminated() throws ParseException {
        assertEquals(new BinaryOp("-", new VariableRef("x"), new NumberLiteral(0)), opt("$x - 0"));
    }

    @Test
    void optimize_multiplyByOne_notEliminated() throws ParseException {
        assertEquals(new BinaryOp("*", new VariableRef("x"), new NumberLiteral(1)), opt("$x * 1"));
    }

    @Test
    void optimize_multiplyOneByX_notEliminated() throws ParseException {
        assertEquals(new BinaryOp("*", new NumberLiteral(1), new VariableRef("x")), opt("1 * $x"));
    }

    @Test
    void optimize_multiplyByZero_notFolded() throws ParseException {
        assertEquals(new BinaryOp("*", new VariableRef("x"), new NumberLiteral(0)), opt("$x * 0"));
    }

    @Test
    void optimize_zeroMultiplyX_notFolded() throws ParseException {
        assertEquals(new BinaryOp("*", new NumberLiteral(0), new VariableRef("x")), opt("0 * $x"));
    }

    @Test
    void optimize_divideByOne_notEliminated() throws ParseException {
        assertEquals(new BinaryOp("/", new VariableRef("x"), new NumberLiteral(1)), opt("$x / 1"));
    }

    // =========================================================================
    // String constant folding and identity
    // =========================================================================

    @Test
    void optimize_stringConcatLiterals_folded() throws ParseException {
        assertEquals(new StringLiteral("hello world"), opt("\"hello\" & \" world\""));
    }

    /**
      * {@code $x & ""} is NOT the identity: {@code &} stringifies, so {@code 5 & ""} is
      * "5" and {@code true & ""} is "true". Dropping the concat turned every such
      * expression into its unconverted operand. The rewrite now applies only when both
      * sides are string literals, where it is a plain constant fold.
      */
    @Test
    void optimize_concatEmptyStringRight_kept() throws ParseException {
        assertEquals(new BinaryOp("&", new VariableRef("x"), new StringLiteral("")),
                opt("$x & \"\""));
    }

    @Test
    void optimize_concatEmptyStringLeft_kept() throws ParseException {
        assertEquals(new BinaryOp("&", new StringLiteral(""), new VariableRef("x")),
                opt("\"\" & $x"));
    }

    @Test
    void optimize_concatEmptyStringLiterals_folded() throws ParseException {
        assertEquals(new StringLiteral("hi"), opt("\"hi\" & \"\""));
    }

    @Test
    void optimize_stringEqualityLiterals_folded() throws ParseException {
        assertEquals(new BooleanLiteral(true),  opt("\"a\" = \"a\""));
        assertEquals(new BooleanLiteral(false), opt("\"a\" = \"b\""));
    }

    // =========================================================================
    // Comparison constant folding
    // =========================================================================

    @Test
    void optimize_numericComparison_lessThan_folded() throws ParseException {
        assertEquals(new BooleanLiteral(true),  opt("1 < 2"));
        assertEquals(new BooleanLiteral(false), opt("2 < 1"));
    }

    @Test
    void optimize_numericComparison_greaterThanOrEqual_folded() throws ParseException {
        assertEquals(new BooleanLiteral(true), opt("5 >= 5"));
    }

    @Test
    void optimize_numericNotEqual_folded() throws ParseException {
        assertEquals(new BooleanLiteral(true),  opt("1 != 2"));
        assertEquals(new BooleanLiteral(false), opt("3 != 3"));
    }

    // =========================================================================
    // Boolean constant folding and identities
    // =========================================================================

    @Test
    void optimize_trueAndFalse_folded() throws ParseException {
        assertEquals(new BooleanLiteral(false), opt("true and false"));
    }

    @Test
    void optimize_trueOrFalse_folded() throws ParseException {
        assertEquals(new BooleanLiteral(true), opt("true or false"));
    }

    @Test
    void optimize_xAndTrue_notSimplified() throws ParseException {
        // $x and true cannot be simplified to $x: $x may be non-boolean at runtime,
        // but and_ always returns bool(...). Returning $x raw would change the result type.
        assertEquals(new BinaryOp("and", new VariableRef("x"), new BooleanLiteral(true)),
                opt("$x and true"));
    }

    @Test
    void optimize_xAndFalse_notShortCircuited() throws ParseException {
        // The reference short-circuits on the LEFT operand only, so the left side still runs
        // and may raise: `$error("x") and false` is D3137, not false.
        assertEquals(new BinaryOp("and", new VariableRef("x"), new BooleanLiteral(false)),
                opt("$x and false"));
    }

    @Test
    void optimize_falseAndX_shortCircuitedToFalse() throws ParseException {
        // `false and X` never evaluates X in the reference, so this fold is sound.
        assertEquals(new BooleanLiteral(false), opt("false and $x"));
    }

    @Test
    void optimize_xOrFalse_notSimplified() throws ParseException {
        // $x or false cannot be simplified to $x: same reason as $x and true.
        assertEquals(new BinaryOp("or", new VariableRef("x"), new BooleanLiteral(false)),
                opt("$x or false"));
    }

    @Test
    void optimize_xOrTrue_notShortCircuited() throws ParseException {
        // Mirror of `$x and false`: the left operand still runs.
        assertEquals(new BinaryOp("or", new VariableRef("x"), new BooleanLiteral(true)),
                opt("$x or true"));
    }

    @Test
    void optimize_trueOrX_shortCircuitedToTrue() throws ParseException {
        assertEquals(new BooleanLiteral(true), opt("true or $x"));
    }

    // =========================================================================
    // Conditional folding
    // =========================================================================

    @Test
    void optimize_conditionalTrueCondition_returnsThen() throws ParseException {
        // true ? "yes" : "no"  →  "yes"
        assertEquals(new StringLiteral("yes"), opt("true ? \"yes\" : \"no\""));
    }

    @Test
    void optimize_conditionalFalseCondition_returnsElse() throws ParseException {
        // false ? "yes" : "no"  →  "no"
        assertEquals(new StringLiteral("no"), opt("false ? \"yes\" : \"no\""));
    }

    @Test
    void optimize_conditionalNullCondition_returnsElse() throws ParseException {
        // null ? "yes" : "no"  →  "no"
        assertEquals(new StringLiteral("no"), opt("null ? \"yes\" : \"no\""));
    }

    @Test
    void optimize_conditionalFalseNoElse_notFolded() throws ParseException {
        // `false ? "yes"` is *undefined*, not JSON null (reference jsonata 2.2.2 drops it from
        // the surrounding array/object). There is no AST literal for undefined, so the node is
        // kept and the translator produces the absent value.
        assertEquals(new ConditionalExpr(new BooleanLiteral(false), new StringLiteral("yes"), null),
                opt("false ? \"yes\""));
    }

    @Test
    void optimize_conditionalNullNoElse_notFolded() throws ParseException {
        assertEquals(new ConditionalExpr(new NullLiteral(), new StringLiteral("yes"), null),
                opt("null ? \"yes\""));
    }

    @Test
    void optimize_conditionalDynamicCondition_preserved() throws ParseException {
        // $x ? "a" : "b"  — condition is not a literal, must be preserved
        AstNode ast = opt("$x ? \"a\" : \"b\"");
        assertInstanceOf(ConditionalExpr.class, ast);
    }

    // =========================================================================
    // Block unwrapping
    // =========================================================================

    @Test
    void optimize_blockWithSingleExpression_unwrapped() throws ParseException {
        // (42)  →  42  (already done by parser, but test the optimizer too)
        AstNode ast = Optimizer.optimize(new Block(List.of(new NumberLiteral(7))));
        assertEquals(new NumberLiteral(7), ast);
    }

    @Test
    void optimize_blockWithMultipleExpressions_preserved() throws ParseException {
        AstNode ast = opt("($a := 1; $a + 2)");
        assertInstanceOf(Block.class, ast);
    }

    // =========================================================================
    // PathExpr flattening
    // =========================================================================

    @Test
    void optimize_nestedPathExpr_flattened() throws ParseException {
        // Build  PathExpr([PathExpr([A, B]), C])  manually and verify it flattens.
        AstNode inner = new PathExpr(List.of(new FieldRef("A"), new FieldRef("B")));
        AstNode outer = new PathExpr(List.of(inner, new FieldRef("C")));
        AstNode result = Optimizer.optimize(outer);
        PathExpr path = (PathExpr) result;
        assertEquals(List.of(new FieldRef("A"), new FieldRef("B"), new FieldRef("C")),
                path.steps());
    }

    // =========================================================================
    // Non-constant sub-trees — ensure optimizer preserves structure
    // =========================================================================

    @Test
    void optimize_mixedExpr_onlyConstantPartFolded() throws ParseException {
        // $x + (2 * 3)  →  BinaryOp(+, $x, 6)
        AstNode ast = opt("$x + (2 * 3)");
        BinaryOp op = (BinaryOp) ast;
        assertEquals("+", op.op());
        assertEquals(new VariableRef("x"), op.left());
        assertEquals(new NumberLiteral(6), op.right());
    }

    @Test
    void optimize_functionCall_argsOptimized() throws ParseException {
        // $sum(1 + 1, 2 * 2)  →  $sum(2, 4)
        AstNode ast = opt("$sum(1 + 1, 2 * 2)");
        FunctionCall fc = (FunctionCall) ast;
        assertEquals("sum", fc.name());
        assertEquals(new NumberLiteral(2), fc.args().get(0));
        assertEquals(new NumberLiteral(4), fc.args().get(1));
    }
}
