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
    void optimize_doubleUnaryMinus_eliminated() throws ParseException {
        // -(-$x)  →  $x
        AstNode inner = new VariableRef("x");
        AstNode ast = Optimizer.optimize(new UnaryMinus(new UnaryMinus(inner)));
        assertEquals(inner, ast);
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

    @Test
    void optimize_addZeroRight_eliminated() throws ParseException {
        // $x + 0  →  $x
        assertEquals(new VariableRef("x"), opt("$x + 0"));
    }

    @Test
    void optimize_addZeroLeft_eliminated() throws ParseException {
        // 0 + $x  →  $x
        assertEquals(new VariableRef("x"), opt("0 + $x"));
    }

    @Test
    void optimize_subtractZero_eliminated() throws ParseException {
        // $x - 0  →  $x
        assertEquals(new VariableRef("x"), opt("$x - 0"));
    }

    @Test
    void optimize_multiplyByOne_eliminated() throws ParseException {
        // $x * 1  →  $x
        assertEquals(new VariableRef("x"), opt("$x * 1"));
    }

    @Test
    void optimize_multiplyOneByX_eliminated() throws ParseException {
        // 1 * $x  →  $x
        assertEquals(new VariableRef("x"), opt("1 * $x"));
    }

    @Test
    void optimize_multiplyByZero_foldedToZero() throws ParseException {
        // $x * 0  →  0
        assertEquals(new NumberLiteral(0), opt("$x * 0"));
    }

    @Test
    void optimize_zeroMultiplyX_foldedToZero() throws ParseException {
        assertEquals(new NumberLiteral(0), opt("0 * $x"));
    }

    @Test
    void optimize_divideByOne_eliminated() throws ParseException {
        // $x / 1  →  $x
        assertEquals(new VariableRef("x"), opt("$x / 1"));
    }

    // =========================================================================
    // String constant folding and identity
    // =========================================================================

    @Test
    void optimize_stringConcatLiterals_folded() throws ParseException {
        assertEquals(new StringLiteral("hello world"), opt("\"hello\" & \" world\""));
    }

    @Test
    void optimize_concatEmptyStringRight_eliminated() throws ParseException {
        // $x & ""  →  $x
        assertEquals(new VariableRef("x"), opt("$x & \"\""));
    }

    @Test
    void optimize_concatEmptyStringLeft_eliminated() throws ParseException {
        // "" & $x  →  $x
        assertEquals(new VariableRef("x"), opt("\"\" & $x"));
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
    void optimize_xAndTrue_simplifiedToX() throws ParseException {
        // $x and true  →  $x
        assertEquals(new VariableRef("x"), opt("$x and true"));
    }

    @Test
    void optimize_xAndFalse_shortCircuitedToFalse() throws ParseException {
        // $x and false  →  false  (regardless of $x)
        assertEquals(new BooleanLiteral(false), opt("$x and false"));
    }

    @Test
    void optimize_xOrFalse_simplifiedToX() throws ParseException {
        // $x or false  →  $x
        assertEquals(new VariableRef("x"), opt("$x or false"));
    }

    @Test
    void optimize_xOrTrue_shortCircuitedToTrue() throws ParseException {
        // $x or true  →  true
        assertEquals(new BooleanLiteral(true), opt("$x or true"));
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
    void optimize_conditionalFalseNoElse_returnsNull() throws ParseException {
        // false ? "yes"  →  null
        assertEquals(new NullLiteral(), opt("false ? \"yes\""));
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
