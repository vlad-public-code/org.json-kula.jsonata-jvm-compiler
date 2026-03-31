package org.json_kula.jsonata_jvm.parser;

import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;
import org.json_kula.jsonata_jvm.parser.lexer.Lexer;
import org.json_kula.jsonata_jvm.parser.lexer.Token;
import org.json_kula.jsonata_jvm.parser.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

import static org.json_kula.jsonata_jvm.parser.lexer.TokenType.*;

/**
 * Recursive-descent parser for the JSONata expression language.
 *
 * <h2>Operator precedence (low → high)</h2>
 * <ol>
 *   <li>{@code :=}  — variable binding</li>
 *   <li>{@code ?:}  — conditional (ternary)</li>
 *   <li>{@code or}</li>
 *   <li>{@code and}</li>
 *   <li>{@code in}  — containment</li>
 *   <li>{@code =  !=  <  <=  >  >=}  — comparison</li>
 *   <li>{@code &}   — string concatenation</li>
 *   <li>{@code +  -}</li>
 *   <li>{@code *  /  %}</li>
 *   <li>Unary {@code -} and {@code not}</li>
 *   <li>{@code ~>}  — function chaining</li>
 *   <li>Postfix: {@code .} path step, {@code [predicate]}, {@code ^(...)} sort,
 *       {@code {key:val}} group-by, {@code |...|} transform</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>{@code
 *     AstNode ast = Parser.parse("Account.Name");
 * }</pre>
 */
public final class Parser {

