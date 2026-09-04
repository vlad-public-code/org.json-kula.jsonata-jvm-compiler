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
        "base64encode", "base64decode", "clone"
    );

    /**
     * Returns {@code true} if {@code name} (without the leading {@code $}) is a JSONata built-in.
     * Used by callers that need to tell a reference to the standard library from a reference to
     * something the expression is expected to provide.
     */
    public static boolean isBuiltin(String name) {
        return BUILTIN_NAMES.contains(name);
    }

    /**
     * Set for exactly one {@link #parsePrimary()} call: the step immediately after a
     * {@code .}. There, a bare {@code name(...)} is a call of the step context's
     * <em>field</em> {@code name}, not of a variable or a built-in.
     *
     * <p>One-shot on purpose. An expression nested inside the step's own arguments —
     * {@code $o.g($count(x))} — must not inherit it, or the built-in call in the
     * argument would be resolved as a field too.
     */
    private boolean nextPrimaryIsDotStep;

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
                throw new ParseException("S0212", "The := operator can only be used to assign to a $variable", t.position());
            }
            if (t.type() == SEMICOLON) {
                throw new ParseException("S0201", "Syntax error: unexpected ';'", t.position());
            }
            if (t.type() == LPAREN) {
                // A non-function expression followed by '(...)' — detect partial-application vs call
                Token inner = parser.peekAt(1);
                if (inner.type() == QUESTION) {
                    throw new ParseException("T1008", "The expression is not a function", t.position());
                }
                throw new ParseException("T1006", "The expression is not a function", t.position());
            }
            throw new ParseException("S0211", "Unexpected token '" + t.value() + "'", t.position());
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
            throw new ParseException("S0212", "The := operator can only be used to assign to a $variable", peek().position());
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

        // A `[]` seen on a node whose result is not a sequence. It is not discarded — the mark
        // lives on the node and is read by the next thing that does produce one — but if the
        // postfix chain ends first, it never fires.
        boolean pendingKeepArray = false;

        while (true) {
            if (peek().type() == DOT) {
                // A bare number, boolean or null literal cannot head a path step. A
                // *parenthesised* one can — `(5).g()` is T1006, not S0213 — and a quoted
                // string is a field name, so neither is rejected here.
                //
                // A `[]` or `[n]` written after the literal does not change what the step is:
                // the reference flags `[` as keepArray / a stage on the step and leaves the
                // step itself a number, so `1[].$` and `1[0].$` are S0213 too.
                if (isLiteralStep(node)) {
                    Token t = peek();
                    throw new ParseException("S0213",
                            "The literal value cannot be used as a step within a path expression",
                            t.position());
                }
                node = parseDotStep(node);
                if (pendingKeepArray) { pendingKeepArray = false; if (producesSequence(node)) node = new ForceArray(node); }
            } else if (peek().type() == AT && peekAt(1).type() == VARIABLE) {
                // Context variable binding: node@$var
                // S0215: @$var cannot follow a predicate/subscript step
                if (endsWithPredicateOrSubscript(node)) {
                    Token t = peek();
                    throw new ParseException("S0215", "A context variable binding must not be applied after a predicate step", t.position());
                }
                // S0216: @$var cannot follow a sort expression
                if (node instanceof SortExpr) {
                    Token t = peek();
                    throw new ParseException("S0216", "A context variable binding cannot follow a sort expression", t.position());
                }
                String varName = peekAt(1).value();
                cursor += 2; // consume AT and VARIABLE
                node = appendToPath(node, new ContextBinding(varName));
            } else if (peek().type() == AT) {
                // AT not followed by $var — must use a variable name (S0214)
                Token t = peek();
                throw new ParseException("S0214", "The operand of the '@' operator must be a variable name ($var)", t.position());
            } else if (peek().type() == HASH && peekAt(1).type() == VARIABLE) {
                // Positional variable binding: node#$var
                String varName = peekAt(1).value();
                cursor += 2; // consume HASH and VARIABLE
                node = appendToPath(node, new PositionBinding(varName));
            } else if (peek().type() == HASH) {
                // HASH not followed by $var — must use a variable name (S0214)
                Token t = peek();
                throw new ParseException("S0214", "The operand of the '#' operator must be a variable name ($var)", t.position());
            } else if (peek().type() == LBRACKET && peekAt(1).type() == RBRACKET) {
                // `[]` marks the node it is written on; the reference reads that mark only where
                // the node's own result is a sequence. On a path that is the path's result, so it
                // applies at once. On anything else it applies to whatever comes next and makes a
                // sequence — a `[...]` stage, or a `.step` that turns the node into a path — and
                // if nothing does, `[]` does nothing at all: `1[]` is 1 but `1[][0]` is [1].
                cursor += 2;
                if (producesSequence(node)) node = new ForceArray(node);
                else pendingKeepArray = true;
            } else if (peek().type() == LBRACKET) {
                node = parseSubscriptOrPredicate(node);
                if (pendingKeepArray) { pendingKeepArray = false; if (producesSequence(node)) node = new ForceArray(node); }
            } else if (peek().type() == CARET) {
                node = parseSortExpr(node);
                if (pendingKeepArray) { pendingKeepArray = false; if (producesSequence(node)) node = new ForceArray(node); }
            } else if (peek().type() == LBRACE) {
                node = parseGroupBy(node);
            } else if (peek().type() == PIPE && transformPatternDepth == 0) {
                node = parseTransform(node);
            } else if (peek().type() == LPAREN) {
                // Chained function application: expr(args) where expr evaluates to a lambda.
                // Desugar to ($__callN := callee; $__callN(args)) so the translator can
                // apply the result of any expression as a function.
                node = desugarCallExpr(node);
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
    /** Returns true if the node's last path step is a predicate or numeric subscript. */
    private static boolean endsWithPredicateOrSubscript(AstNode node) {
        AstNode last = node;
        if (node instanceof PathExpr pe && !pe.steps().isEmpty()) {
            last = pe.steps().get(pe.steps().size() - 1);
        }
        return last instanceof PredicateExpr || last instanceof ArraySubscript;
    }

    private static AstNode appendToPath(AstNode node, AstNode step) {
        List<AstNode> steps = new ArrayList<>();
        if (node instanceof PathExpr pe) {
            steps.addAll(pe.steps());
        } else {
            steps.add(node);
        }
        steps.add(step);
        return newPath(steps);
    }

    // =========================================================================
    // Postfix helpers
    // =========================================================================


    /**
     * Builds a {@link PathExpr}, flagging a leading array constructor.
     *
     * <p>Mirrors the reference's {@code firststep.consarray = true}: a {@code [...]}
     * written as the first step of a path is evaluated as a value rather than iterated,
     * and an empty one ends the path there. The distinction is purely syntactic —
     * {@code [].x} and {@code ([]).x} differ — and it has to be recorded here, because by
     * the time the optimizer has unwrapped the {@code Parenthesized} nothing else tells
     * them apart.
     *
     * <p>A sort applied to the head ({@code []^(x).y}) is folded into the head node here
     * rather than being a separate step as it is in the reference, so the constructor
     * inside it is flagged too. Sorting an empty array is still empty, so the short-circuit
     * lands on the same result.
     */
    private static AstNode newPath(List<AstNode> steps) {
        if (steps.size() > 1) {
            AstNode head = steps.get(0);
            if (head instanceof ArrayConstructor ac && !ac.pathHead()) {
                steps = new ArrayList<>(steps);
                steps.set(0, new ArrayConstructor(ac.elements(), true));
            } else if (flagStagedHead(head) instanceof AstNode staged) {
                // A `[...]` stage does not change what the step is, so a constructor carrying one
                // is still the flagged head: `[1,2][0].$` short-circuits just as `[1,2].$` does,
                // and hands the stage's value — not an array — to the steps after it.
                steps = new ArrayList<>(steps);
                steps.set(0, staged);
            } else if (head instanceof SortExpr se
                    && se.source() instanceof ArrayConstructor inner && !inner.pathHead()) {
                steps = new ArrayList<>(steps);
                steps.set(0, new SortExpr(new ArrayConstructor(inner.elements(), true), se.keys()));
            }
        }
        return new PathExpr(steps);
    }

    /**
     * Whether {@code node} is one of the shapes the reference's {@code processAST} gives
     * {@code type: 'path'} — the distinction that decides where a postfix operator lands.
     *
     * <p>Written after `X`, a `[...]`, `^(…)` or `{…}` attaches to `X`'s last <em>step</em>
     * when X is a path, and to the node itself when it is not. The same test decides whether
     * a trailing {@code []} has anything to keep (a non-path result is never a sequence).
     *
     * <p>A {@code ^(…)} sort always yields a path — the reference wraps a non-path source in
     * one — while a predicate or subscript is a path only when <em>its</em> source is.
     */
    /**
     * Whether {@code node} is a literal that cannot be a path step — a number, or one of
     * {@code true}/{@code false}/{@code null}. The reference rejects these in <em>any</em>
     * position of a multi-step path, not just after a dot:
     * {@code result.steps.filter(step => step.type === 'number' || step.type === 'value')}.
     *
     * <p>A {@code []} or {@code [n]} written after one does not change what the step is, so it
     * is looked through. A quoted string is excluded: it names a field ({@code "nums".$} is the
     * field {@code nums}). So is a parenthesised literal — {@code (5).g()} is T1006, not S0213 —
     * because the parentheses make it a block, and constant folding legitimately turns
     * {@code a.(1+1)} into one.
     */
    private static boolean isLiteralStep(AstNode node) {
        return switch (node) {
            case NumberLiteral ignored  -> true;
            case BooleanLiteral ignored -> true;
            case NullLiteral ignored    -> true;
            case ForceArray fa          -> isLiteralStep(fa.source());
            case ArraySubscript as      -> isLiteralStep(as.source());
            case PredicateExpr pe       -> isLiteralStep(pe.source());
            default                     -> false;
        };
    }

    /**
     * The built-ins whose result the reference builds with {@code createSequence}, and so the only
     * calls a trailing {@code []} has anything to keep from.
     *
     * <p>Everything else returns a plain array, an object or a scalar. Two that look like they
     * belong and do not: {@code $append} concatenates, which yields a plain array and returns one
     * argument verbatim when the other is absent ({@code $append(1, nope)[]} is 1); and
     * {@code $eval} returns whatever the evaluated expression returned ({@code $eval("1")[]} is 1).
     *
     * <p>{@code $lookup} is here but is not syntactic: it builds a sequence only for an
     * <em>array</em> input, so {@code $lookup(one,"x")[]} is [1] while {@code $lookup(a,"b")[]} is
     * 1. The translator gives it its own keepArray-aware entry point.
     */
    private static final java.util.Set<String> SEQUENCE_BUILTINS = java.util.Set.of(
            "map", "filter", "keys", "spread", "each", "match", "distinct", "lookup");

    /**
     * Whether a trailing {@code []} has anything to keep.
     *
     * <p>{@code keepArray} is only ever read inside {@code evaluate}'s {@code isSequence(result)}
     * branch, so {@code []} after anything whose result is not a sequence does nothing whatsoever:
     * {@code 1[]} is 1, {@code {}[]} is {}, {@code (a.b)[]} is 1 where {@code a.b[]} is [1], and
     * {@code $sum(nums)[]} is 6. Wrapping those in an array was the single largest divergence
     * family in a {@code []}-suffix sweep.
     *
     * <p>The decision is syntactic and has to be taken here: the optimizer strips the
     * {@code Parenthesized} that separates {@code (a.b)[]} from {@code a.b[]}.
     */
    private static boolean producesSequence(AstNode node) {
        if (node instanceof FunctionCall fc) return SEQUENCE_BUILTINS.contains(fc.name());
        // A predicate or subscript stage always builds its result with createSequence, whatever it
        // ran over — so `1[0][]` is [1] and `$sum(nums)[0][]` is [6], not the bare value.
        if (node instanceof PredicateExpr || node instanceof ArraySubscript) return true;
        // `a ~> $f()` carries keepArray on the apply node, whose result is whatever the last
        // stage returned — so it inherits that stage's answer.
        if (node instanceof ChainExpr ce && !ce.steps().isEmpty()) {
            return producesSequence(ce.steps().get(ce.steps().size() - 1));
        }
        // `@$v` hangs the focus off whatever the left side already was and never wraps it into a
        // path, so `$@$e` is still a variable and its `[]` does nothing. `#$i` does wrap, which is
        // why `$#$i[]` keeps its array. Here both are steps, so the bindings are looked through.
        if (node instanceof PathExpr pe) {
            List<AstNode> steps = pe.steps();
            int i = steps.size() - 1;
            while (i > 0 && steps.get(i) instanceof ContextBinding) i--;
            return i > 0 || producesSequence(steps.get(0));
        }
        return isPathLike(node);
    }

    /** Whether any step binds a context ({@code @$v}) or a position ({@code #$v}). */
    private static boolean hasBindingStep(List<AstNode> steps) {
        for (AstNode step : steps) {
            if (step instanceof ContextBinding || step instanceof PositionBinding) return true;
        }
        return false;
    }

    static boolean isPathLike(AstNode node) {
        return switch (node) {
            case PathExpr ignored       -> true;
            case FieldRef ignored       -> true;
            case WildcardStep ignored   -> true;
            case DescendantStep ignored -> true;
            case ParentStep ignored     -> true;
            case SortExpr ignored       -> true;
            case PredicateExpr pe       -> isPathLike(pe.source());
            case ArraySubscript as      -> isPathLike(as.source());
            case ForceArray fa          -> isPathLike(fa.source());
            default                     -> false;
        };
    }

    /*
     * Why the three postfix productions above push through a GroupByExpr:
     *
     * The reference records a group-by as a `group` property on the path node, not as a wrapper,
     * so a `[...]`, `^(…)` or `.step` written after it lands on the path's last step and runs
     * BEFORE the grouping — `objs{"k":x}[0]` groups `objs[0]`, not `objs{"k":x}` indexed. Here a
     * group-by is a node wrapping its source, so the equivalent is to push the operator through.
     *
     * S0209 is therefore not "a predicate after a group-by". It fires only when the group-by's
     * source was NOT a path, because then there is no step for the operator to land on.
     */

    /**
     * Rebuilds {@code step} with its innermost array constructor flagged as a path head, when the
     * step is a constructor carrying one or more {@code [...]} stages. Returns {@code null} when
     * it is not, or when the flag is already set.
     */
    private static AstNode flagStagedHead(AstNode step) {
        if (step instanceof ArrayConstructor ac) {
            return ac.pathHead() ? null : new ArrayConstructor(ac.elements(), true);
        }
        if (step instanceof PredicateExpr pe) {
            AstNode inner = flagStagedHead(pe.source());
            return inner == null ? null : new PredicateExpr(inner, pe.predicate(), pe.stage());
        }
        if (step instanceof ArraySubscript as) {
            AstNode inner = flagStagedHead(as.source());
            return inner == null ? null : new ArraySubscript(inner, as.index());
        }
        return null;
    }

    private AstNode parseDotStep(AstNode left) throws ParseException {
        if (left instanceof GroupByExpr gbe && isPathLike(gbe.source())) {
            return new GroupByExpr(parseDotStep(gbe.source()), gbe.pairs());
        }
        // A `[]` between two steps belongs to the step it was written on, not between two paths:
        // the reference flags keepArray there and flags keepSingletonArray on the path as a whole.
        // Keeping the marker outside a *nested* path instead hides the earlier steps from the one
        // being added, which is how `o.p[].%` lost the parent it should reach.
        if (left instanceof ForceArray fa && fa.source() instanceof PathExpr) {
            return new ForceArray(parseDotStep(fa.source()));
        }
        consume(DOT);
        // % after a dot means "parent step"
        AstNode right;
        if (peek().type() == PERCENT) {
            cursor++;
            right = new ParentStep();
        } else if (peek().type() == STRING) {
            // Quoted string after dot is a field name (e.g. Other."Alternative.Address").
            // Followed by '(' it is not a field call, though: the reference treats the
            // quoted text as a string literal being invoked, which is T1006 at runtime.
            Token t = consume(STRING);
            // Followed by '(' it is not a field call: the reference invokes the quoted
            // text as a string *literal*, which is T1006 at runtime. Only a bare
            // identifier step names a field to call.
            right = peek().type() == LPAREN
                    ? desugarCallExpr(new StringLiteral(t.value()))
                    : new FieldRef(t.value());
        } else if (peek().type() == NUMBER) {
            // A number literal after '.' is not a valid path step — S0213
            Token t = peek();
            throw new ParseException("S0213", "The expression on the right side of the '.' operator must be a name or a wildcard, not a number", t.position());
        } else {
            nextPrimaryIsDotStep = true;
            try {
                right = parsePrimary();
            } finally {
                nextPrimaryIsDotStep = false;
            }
        }
        // `a.true` and `a.null` are S0213 for the same reason `a.1` is: the reference rejects a
        // `value`-typed step as well as a `number`-typed one. (`a.(1)` stays legal — that is a
        // block, not a literal step.)
        if (isLiteralStep(right)) {
            throw new ParseException("S0213",
                    "The literal value cannot be used as a step within a path expression",
                    peek().position());
        }

        // Flatten consecutive dot-steps into a single PathExpr
        List<AstNode> steps = new ArrayList<>();
        if (left instanceof PathExpr pe) {
            steps.addAll(pe.steps());
        } else {
            steps.add(left);
        }
        steps.add(right);
        return newPath(steps);
    }

    private AstNode parseSubscriptOrPredicate(AstNode source) throws ParseException {
        if (source instanceof GroupByExpr gbe) {
            if (!isPathLike(gbe.source())) {
                Token t = peek();
                throw new ParseException("S0209", "A predicate cannot be applied to a group-by expression", t.position());
            }
            return new GroupByExpr(parseSubscriptOrPredicate(gbe.source()), gbe.pairs());
        }
        consume(LBRACKET);
        if (peek().type() == RBRACKET) {
            // Empty [] — the keep-array operator. parsePostfix handles the ordinary route,
            // including the deferred case; this is the group-by push-through calling in.
            consume(RBRACKET);
            return producesSequence(source) ? new ForceArray(source) : source;
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
        // A `[]` between the step and its stage does not separate them: the reference flags
        // keepArray on the step and still folds the following `[...]` onto that same step, so
        // `o.p[][0]` indexes each o's p array rather than the collected sequence. Fold through
        // the marker and put it back outside.
        if (source instanceof ForceArray fa) {
            AstNode folded = foldStage(fa.source(), inner);
            if (folded != null) return new ForceArray(folded);
        }
        AstNode folded = foldStage(source, inner);
        if (folded != null) return folded;
        // a.b.c[pred][n] — fold when source is a PredicateExpr on a PathExpr, so [n] is applied
        // per-element (per-b) rather than on the globally collected result. Reachable only for a
        // path shape foldStage declined.
        if (inner instanceof NumberLiteral
                && source instanceof PredicateExpr pe2 && pe2.source() instanceof PathExpr pp) {
            List<AstNode> steps = new ArrayList<>(pp.steps());
            AstNode last = steps.remove(steps.size() - 1);
            steps.add(new ArraySubscript(new PredicateExpr(last, pe2.predicate()), inner));
            return newPath(steps);
        }
        return inner instanceof NumberLiteral ? new ArraySubscript(source, inner)
                                              : new PredicateExpr(source, inner);
    }

    /**
     * Folds a {@code [inner]} onto the last step of {@code source}, when {@code source} is a path.
     *
     * <p>The reference's {@code [} production makes every {@code [...]} over a path a {@code stages}
     * entry on {@code steps[last]}, and only a whole-value {@code predicate} when the left side is
     * not a path ({@code $employees[cond]}, {@code (expr)[cond]}). A stage runs inside the
     * per-input-item loop, on that item's own step result, which is why {@code (a.b)[n]} — a
     * parenthesised source, so not folded — indexes the collected sequence instead.
     *
     * <p>Two paths decline the fold. One ending in a binding takes the predicate as a following
     * step so that {@code $i} / {@code $var} is in scope while it runs. And a path containing any
     * binding is a tuple stream, where evaluateTupleStep expands everything before running its
     * stages — so the predicate really does apply to the whole stream, which is what an unfolded
     * PredicateExpr already compiles to. A literal subscript over a tuple stream is the
     * exception: it keeps folding, because the cross-join subscript hoisting in
     * {@code PathCodeGen.visitPathExpr} recognises it by that shape.
     *
     * @return the rewritten path, or {@code null} if {@code source} is not a foldable path
     */
    private static AstNode foldStage(AstNode source, AstNode inner) {
        if (!(source instanceof PathExpr pe)) return null;
        List<AstNode> pathSteps = pe.steps();
        AstNode lastStep = pathSteps.get(pathSteps.size() - 1);
        if (lastStep instanceof PositionBinding || lastStep instanceof ContextBinding) {
            List<AstNode> steps = new ArrayList<>(pathSteps);
            steps.add(new PredicateExpr(new ContextRef(), inner));
            return newPath(steps);
        }
        if (hasBindingStep(pathSteps) && !(inner instanceof NumberLiteral)) return null;
        List<AstNode> steps = new ArrayList<>(pathSteps);
        steps.set(steps.size() - 1, inner instanceof NumberLiteral
                ? new ArraySubscript(lastStep, inner)
                : new PredicateExpr(lastStep, inner, true));
        return newPath(steps);
    }

    private AstNode parseSortExpr(AstNode source) throws ParseException {
        if (source instanceof GroupByExpr gbe && isPathLike(gbe.source())) {
            return new GroupByExpr(parseSortExpr(gbe.source()), gbe.pairs());
        }
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
            throw new ParseException("S0210", "Each group-by clause can only contain one expression", t.position());
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
        // Update may be any expression; T2011 is thrown at runtime if it's not an object.
        AstNode update = parseExpression();
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
        // Update may be any expression; T2011 is thrown at runtime if it's not an object.
        AstNode update = parseExpression();
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
                    "S0207", "Unexpected end of expression", t.position());
            case ERROR          -> throw ParseException.withErrorCodeFromMessage(t.value(), t.position());
            default             -> throw new ParseException(
                    "S0211", "Unexpected token '" + t.value() + "'", t.position());
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
        // Consumed here, before anything else is parsed, so it applies to this name only.
        boolean isDotStep = nextPrimaryIsDotStep;
        nextPrimaryIsDotStep = false;
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
            if (isDotStep) {
                // `a.g(...)` calls the FIELD g of the step context. A built-in name is a
                // field here like any other, so no T1005 — `$o.count()` is $o's own
                // `count`, and only a *missing* field reports T1005 to suggest $count.
                List<AstNode> args = parseCallArgs();
                return new FunctionCall(t.value(), args, false);
            }
            AstNode call = parseFunctionArgs(t.value(), t.position());
            if (call instanceof PartialApplication) {
                // Bare identifier partial application is invalid.
                // T1007 if name matches a known built-in; T1008 otherwise.
                if (BUILTIN_NAMES.contains(t.value())) {
                    throw new ParseException("T1007", "Attempted to partially apply a built-in function '"
                            + t.value() + "'", t.position());
                }
                throw new ParseException("T1008", "The expression is not a function", t.position());
            }
            // A built-in called without its `$`. The reference raises T1005 only if
            // control actually reaches the call — `false ? count([1,2]) : 1` evaluates to
            // 1 — so this is deferred to evaluation rather than rejected at compile time.
            if (BUILTIN_NAMES.contains(t.value())) {
                return new DeferredError("T1005",
                        "Attempted to invoke a non-function. Did you mean $" + t.value() + "?");
            }
            return call;
        }
        return new FieldRef(t.value());
    }

    /** Parses a parenthesised argument list, leaving the caller to build the node. */
    private List<AstNode> parseCallArgs() throws ParseException {
        consume(LPAREN);
        List<AstNode> args = new ArrayList<>();
        if (peek().type() != RPAREN) {
            do {
                args.add(parseExpression());
            } while (tryConsume(COMMA));
        }
        consume(RPAREN);
        return args;
    }

    private AstNode parseFunctionArgs(String name, int pos) throws ParseException {
        List<AstNode> args = parseCallArgs();
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
                            "S0208", "Lambda parameter '" + p.value() + "' must be a $variable", p.position());
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
            throw new ParseException("S0402", "Invalid type signature: unexpected '>' after signature", peek().position());
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

    /**
     * Desugars a chained call {@code expr(args)} (where {@code expr} is already parsed)
     * to {@code ($__callN := expr; $__callN(args))}.
     * Used in {@code parsePostfix} to support calling the result of any expression as a function,
     * e.g. {@code $g($g)($a)} or {@code λ($f){…}(arg1)(arg2)}.
     */
    private AstNode desugarCallExpr(AstNode callee) throws ParseException {
        consume(LPAREN);
        List<AstNode> args = new ArrayList<>();
        if (peek().type() != RPAREN) {
            do {
                args.add(parseExpression());
            } while (tryConsume(COMMA));
        }
        consume(RPAREN);
        String tmpName = "__call_" + lambdaTempCounter++;
        return new Parenthesized(new Block(List.of(
                new VariableBinding(tmpName, callee),
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
            throw new ParseException("S0401", "Invalid type specification in signature: '" + sig + "'", startPos);
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
                        "S0203", "Expected " + expected + " but reached end of expression",
                        t.position());
            throw new ParseException(
                    "S0202", "Expected " + expected + " but found " + t.type() + " ('" + t.value() + "')",
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
            throw new ParseException(null, "Invalid number literal: " + text, pos);
        }
    }

}
