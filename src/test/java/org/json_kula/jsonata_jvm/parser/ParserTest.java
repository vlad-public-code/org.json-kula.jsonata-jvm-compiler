package org.json_kula.jsonata_jvm.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 50 unit tests for {@link Parser#parse(String)}.
 *
 * <p>Tests are split into two groups:
 * <ul>
 *   <li>{@code parse_validExpression_*} – valid inputs that must produce a specific AST</li>
 *   <li>{@code parse_invalidExpression_throwsParseException} – inputs that must throw
 *       {@link ParseException}</li>
 * </ul>
 */
class ParserTest {

    // =========================================================================
    // Valid expressions — individual @Test methods (40 cases)
    // =========================================================================

    // --- Literals ---

    @Test
    void parse_stringLiteral_doubleQuoted() throws ParseException {
        AstNode ast = Parser.parse("\"hello\"");
        assertEquals(new StringLiteral("hello"), ast);
    }

    @Test
    void parse_stringLiteral_singleQuoted() throws ParseException {
        AstNode ast = Parser.parse("'world'");
        assertEquals(new StringLiteral("world"), ast);
    }

    @Test
    void parse_stringLiteral_withEscapeSequences() throws ParseException {
        AstNode ast = Parser.parse("\"line1\\nline2\\ttab\"");
        assertEquals(new StringLiteral("line1\nline2\ttab"), ast);
    }

    @Test
    void parse_integerLiteral() throws ParseException {
        AstNode ast = Parser.parse("42");
        assertEquals(new NumberLiteral(42.0), ast);
    }

    @Test
    void parse_decimalLiteral() throws ParseException {
        AstNode ast = Parser.parse("3.14");
        assertEquals(new NumberLiteral(3.14), ast);
    }

    @Test
    void parse_scientificNotationLiteral() throws ParseException {
        AstNode ast = Parser.parse("1.5e10");
        assertEquals(new NumberLiteral(1.5e10), ast);
    }

    @Test
    void parse_trueLiteral() throws ParseException {
        AstNode ast = Parser.parse("true");
        assertEquals(new BooleanLiteral(true), ast);
    }

    @Test
    void parse_falseLiteral() throws ParseException {
        AstNode ast = Parser.parse("false");
        assertEquals(new BooleanLiteral(false), ast);
    }

    @Test
    void parse_nullLiteral() throws ParseException {
        AstNode ast = Parser.parse("null");
        assertEquals(new NullLiteral(), ast);
    }

    // --- References ---

    @Test
    void parse_contextRef_bareDollar() throws ParseException {
        AstNode ast = Parser.parse("$");
        assertEquals(new ContextRef(), ast);
    }

    @Test
    void parse_rootRef_doubleDollar() throws ParseException {
        AstNode ast = Parser.parse("$$");
        assertEquals(new RootRef(), ast);
    }

    @Test
    void parse_variableRef() throws ParseException {
        AstNode ast = Parser.parse("$myVar");
        assertEquals(new VariableRef("myVar"), ast);
    }

    @Test
    void parse_fieldRef_bareIdentifier() throws ParseException {
        AstNode ast = Parser.parse("Account");
        assertEquals(new FieldRef("Account"), ast);
    }

    @Test
    void parse_fieldRef_backtickQuoted() throws ParseException {
        AstNode ast = Parser.parse("`order id`");
        assertEquals(new FieldRef("order id"), ast);
    }

    // --- Path expressions ---

    @Test
    void parse_dotPath_twoSteps() throws ParseException {
        AstNode ast = Parser.parse("Account.Name");
        assertInstanceOf(PathExpr.class, ast);
        PathExpr path = (PathExpr) ast;
        assertEquals(2, path.steps().size());
        assertEquals(new FieldRef("Account"), path.steps().get(0));
        assertEquals(new FieldRef("Name"),    path.steps().get(1));
    }

    @Test
    void parse_dotPath_threeSteps() throws ParseException {
        AstNode ast = Parser.parse("Order.Customer.Address");
        assertInstanceOf(PathExpr.class, ast);
        PathExpr path = (PathExpr) ast;
        assertEquals(3, path.steps().size());
        assertEquals(new FieldRef("Order"),    path.steps().get(0));
        assertEquals(new FieldRef("Customer"), path.steps().get(1));
        assertEquals(new FieldRef("Address"),  path.steps().get(2));
    }

    @Test
    void parse_wildcardStep() throws ParseException {
        AstNode ast = Parser.parse("*");
        assertEquals(new WildcardStep(), ast);
    }

    @Test
    void parse_descendantStep() throws ParseException {
        AstNode ast = Parser.parse("**");
        assertEquals(new DescendantStep(), ast);
    }

    @Test
    void parse_pathWithWildcard() throws ParseException {
        AstNode ast = Parser.parse("Account.*");
        assertInstanceOf(PathExpr.class, ast);
        PathExpr path = (PathExpr) ast;
        assertEquals(new WildcardStep(), path.steps().get(1));
    }

    @Test
    void parse_predicateFilter() throws ParseException {
        AstNode ast = Parser.parse("Account[type = 'premium']");
        assertInstanceOf(PredicateExpr.class, ast);
        PredicateExpr pred = (PredicateExpr) ast;
        assertEquals(new FieldRef("Account"), pred.source());
        assertInstanceOf(BinaryOp.class, pred.predicate());
    }

    @Test
    void parse_arraySubscriptByIndex() throws ParseException {
        AstNode ast = Parser.parse("items[0]");
        assertInstanceOf(ArraySubscript.class, ast);
        ArraySubscript sub = (ArraySubscript) ast;
        assertEquals(new FieldRef("items"), sub.source());
        assertEquals(new NumberLiteral(0.0), sub.index());
    }

    // --- Arithmetic operators ---

    @Test
    void parse_addition() throws ParseException {
        AstNode ast = Parser.parse("1 + 2");
        assertEquals(new BinaryOp("+", new NumberLiteral(1), new NumberLiteral(2)), ast);
    }

    @Test
    void parse_subtraction() throws ParseException {
        AstNode ast = Parser.parse("10 - 3");
        assertEquals(new BinaryOp("-", new NumberLiteral(10), new NumberLiteral(3)), ast);
    }

    @Test
    void parse_multiplication() throws ParseException {
        AstNode ast = Parser.parse("4 * 5");
        assertEquals(new BinaryOp("*", new NumberLiteral(4), new NumberLiteral(5)), ast);
    }

    @Test
    void parse_division() throws ParseException {
        AstNode ast = Parser.parse("10 / 2");
        assertEquals(new BinaryOp("/", new NumberLiteral(10), new NumberLiteral(2)), ast);
    }

    @Test
    void parse_modulo() throws ParseException {
        AstNode ast = Parser.parse("7 % 3");
        assertEquals(new BinaryOp("%", new NumberLiteral(7), new NumberLiteral(3)), ast);
    }

    @Test
    void parse_unaryMinus() throws ParseException {
        AstNode ast = Parser.parse("-$x");
        assertEquals(new UnaryMinus(new VariableRef("x")), ast);
    }

    @Test
    void parse_negativeNumberLiteral_optimisedToNumberLiteral() throws ParseException {
        AstNode ast = Parser.parse("-42");
        // Parser should fold -42 directly into a NumberLiteral
        assertEquals(new NumberLiteral(-42.0), ast);
    }

    @Test
    void parse_operatorPrecedence_mulBeforeAdd() throws ParseException {
        // 2 + 3 * 4  =>  BinaryOp(+, 2, BinaryOp(*, 3, 4))
        AstNode ast = Parser.parse("2 + 3 * 4");
        BinaryOp add = (BinaryOp) ast;
        assertEquals("+", add.op());
        assertEquals(new NumberLiteral(2), add.left());
        BinaryOp mul = (BinaryOp) add.right();
        assertEquals("*", mul.op());
    }

    // --- Comparison and logical operators ---

    @Test
    void parse_equalityComparison() throws ParseException {
        AstNode ast = Parser.parse("status = 'active'");
        BinaryOp op = (BinaryOp) ast;
        assertEquals("=", op.op());
        assertEquals(new FieldRef("status"), op.left());
        assertEquals(new StringLiteral("active"), op.right());
    }

    @Test
    void parse_notEqualComparison() throws ParseException {
        AstNode ast = Parser.parse("qty != 0");
        BinaryOp op = (BinaryOp) ast;
        assertEquals("!=", op.op());
    }

    @Test
    void parse_andExpression() throws ParseException {
        AstNode ast = Parser.parse("a and b");
        BinaryOp op = (BinaryOp) ast;
        assertEquals("and", op.op());
        assertEquals(new FieldRef("a"), op.left());
        assertEquals(new FieldRef("b"), op.right());
    }

    @Test
    void parse_orExpression() throws ParseException {
        AstNode ast = Parser.parse("x or y");
        BinaryOp op = (BinaryOp) ast;
        assertEquals("or", op.op());
    }

    @Test
    void parse_inExpression() throws ParseException {
        AstNode ast = Parser.parse("\"red\" in colors");
        BinaryOp op = (BinaryOp) ast;
        assertEquals("in", op.op());
        assertEquals(new StringLiteral("red"), op.left());
        assertEquals(new FieldRef("colors"), op.right());
    }

    @Test
    void parse_stringConcatenation() throws ParseException {
        AstNode ast = Parser.parse("first & ' ' & last");
        // Left-associative: (first & ' ') & last
        BinaryOp outer = (BinaryOp) ast;
        assertEquals("&", outer.op());
        BinaryOp inner = (BinaryOp) outer.left();
        assertEquals("&", inner.op());
        assertEquals(new FieldRef("first"), inner.left());
    }

    // --- Conditional ---

    @Test
    void parse_conditionalWithElse() throws ParseException {
        AstNode ast = Parser.parse("a > 0 ? 'pos' : 'neg'");
        assertInstanceOf(ConditionalExpr.class, ast);
        ConditionalExpr cond = (ConditionalExpr) ast;
        assertNotNull(cond.then());
        assertNotNull(cond.otherwise());
        assertEquals(new StringLiteral("pos"), cond.then());
        assertEquals(new StringLiteral("neg"), cond.otherwise());
    }

    @Test
    void parse_conditionalWithoutElse() throws ParseException {
        AstNode ast = Parser.parse("flag ? 'yes'");
        assertInstanceOf(ConditionalExpr.class, ast);
        ConditionalExpr cond = (ConditionalExpr) ast;
        assertNull(cond.otherwise());
    }

    // --- Function calls ---

    @Test
    void parse_builtinFunctionCall_noArgs() throws ParseException {
        // $now() has no arguments
        AstNode ast = Parser.parse("$now()");
        FunctionCall fc = (FunctionCall) ast;
        assertEquals("now", fc.name());
        assertTrue(fc.args().isEmpty());
    }

    @Test
    void parse_builtinFunctionCall_oneArg() throws ParseException {
        AstNode ast = Parser.parse("$string(value)");
        FunctionCall fc = (FunctionCall) ast;
        assertEquals("string", fc.name());
        assertEquals(1, fc.args().size());
        assertEquals(new FieldRef("value"), fc.args().get(0));
    }

    @Test
    void parse_builtinFunctionCall_multipleArgs() throws ParseException {
        AstNode ast = Parser.parse("$substring(str, 0, 5)");
        FunctionCall fc = (FunctionCall) ast;
        assertEquals("substring", fc.name());
        assertEquals(3, fc.args().size());
    }

    @Test
    void parse_notKeyword_treatedAsFunctionCall() throws ParseException {
        AstNode ast = Parser.parse("not(true)");
        FunctionCall fc = (FunctionCall) ast;
        assertEquals("not", fc.name());
        assertEquals(List.of(new BooleanLiteral(true)), fc.args());
    }

    // --- Lambda ---

    @Test
    void parse_lambdaNoParams() throws ParseException {
        AstNode ast = Parser.parse("function(){ 42 }");
        Lambda lam = (Lambda) ast;
        assertTrue(lam.params().isEmpty());
        assertEquals(new NumberLiteral(42), lam.body());
    }

    @Test
    void parse_lambdaWithParams() throws ParseException {
        AstNode ast = Parser.parse("function($x, $y){ $x + $y }");
        Lambda lam = (Lambda) ast;
        assertEquals(List.of("x", "y"), lam.params());
        BinaryOp body = (BinaryOp) lam.body();
        assertEquals("+", body.op());
    }

    // --- Variable binding ---

    @Test
    void parse_variableBinding() throws ParseException {
        AstNode ast = Parser.parse("$total := price * qty");
        VariableBinding vb = (VariableBinding) ast;
        assertEquals("total", vb.name());
        BinaryOp mul = (BinaryOp) vb.value();
        assertEquals("*", mul.op());
    }

    // --- Block ---

    @Test
    void parse_block_multipleStatements() throws ParseException {
        // Parenthesised blocks are now wrapped in Parenthesized to distinguish
        // (a.b)[n] (whole-result subscript) from a.b[n] (per-element subscript).
        AstNode ast = Parser.parse("($a := 1; $b := 2; $a + $b)");
        assertInstanceOf(Parenthesized.class, ast);
        Block block = (Block) ((Parenthesized) ast).inner();
        assertEquals(3, block.expressions().size());
        assertInstanceOf(VariableBinding.class, block.expressions().get(0));
        assertInstanceOf(VariableBinding.class, block.expressions().get(1));
        assertInstanceOf(BinaryOp.class,        block.expressions().get(2));
    }

    @Test
    void parse_block_singleExpressionWrappedInParenthesized() throws ParseException {
        // (expr) is represented as Parenthesized(inner) — the inner expression is
        // not a Block, but the Parenthesized wrapper is retained by the parser so
        // that a following subscript [n] can choose whole-result semantics.
        AstNode ast = Parser.parse("(42)");
        assertInstanceOf(Parenthesized.class, ast);
        assertEquals(new NumberLiteral(42), ((Parenthesized) ast).inner());
    }

    // --- Array and object constructors ---

    @Test
    void parse_emptyArrayConstructor() throws ParseException {
        AstNode ast = Parser.parse("[]");
        ArrayConstructor arr = (ArrayConstructor) ast;
        assertTrue(arr.elements().isEmpty());
    }

    @Test
    void parse_arrayConstructorWithElements() throws ParseException {
        AstNode ast = Parser.parse("[1, 2, 3]");
        ArrayConstructor arr = (ArrayConstructor) ast;
        assertEquals(3, arr.elements().size());
        assertEquals(new NumberLiteral(1), arr.elements().get(0));
        assertEquals(new NumberLiteral(2), arr.elements().get(1));
        assertEquals(new NumberLiteral(3), arr.elements().get(2));
    }

    @Test
    void parse_rangeExpressionInBrackets() throws ParseException {
        AstNode ast = Parser.parse("[1..5]");
        RangeExpr range = (RangeExpr) ast;
        assertEquals(new NumberLiteral(1), range.from());
        assertEquals(new NumberLiteral(5), range.to());
    }

    @Test
    void parse_emptyObjectConstructor() throws ParseException {
        AstNode ast = Parser.parse("{}");
        ObjectConstructor obj = (ObjectConstructor) ast;
        assertTrue(obj.pairs().isEmpty());
    }

    @Test
    void parse_objectConstructorWithPairs() throws ParseException {
        AstNode ast = Parser.parse("{\"name\": first, \"age\": years}");
        ObjectConstructor obj = (ObjectConstructor) ast;
        assertEquals(2, obj.pairs().size());
        assertEquals(new StringLiteral("name"), obj.pairs().get(0).key());
        assertEquals(new FieldRef("first"),     obj.pairs().get(0).value());
    }

    // --- Chain operator ---

    @Test
    void parse_chainOperator() throws ParseException {
        AstNode ast = Parser.parse("items ~> $sort()");
        ChainExpr chain = (ChainExpr) ast;
        assertEquals(2, chain.steps().size());
        assertEquals(new FieldRef("items"), chain.steps().get(0));
        assertInstanceOf(FunctionCall.class,  chain.steps().get(1));
    }

    // --- Whitespace and comments ---

    @Test
    void parse_expressionWithBlockComment() throws ParseException {
        AstNode ast = Parser.parse("1 /* the answer */ + 1");
        assertEquals(new BinaryOp("+", new NumberLiteral(1), new NumberLiteral(1)), ast);
    }

    @Test
    void parse_expressionWithLeadingAndTrailingWhitespace() throws ParseException {
        AstNode ast = Parser.parse("   true   ");
        assertEquals(new BooleanLiteral(true), ast);
    }

    // =========================================================================
    // Invalid expressions — parameterized @Test (10 cases)
    // =========================================================================

    record InvalidCase(String expression, String description) {}

    static Stream<InvalidCase> invalidExpressions() {
        return Stream.of(
            new InvalidCase("",                     "empty string"),
            new InvalidCase("(",                    "unclosed parenthesis"),
            new InvalidCase("[",                    "unclosed bracket"),
            new InvalidCase("{",                    "unclosed brace"),
            new InvalidCase("1 +",                  "trailing operator with no right operand"),
            new InvalidCase("!x",                   "bare ! without = is illegal"),
            new InvalidCase("~x",                   "bare ~ without > is illegal"),
            new InvalidCase("\"unterminated",       "unterminated string literal"),
            new InvalidCase("`unclosed backtick",   "unterminated backtick identifier"),
            new InvalidCase("1 2",                  "two adjacent primaries with no operator")
        );
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("invalidExpressions")
    void parse_invalidExpression_throwsParseException(InvalidCase tc) {
        assertThrows(ParseException.class, () -> Parser.parse(tc.expression()),
                "Expected ParseException for: " + tc.description());
    }
}