    private final List<Token> tokens;
    private int cursor;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.cursor = 0;
    }

    /**
     * Parses {@code expression} and returns the root AST node.
     *
     * @param expression the JSONata source text
     * @return the parsed AST
     * @throws ParseException if the expression is syntactically invalid
     */
    public static AstNode parse(String expression) throws ParseException {
        List<Token> tokens = Lexer.tokenize(expression);
        Parser parser = new Parser(tokens);
        AstNode result = parser.parseExpression();
        if (!parser.peek().type().equals(EOF)) {
            Token t = parser.peek();
            throw new ParseException("Unexpected token: " + t, t.position());
        }
        return result;
    }

    // =========================================================================
    // Precedence levels
    // =========================================================================

    /** Entry point: lowest-precedence expression. */
    private AstNode parseExpression() throws ParseException {
        return parseBinding();
    }

    // Level 1: variable binding  $name := expr
    private AstNode parseBinding() throws ParseException {
        if (peek().type() == VARIABLE && peekAt(1).type() == COLON_ASSIGN) {
            String name = consume(VARIABLE).value();
            consume(COLON_ASSIGN);
            AstNode value = parseConditional();
            return new VariableBinding(name, value);
        }
        return parseConditional();
    }

    // Level 2: conditional  condition ? then : else  /  left ?: right  /  left ?? right
    //
    // The ternary operator is right-associative so that chained ternaries like
    //   $x > 0 ? "pos" : $x < 0 ? "neg" : "zero"
    // parse as  ($x > 0 ? "pos" : ($x < 0 ? "neg" : "zero")).
    // Both the 'then' and 'otherwise' branches are parsed via a recursive call to
    // parseConditional() rather than parseOr() to achieve this.
    private AstNode parseConditional() throws ParseException {
        AstNode left = parseOr();
        if (peek().type() == QUESTION) {
            consume(QUESTION);
            AstNode then      = parseConditional();   // right-associative
            AstNode otherwise = null;
            if (peek().type() == COLON) {
                consume(COLON);
                otherwise = parseConditional();       // right-associative
            }
            return new ConditionalExpr(left, then, otherwise);
        }
        if (peek().type() == QUESTION_COLON) {
            consume(QUESTION_COLON);
            AstNode right = parseConditional();
            return new ElvisExpr(left, right);
        }
        if (peek().type() == QUESTION_QUESTION) {
            consume(QUESTION_QUESTION);
            AstNode right = parseConditional();
            return new CoalesceExpr(left, right);
        }
        return left;
    }

    // Level 3: or
    private AstNode parseOr() throws ParseException {
        AstNode left = parseAnd();
        while (peek().type() == OR) {
            consume(OR);
            left = new BinaryOp("or", left, parseAnd());
        }
        return left;
    }

    // Level 4: and
    private AstNode parseAnd() throws ParseException {
        AstNode left = parseIn();
        while (peek().type() == AND) {
            consume(AND);
            left = new BinaryOp("and", left, parseIn());
        }
        return left;
    }

    // Level 5: in
    private AstNode parseIn() throws ParseException {
        AstNode left = parseComparison();
        while (peek().type() == IN) {
            consume(IN);
            left = new BinaryOp("in", left, parseComparison());
        }
        return left;
    }

    // Level 6: comparison  = != < <= > >=
    private AstNode parseComparison() throws ParseException {
        AstNode left = parseConcat();
        while (isComparisonOp(peek().type())) {
            String op = peek().value().isEmpty() ? tokenTypeToOp(peek().type()) : peek().value();
            if (op.isEmpty()) op = tokenTypeToOp(peek().type());
            cursor++;
            left = new BinaryOp(op, left, parseConcat());
        }
        return left;
    }

    private static boolean isComparisonOp(TokenType t) {
        return t == EQUAL || t == NOT_EQUAL || t == LESS || t == LESS_EQUAL
                || t == GREATER || t == GREATER_EQUAL;
    }

    // Level 7: string concatenation  &
    private AstNode parseConcat() throws ParseException {
        AstNode left = parseAddSub();
        while (peek().type() == AMPERSAND) {
            consume(AMPERSAND);
            left = new BinaryOp("&", left, parseAddSub());
        }
        return left;
    }

    // Level 8: + -
    private AstNode parseAddSub() throws ParseException {
        AstNode left = parseMulDiv();
        while (peek().type() == PLUS || peek().type() == MINUS) {
            String op = peek().type() == PLUS ? "+" : "-";
            cursor++;
            left = new BinaryOp(op, left, parseMulDiv());
        }
        return left;
    }

    // Level 9: * / %
    private AstNode parseMulDiv() throws ParseException {
        AstNode left = parseUnary();
        while (peek().type() == STAR || peek().type() == SLASH || peek().type() == PERCENT) {
            String op = switch (peek().type()) {
                case STAR    -> "*";
                case SLASH   -> "/";
                case PERCENT -> "%";
                default      -> throw new AssertionError();
            };
            cursor++;
            left = new BinaryOp(op, left, parseUnary());
        }
        return left;
    }

    // Level 10: unary - and not
    private AstNode parseUnary() throws ParseException {
        if (peek().type() == MINUS) {
            consume(MINUS);
            // Negative number literal optimisation
            if (peek().type() == NUMBER) {
                double v = parseDouble(peek().value(), peek().position());
                cursor++;
                return new NumberLiteral(-v);
            }
            return new UnaryMinus(parseUnary());
        }
        if (peek().type() == NOT) {
            consume(NOT);
            // 'not' is treated as a built-in single-argument function
            consume(LPAREN);
            AstNode arg = parseExpression();
            consume(RPAREN);
            return new FunctionCall("not", List.of(arg));
        }
        return parseChain();
    }

    // Level 11: ~> function chaining
    private AstNode parseChain() throws ParseException {
        AstNode left = parsePostfix();
        if (peek().type() == TILDE_GT) {
            List<AstNode> steps = new ArrayList<>();
            steps.add(left);
            while (peek().type() == TILDE_GT) {
                consume(TILDE_GT);
                steps.add(parsePostfix());
            }
            return new ChainExpr(steps);
        }
        return left;
    }

    // Level 12: postfix — . [] ^() {} ||
    private AstNode parsePostfix() throws ParseException {
        AstNode node = parsePrimary();

        while (true) {
            if (peek().type() == DOT) {
                node = parseDotStep(node);
            } else if (peek().type() == LBRACKET) {
                node = parseSubscriptOrPredicate(node);
            } else if (peek().type() == CARET) {
                node = parseSortExpr(node);
            } else if (peek().type() == LBRACE) {
                node = parseGroupBy(node);
            } else if (peek().type() == PIPE) {
                node = parseTransform(node);
            } else {
                break;
            }
        }
        return node;
    }

    // =========================================================================
    // Postfix helpers
    // =========================================================================

    private AstNode parseDotStep(AstNode left) throws ParseException {
        consume(DOT);
        AstNode right = parsePrimary();
        // Flatten consecutive dot-steps into a single PathExpr
        List<AstNode> steps = new ArrayList<>();
        if (left instanceof PathExpr pe) {
            steps.addAll(pe.steps());
        } else {
            steps.add(left);
        }
        steps.add(right);
        return new PathExpr(steps);
    }

    private AstNode parseSubscriptOrPredicate(AstNode source) throws ParseException {
        consume(LBRACKET);
        if (peek().type() == RBRACKET) {
            // Empty [] — force-array operator: wraps the result in an array
            consume(RBRACKET);
            return new ForceArray(source);
        }
        // Range expression inside brackets: [from..to]
        AstNode inner = parseExpression();
        if (peek().type() == DOT_DOT) {
            consume(DOT_DOT);
            AstNode to = parseExpression();
            consume(RBRACKET);
            return new PredicateExpr(source, new RangeExpr(inner, to));
        }
        consume(RBRACKET);
        if (inner instanceof NumberLiteral) {
            // a.b[n] — fold the subscript into the last path step so it is
            // applied per-element when the path maps over a sequence.
            // (a.b)[n] has source=Parenthesized and is NOT folded; the subscript
            // is applied to the whole collected result instead.
            if (source instanceof PathExpr pe) {
                List<AstNode> steps = new ArrayList<>(pe.steps());
                AstNode lastStep = steps.remove(steps.size() - 1);
                steps.add(new ArraySubscript(lastStep, inner));
                return new PathExpr(steps);
            }
            return new ArraySubscript(source, inner);
        }
        return new PredicateExpr(source, inner);
    }

    private AstNode parseSortExpr(AstNode source) throws ParseException {
        consume(CARET);
        consume(LPAREN);
        List<SortKey> keys = new ArrayList<>();
        do {
            boolean descending = false;
            if (peek().type() == LESS) {
                consume(LESS);
                descending = false;
            } else if (peek().type() == GREATER) {
                consume(GREATER);
                descending = true;
            }
            keys.add(new SortKey(parseExpression(), descending));
        } while (tryConsume(COMMA));
        consume(RPAREN);
        return new SortExpr(source, keys);
    }

    private AstNode parseGroupBy(AstNode source) throws ParseException {
        List<KeyValuePair> pairs = parseObjectBody();
        return new GroupByExpr(source, pairs);
    }

    private AstNode parseTransform(AstNode source) throws ParseException {
        consume(PIPE);
        AstNode pattern = parseExpression();
        consume(PIPE);
        AstNode update = parseObjectConstructorNode();
        consume(PIPE);
        return new TransformExpr(source, pattern, update);
    }

    // =========================================================================
    // Primary expressions
    // =========================================================================

    private AstNode parsePrimary() throws ParseException {
        Token t = peek();
        return switch (t.type()) {
            case STRING         -> { cursor++; yield new StringLiteral(t.value()); }
            case NUMBER         -> { cursor++; yield new NumberLiteral(parseDouble(t.value(), t.position())); }
            case TRUE           -> { cursor++; yield new BooleanLiteral(true); }
            case FALSE          -> { cursor++; yield new BooleanLiteral(false); }
            case NULL           -> { cursor++; yield new NullLiteral(); }
            case DOLLAR_DOLLAR  -> { cursor++; yield new RootRef(); }
            case DOLLAR         -> { cursor++; yield new ContextRef(); }
            case VARIABLE       -> parseVariableOrFunctionCall();
            case IDENTIFIER     -> parseIdentifierOrFunctionCall();
            case STAR           -> { cursor++; yield new WildcardStep(); }
            case STAR_STAR      -> { cursor++; yield new DescendantStep(); }
            case LPAREN         -> parseParenthesised();
            case LBRACKET       -> parseArrayConstructor();
            case LBRACE         -> parseObjectConstructorNode();
            case QUESTION       -> { cursor++; yield new PartialPlaceholder(); }
            case MINUS          -> parseUnary();  // let unary handle it
            case NOT            -> parseUnary();
            default             -> throw new ParseException(
                    "Unexpected token: " + t, t.position());
        };
    }

    // $name or $funcName(args)
    private AstNode parseVariableOrFunctionCall() throws ParseException {
        Token t = consume(VARIABLE);
        if (peek().type() == LPAREN) {
            return parseFunctionArgs(t.value(), t.position());
        }
        return new VariableRef(t.value());
    }

    // bareIdentifier, built-in function call, or lambda (function keyword)
    private AstNode parseIdentifierOrFunctionCall() throws ParseException {
        Token t = consume(IDENTIFIER);
        // 'function' keyword (or Greek λ) introduces a lambda expression
        if (("function".equals(t.value()) || "\u03bb".equals(t.value())) && peek().type() == LPAREN) {
            return parseLambda();
        }
        if (peek().type() == LPAREN) {
            // Treat as built-in/user-defined function call
            return parseFunctionArgs(t.value(), t.position());
        }
        return new FieldRef(t.value());
    }

    private AstNode parseFunctionArgs(String name, int pos) throws ParseException {
        consume(LPAREN);
        List<AstNode> args = new ArrayList<>();
        if (peek().type() != RPAREN) {
            do {
                args.add(parseExpression());
            } while (tryConsume(COMMA));
        }
        consume(RPAREN);
        // If any argument is a PartialPlaceholder, produce a PartialApplication node.
        boolean hasPlaceholder = args.stream().anyMatch(a -> a instanceof PartialPlaceholder);
        if (hasPlaceholder) return new PartialApplication(name, args);
        return new FunctionCall(name, args);
    }

    // Parenthesised expression or block: (expr) or (expr; expr; ...)
    private AstNode parseParenthesised() throws ParseException {
        consume(LPAREN);
        // Empty parens — treat as empty block
        if (peek().type() == RPAREN) {
            consume(RPAREN);
            return new Block(List.of());
        }
        // Check for lambda:  function($params) { body }
        // also handles shorthand lambda via IDENTIFIER 'function'
        // Already handled via parsePrimary -> IDENTIFIER -> function(
        // So parenthesised is just a block or single expression.
        List<AstNode> exprs = new ArrayList<>();
        exprs.add(parseExpression());
        while (peek().type() == SEMICOLON) {
            consume(SEMICOLON);
            if (peek().type() == RPAREN) break; // trailing semicolon
            exprs.add(parseExpression());
        }
        consume(RPAREN);
        // Wrap in Parenthesized so that a following subscript [n] knows to apply
        // to the whole collected result rather than per path-step element.
        AstNode inner = exprs.size() == 1 ? exprs.get(0) : new Block(exprs);
        return new Parenthesized(inner);
    }

    // [elem, elem, ...] — range is handled inside parsePrimary via LBRACKET
    private AstNode parseArrayConstructor() throws ParseException {
        consume(LBRACKET);
        List<AstNode> elements = new ArrayList<>();
        if (peek().type() != RBRACKET) {
            AstNode first = parseExpression();
            // Range [from..to]
            if (peek().type() == DOT_DOT) {
                consume(DOT_DOT);
                AstNode to = parseExpression();
                consume(RBRACKET);
                return new RangeExpr(first, to);
            }
            elements.add(first);
            while (tryConsume(COMMA)) {
                elements.add(parseExpression());
            }
        }
        consume(RBRACKET);
        return new ArrayConstructor(elements);
    }

    // { key: value, ... }
    private AstNode parseObjectConstructorNode() throws ParseException {
        List<KeyValuePair> pairs = parseObjectBody();
        return new ObjectConstructor(pairs);
    }

    private List<KeyValuePair> parseObjectBody() throws ParseException {
        consume(LBRACE);
        List<KeyValuePair> pairs = new ArrayList<>();
        if (peek().type() != RBRACE) {
            do {
                AstNode key = parseExpression();
                consume(COLON);
                AstNode value = parseExpression();
                pairs.add(new KeyValuePair(key, value));
            } while (tryConsume(COMMA));
        }
        consume(RBRACE);
        return pairs;
    }

    // Lambda: function($x, $y) { body }
    // Called via parsePrimary -> IDENTIFIER('function') -> parseFunctionArgs which ends up in
    // FunctionCall("function", args). We need to detect the special keyword form.
    // Override: detect IDENTIFIER "function" before parseFunctionArgs.
    // Already handled: IDENTIFIER 'function' → FunctionCall("function", [params..., body]) but
    // that doesn't model it correctly. Let's handle it properly in parseIdentifierOrFunctionCall.
    private AstNode parseLambda() throws ParseException {
        consume(LPAREN);
        List<String> params = new ArrayList<>();
        if (peek().type() != RPAREN) {
            do {
                Token p = consume(VARIABLE);
                params.add(p.value());
            } while (tryConsume(COMMA));
        }
        consume(RPAREN);
        consume(LBRACE);
        AstNode body = parseExpression();
        consume(RBRACE);
        return new Lambda(params, body);
    }

    // =========================================================================
    // Token stream utilities
    // =========================================================================

    private Token peek() {
        return tokens.get(cursor);
    }

    private Token peekAt(int offset) {
        int idx = cursor + offset;
        return idx < tokens.size() ? tokens.get(idx) : tokens.get(tokens.size() - 1);
    }

    private Token consume(TokenType expected) throws ParseException {
        Token t = tokens.get(cursor);
        if (t.type() != expected) {
            throw new ParseException(
                    "Expected " + expected + " but found " + t.type() + " ('" + t.value() + "')",
                    t.position());
        }
        cursor++;
        return t;
    }

    /** Consumes the next token if it matches {@code type}; returns {@code true} if consumed. */
    private boolean tryConsume(TokenType type) {
        if (peek().type() == type) {
            cursor++;
            return true;
        }
        return false;
    }

    private static String tokenTypeToOp(TokenType t) {
        return switch (t) {
            case EQUAL         -> "=";
            case NOT_EQUAL     -> "!=";
            case LESS          -> "<";
            case LESS_EQUAL    -> "<=";
            case GREATER       -> ">";
            case GREATER_EQUAL -> ">=";
            default            -> "";
        };
    }

    private static double parseDouble(String text, int pos) throws ParseException {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid number literal: " + text, pos);
        }
    }

}
