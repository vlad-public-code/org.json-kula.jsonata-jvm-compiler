package org.json_kula.jsonata_jvm.parser;

import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;
import org.json_kula.jsonata_jvm.parser.lexer.Lexer;
import org.json_kula.jsonata_jvm.parser.lexer.Token;
import org.json_kula.jsonata_jvm.parser.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    /**
     * When {@code > 0}, the postfix {@code |} (transform-as-postfix-operator) is suppressed.
     * This prevents the separator {@code |} between pattern and update inside a transform
     * literal from being greedily consumed as a nested transform postfix operator.
     */
    private int transformPatternDepth = 0;
    /** Counter for generating unique temp variable names in lambda desugaring. */
    private int lambdaTempCounter = 0;

    /** Names of built-in functions (without $ prefix). Partial application of these via bare
     *  identifier syntax (no $) throws T1007; unknown names throw T1008. */
    private static final Set<String> BUILTIN_NAMES = Set.of(
        "string", "length", "substring", "substringBefore", "substringAfter",
        "uppercase", "lowercase", "trim", "pad", "contains", "split", "join",
        "replace", "match", "number", "abs", "floor", "ceil", "round", "sqrt",
        "power", "random", "boolean", "not", "exists", "count", "sum", "max",
        "min", "average", "reverse", "sort", "shuffle", "distinct", "append",
        "keys", "lookup", "spread", "merge", "each", "sift", "type", "map",
        "filter", "reduce", "single", "zip", "formatNumber", "parseNumber",
        "formatBase", "formatInteger", "parseInteger", "now", "millis",
        "fromMillis", "toMillis", "error", "assert", "typeOf", "eval",
        "encodeUrl", "encodeUrlComponent", "decodeUrl", "decodeUrlComponent",
        "base64encode", "base64decode"
    );

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
            if (t.type() == COLON_ASSIGN) {
                throw new ParseException("S0212: The := operator can only be used to assign to a $variable", t.position());
            }
            if (t.type() == SEMICOLON) {
                throw new ParseException("S0201: Syntax error: unexpected ';'", t.position());
            }
            if (t.type() == LPAREN) {
                // A non-function expression followed by '(...)' — detect partial-application vs call
                Token inner = parser.peekAt(1);
                if (inner.type() == QUESTION) {
                    throw new ParseException("T1008: The expression is not a function", t.position());
                }
                throw new ParseException("T1006: The expression is not a function", t.position());
            }
            throw new ParseException("S0211: Unexpected token '" + t.value() + "'", t.position());
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

    // Level 1: variable binding  $name := expr  (right-associative for chained := )
    private AstNode parseBinding() throws ParseException {
        if (peek().type() == VARIABLE && peekAt(1).type() == COLON_ASSIGN) {
            String name = consume(VARIABLE).value();
            consume(COLON_ASSIGN);
            AstNode value = parseBinding(); // right-associative: $a := $b := 5 → $a := ($b := 5)
            return new VariableBinding(name, value);
        }
        AstNode lhs = parseConditional();
        // Detect invalid assignment target like $a[1]:=3 or foo:=3
        if (peek().type() == COLON_ASSIGN) {
            throw new ParseException("S0212: The := operator can only be used to assign to a $variable", peek().position());
        }
        return lhs;
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
            } else if (peek().type() == AT && peekAt(1).type() == VARIABLE) {
                // Context variable binding: node@$var
                String varName = peekAt(1).value();
                cursor += 2; // consume AT and VARIABLE
                node = appendToPath(node, new ContextBinding(varName));
            } else if (peek().type() == HASH && peekAt(1).type() == VARIABLE) {
                // Positional variable binding: node#$var
                String varName = peekAt(1).value();
                cursor += 2; // consume HASH and VARIABLE
                node = appendToPath(node, new PositionBinding(varName));
            } else if (peek().type() == LBRACKET) {
                node = parseSubscriptOrPredicate(node);
            } else if (peek().type() == CARET) {
                node = parseSortExpr(node);
            } else if (peek().type() == LBRACE) {
                node = parseGroupBy(node);
            } else if (peek().type() == PIPE && transformPatternDepth == 0) {
                node = parseTransform(node);
            } else {
                break;
            }
        }
        return node;
    }

    /**
     * Appends {@code step} to {@code node} by extending or creating a {@link PathExpr}.
     * If {@code node} is already a {@link PathExpr}, the step is added to its list.
     * Otherwise, a new two-element {@link PathExpr} is created.
     */
    private static AstNode appendToPath(AstNode node, AstNode step) {
        List<AstNode> steps = new ArrayList<>();
        if (node instanceof PathExpr pe) {
            steps.addAll(pe.steps());
        } else {
            steps.add(node);
        }
        steps.add(step);
        return new PathExpr(steps);
    }

    // =========================================================================
    // Postfix helpers
    // =========================================================================

    private AstNode parseDotStep(AstNode left) throws ParseException {
        consume(DOT);
        // % after a dot means "parent step"
        AstNode right;
        if (peek().type() == PERCENT) {
            cursor++;
            right = new ParentStep();
        } else if (peek().type() == STRING) {
            // Quoted string after dot is a field name (e.g. Other."Alternative.Address")
            Token t = consume(STRING);
            right = new FieldRef(t.value());
        } else if (peek().type() == NUMBER) {
            // A number literal after '.' is not a valid path step — S0213
            Token t = peek();
            throw new ParseException("S0213: The expression on the right side of the '.' operator must be a name or a wildcard, not a number", t.position());
        } else {
            right = parsePrimary();
        }
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
        if (source instanceof GroupByExpr) {
            Token t = peek();
            throw new ParseException("S0209: A predicate cannot be applied to a group-by expression", t.position());
        }
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
        // Non-numeric predicate: for positional-binding or context-binding paths
        // (ending in #$var or @$var), fold the predicate as a path step so that
        // $i / $var is in scope during filtering.
        if (source instanceof PathExpr pe) {
            List<AstNode> pathSteps = pe.steps();
            AstNode lastStep = pathSteps.get(pathSteps.size() - 1);
            if (lastStep instanceof PositionBinding || lastStep instanceof ContextBinding) {
                // Fold as a PredicateExpr path step so the binding is traversed first
                List<AstNode> steps = new ArrayList<>(pathSteps);
                steps.add(new PredicateExpr(new ContextRef(), inner));
                return new PathExpr(steps);
            }
        }
        return new PredicateExpr(source, inner);
    }

    private AstNode parseSortExpr(AstNode source) throws ParseException {
        consume(CARET);
        consume(LPAREN);
        List<SortKey> keys = new ArrayList<>();
        do {
            boolean descending = false;  // Default: ascending sort
            if (peek().type() == LESS) {
                consume(LESS);
                descending = false;   // '<' prefix for ascending sort (descending=false)
            } else if (peek().type() == GREATER) {
                consume(GREATER);
                descending = true;    // '>' prefix for descending sort
            }
            keys.add(new SortKey(parseExpression(), descending));
        } while (tryConsume(COMMA));
        consume(RPAREN);
        return new SortExpr(source, keys);
    }

    private AstNode parseGroupBy(AstNode source) throws ParseException {
        if (source instanceof GroupByExpr) {
            Token t = peek();
            throw new ParseException("S0210: Each group-by clause can only contain one expression", t.position());
        }
        List<KeyValuePair> pairs = parseObjectBody();
        return new GroupByExpr(source, pairs);
    }

    private AstNode parseTransform(AstNode source) throws ParseException {
        consume(PIPE);
        // Suppress postfix-| inside the transform body so that the separator '|'
        // is not greedily consumed as a nested transform postfix operator.
        transformPatternDepth++;
        AstNode pattern = parseExpression();
        consume(PIPE);
        AstNode update = parseObjectConstructorNode();
        AstNode delete = null;
        if (tryConsume(COMMA)) {
            delete = parseExpression();
        }
        transformPatternDepth--;
        consume(PIPE);
        return new TransformExpr(source, pattern, update, delete);
    }

    /** Standalone transform literal: {@code | pattern | update [, delete] |}. */
    private AstNode parseTransformLambda() throws ParseException {
        consume(PIPE);
        // Suppress postfix-| inside the transform body so that the separator '|'
        // is not greedily consumed as a nested transform postfix operator.
        transformPatternDepth++;
        AstNode pattern = parseExpression();
        consume(PIPE);
        AstNode update = parseObjectConstructorNode();
        AstNode delete = null;
        if (tryConsume(COMMA)) {
            delete = parseExpression();
        }
        transformPatternDepth--;
        consume(PIPE);
        return new TransformLambda(pattern, update, delete);
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
            case REGEX          -> {
                cursor++;
                int sep = t.value().lastIndexOf('/');
                yield new RegexLiteral(t.value().substring(0, sep),
                                       t.value().substring(sep + 1));
            }
            case DOLLAR_DOLLAR  -> { cursor++; yield new RootRef(); }
            case DOLLAR         -> { cursor++; yield new ContextRef(); }
            case VARIABLE       -> parseVariableOrFunctionCall();
            case IDENTIFIER     -> parseIdentifierOrFunctionCall();
            case AND            -> { cursor++; yield new FieldRef(t.value()); }
            case OR             -> { cursor++; yield new FieldRef(t.value()); }
            case IN             -> { cursor++; yield new FieldRef(t.value()); }
            case STAR           -> { cursor++; yield new WildcardStep(); }
            case STAR_STAR      -> { cursor++; yield new DescendantStep(); }
            case PERCENT        -> { cursor++; yield new ParentStep(); }
            case LPAREN         -> parseParenthesised();
            case LBRACKET       -> parseArrayConstructor();
            case LBRACE         -> parseObjectConstructorNode();
            case PIPE           -> parseTransformLambda();
            case QUESTION       -> {
                cursor++;
                // '?' followed by '(' is a lambda shorthand (same as 'function'/λ)
                if (peek().type() == LPAREN) {
                    AstNode lambda = parseLambda();
                    if (peek().type() == LPAREN) {
                        yield desugarImmediateLambdaCall(lambda);
                    }
                    yield lambda;
                }
                yield new PartialPlaceholder();
            }
            case MINUS          -> parseUnary();  // let unary handle it
            case NOT            -> parseUnary();
            case EOF            -> throw new ParseException(
                    "S0207: Unexpected end of expression", t.position());
            case ERROR          -> throw new ParseException(t.value(), t.position());
            default             -> throw new ParseException(
                    "S0211: Unexpected token '" + t.value() + "'", t.position());
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
            AstNode lambda = parseLambda();
            // Immediate invocation: function($x){body}(args) — desugar to ($__ln:=lambda; $__ln(args))
            if (peek().type() == LPAREN) {
                return desugarImmediateLambdaCall(lambda);
            }
            return lambda;
        }
        if (peek().type() == LPAREN) {
            AstNode call = parseFunctionArgs(t.value(), t.position());
            if (call instanceof PartialApplication) {
                // Bare identifier partial application is invalid.
                // T1007 if name matches a known built-in; T1008 otherwise.
                if (BUILTIN_NAMES.contains(t.value())) {
                    throw new ParseException("T1007: Attempted to partially apply a built-in function '"
                            + t.value() + "'", t.position());
                }
                throw new ParseException("T1008: The expression is not a function", t.position());
            }
            // Regular call of a built-in function without $ prefix - throw T1005
            if (BUILTIN_NAMES.contains(t.value())) {
                throw new ParseException("T1005: Attempted to invoke a non-function. Did you mean $" 
                        + t.value() + "?", t.position());
            }
            return call;
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

    // [elem, elem, ...] — supports ranges and multi-range like [1..3, 7..9]
    private AstNode parseArrayConstructor() throws ParseException {
        consume(LBRACKET);
        List<AstNode> elements = new ArrayList<>();
        if (peek().type() != RBRACKET) {
            AstNode first = parseExpression();
            // Range element: from..to
            if (peek().type() == DOT_DOT) {
                consume(DOT_DOT);
                AstNode to = parseExpression();
                elements.add(new RangeExpr(first, to));
            } else {
                elements.add(first);
            }
            while (tryConsume(COMMA)) {
                AstNode elem = parseExpression();
                if (peek().type() == DOT_DOT) {
                    consume(DOT_DOT);
                    AstNode to = parseExpression();
                    elements.add(new RangeExpr(elem, to));
                } else {
                    elements.add(elem);
                }
            }
        }
        consume(RBRACKET);
        // Unwrap single range to preserve backward-compatible RangeExpr node
        if (elements.size() == 1 && elements.get(0) instanceof RangeExpr r) {
            return r;
        }
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
                Token p = peek();
                if (p.type() != VARIABLE) {
                    throw new ParseException(
                            "S0208: Lambda parameter '" + p.value() + "' must be a $variable", p.position());
                }
                cursor++;
                params.add(p.value());
            } while (tryConsume(COMMA));
        }
        consume(RPAREN);
        // Parse optional type-signature: <...> — e.g. function($x,$y)<n-n:n>{body}
        String signature = null;
        if (peek().type() == LESS) {
            signature = readTypeSignature();
        }
        // Extra '>' after signature is S0402
        if (peek().type() == GREATER) {
            throw new ParseException("S0402: Invalid type signature: unexpected '>' after signature", peek().position());
        }
        consume(LBRACE);
        AstNode body = parseExpression();
        consume(RBRACE);
        return new Lambda(params, body, signature);
    }

    /**
     * Desugars an immediately-invoked lambda literal {@code function($x){body}(args)} to
     * {@code ($__ln_N := function($x){body}; $__ln_N(args))}.
     */
    private AstNode desugarImmediateLambdaCall(AstNode lambda) throws ParseException {
        consume(LPAREN);
        List<AstNode> args = new ArrayList<>();
        if (peek().type() != RPAREN) {
            do {
                args.add(parseExpression());
            } while (tryConsume(COMMA));
        }
        consume(RPAREN);
        // If lambda has a signature, use LambdaCall so translator can apply
        // type-checking and context binding. Otherwise use the old desugared form.
        if (lambda instanceof Lambda lam && lam.signature() != null) {
            return new LambdaCall(lam, args);
        }
        String tmpName = "__ln_" + lambdaTempCounter++;
        return new Parenthesized(new Block(List.of(
                new VariableBinding(tmpName, lambda),
                new FunctionCall(tmpName, args)
        )));
    }

    /** Skips a type-signature {@code <...>} (possibly nested, e.g. {@code <a<n>>}). */
    private void skipTypeSignature() {
        cursor++; // consume '<'
        int depth = 1;
        while (cursor < tokens.size() && depth > 0) {
            TokenType tt = tokens.get(cursor).type();
            if (tt == LESS) depth++;
            else if (tt == GREATER) depth--;
            cursor++;
        }
    }

    /**
     * Reads and returns a type-signature string {@code <...>}, validating basic rules.
     * Throws S0401 for invalid type specs (e.g. {@code n<n>}) and S0402 for invalid
     * union types (e.g. {@code (sa<n>)} which has a typed array in a union without
     * the outer close {@code >} in the right place).
     */
    private String readTypeSignature() throws ParseException {
        int startCursor = cursor;
        int startPos = peek().position();
        cursor++; // consume '<'
        StringBuilder sb = new StringBuilder("<");
        int depth = 1;
        while (cursor < tokens.size() && depth > 0) {
            Token tok = tokens.get(cursor);
            TokenType tt = tok.type();
            sb.append(tok.value().isEmpty() ? tokenTypeToSigChar(tt) : tok.value());
            if (tt == LESS) depth++;
            else if (tt == GREATER) {
                depth--;
                if (depth == 0) break;
            }
            cursor++;
        }
        cursor++; // consume closing '>'
        String sig = sb.toString();
        // Validate: detect n<n> (parametrized non-array type) → S0401
        if (sig.matches(".*[bnslu]<.*")) {
            throw new ParseException("S0401: Invalid type specification in signature: '" + sig + "'", startPos);
        }
        return sig;
    }

    private static String tokenTypeToSigChar(TokenType tt) {
        return switch (tt) {
            case LESS -> "<";
            case GREATER -> ">";
            case LPAREN -> "(";
            case RPAREN -> ")";
            case COLON -> ":";
            case PLUS -> "+";
            case QUESTION -> "?";
            case MINUS -> "-";
            default -> "";
        };
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
            if (t.type() == org.json_kula.jsonata_jvm.parser.lexer.TokenType.EOF)
                throw new ParseException(
                        "S0203: Expected " + expected + " but reached end of expression",
                        t.position());
            throw new ParseException(
                    "S0202: Expected " + expected + " but found " + t.type() + " ('" + t.value() + "')",
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
