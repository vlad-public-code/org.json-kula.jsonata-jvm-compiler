package org.json_kula.jsonata_jvm.optimizer;

import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-pass, bottom-up AST optimizer for JSONata expressions.
 *
 * <p>Each optimization pass rewrites children first (post-order), then applies
 * local rewrite rules to the resulting node. Passes may be chained; a single
 * call to {@link #optimize(AstNode)} applies one full pass.
 *
 * <h2>Rewrites applied</h2>
 * <ul>
 *   <li><b>Constant folding</b> — evaluates operators whose operands are all
 *       compile-time constants (number, string, boolean, null):
 *       arithmetic ({@code + - * / %}), string concatenation ({@code &}),
 *       comparisons ({@code = != < <= > >=}), and boolean logic
 *       ({@code and}, {@code or}).</li>
 *   <li><b>Arithmetic identity / absorption</b> — {@code x+0}, {@code x*1},
 *       {@code x*0}, {@code x/1}, {@code x-0}, and their symmetric variants.</li>
 *   <li><b>String identity</b> — {@code x & ""} and {@code "" & x} → {@code x}.</li>
 *   <li><b>Boolean short-circuit identities</b> — {@code x and false} → {@code false},
 *       {@code x or true} → {@code true}, {@code x and true} → {@code x},
 *       {@code x or false} → {@code x}.</li>
 *   <li><b>Conditional folding</b> — when the condition is a literal
 *       {@code true}/{@code false}/{@code null}.</li>
 *   <li><b>Unary-minus elimination</b> — {@code -NumberLiteral(v)} →
 *       {@code NumberLiteral(-v)};  double negation {@code -(-x)} → {@code x}.</li>
 *   <li><b>Block unwrapping</b> — a {@link Block} with exactly one expression
 *       is replaced by that expression.</li>
 *   <li><b>PathExpr flattening</b> — nested {@link PathExpr} nodes are merged
 *       into a single flat list of steps.</li>
 * </ul>
 *
 * <p>Unsafe operations are left intact: division or modulo by the literal zero
 * is not folded so that the runtime can report a meaningful error.
 */
public final class Optimizer {

    private Optimizer() {}

    /**
     * Returns an optimized copy of {@code node}.
     * The original node is never mutated.
     *
     * @param node the root of the AST sub-tree to optimize
     * @return the (possibly identical) optimized node
     */
    public static AstNode optimize(AstNode node) {
        return VISITOR.rewrite(node);
    }

    // -------------------------------------------------------------------------

    private static final RewriteVisitor VISITOR = new RewriteVisitor();

    private static final class RewriteVisitor implements AstNode.Visitor<AstNode, Void> {

        AstNode rewrite(AstNode node) {
            return node.accept(this, null);
        }

        // ---- Terminals — returned as-is ----

        @Override public AstNode visitStringLiteral(StringLiteral n, Void c)   { return n; }
        @Override public AstNode visitNumberLiteral(NumberLiteral n, Void c)   { return n; }
        @Override public AstNode visitBooleanLiteral(BooleanLiteral n, Void c) { return n; }
        @Override public AstNode visitNullLiteral(NullLiteral n, Void c)       { return n; }
        @Override public AstNode visitRegexLiteral(RegexLiteral n, Void c)     { return n; }
        @Override public AstNode visitContextRef(ContextRef n, Void c)         { return n; }
        @Override public AstNode visitRootRef(RootRef n, Void c)               { return n; }
        @Override public AstNode visitVariableRef(VariableRef n, Void c)       { return n; }
        @Override public AstNode visitFieldRef(FieldRef n, Void c)             { return n; }
        @Override public AstNode visitWildcardStep(WildcardStep n, Void c)     { return n; }
        @Override public AstNode visitDescendantStep(DescendantStep n, Void c) { return n; }

        // ---- Unary minus ----

        @Override
        public AstNode visitUnaryMinus(UnaryMinus n, Void c) {
            AstNode operand = rewrite(n.operand());
            // -NumberLiteral(v) → NumberLiteral(-v)
            if (operand instanceof NumberLiteral nl) {
                return new NumberLiteral(-nl.value());
            }
            // -(-x) → x
            if (operand instanceof UnaryMinus inner) {
                return inner.operand();
            }
            return operand == n.operand() ? n : new UnaryMinus(operand);
        }

        // ---- Binary operations ----

        @Override
        public AstNode visitBinaryOp(BinaryOp n, Void c) {
            AstNode left  = rewrite(n.left());
            AstNode right = rewrite(n.right());

            AstNode folded = tryFold(n.op(), left, right);
            if (folded != null) return folded;

            return left == n.left() && right == n.right() ? n : new BinaryOp(n.op(), left, right);
        }

        // ---- Conditional ----

        @Override
        public AstNode visitConditionalExpr(ConditionalExpr n, Void c) {
            AstNode condition = rewrite(n.condition());
            AstNode then      = rewrite(n.then());
            AstNode otherwise = n.otherwise() != null ? rewrite(n.otherwise()) : null;

            // true ? a : b  →  a
            if (condition instanceof BooleanLiteral bl && bl.value()) return then;
            // false ? a : b  →  b   (or null if no else-branch)
            if (condition instanceof BooleanLiteral bl && !bl.value()) {
                return otherwise != null ? otherwise : new NullLiteral();
            }
            // null ? a : b  →  b
            if (condition instanceof NullLiteral) {
                return otherwise != null ? otherwise : new NullLiteral();
            }

            return condition == n.condition() && then == n.then() && otherwise == n.otherwise()
                    ? n
                    : new ConditionalExpr(condition, then, otherwise);
        }

        // ---- Block ----

        @Override
        public AstNode visitBlock(Block n, Void c) {
            List<AstNode> exprs = rewriteList(n.expressions());
            // Single-expression block: unwrap
            if (exprs.size() == 1) return exprs.get(0);
            return exprs.equals(n.expressions()) ? n : new Block(exprs);
        }

        // ---- Path expression ----

        @Override
        public AstNode visitPathExpr(PathExpr n, Void c) {
            // Rewrite each step, then flatten nested PathExprs
            List<AstNode> flat = new ArrayList<>();
            for (AstNode step : n.steps()) {
                AstNode rewritten = rewrite(step);
                if (rewritten instanceof PathExpr inner) {
                    flat.addAll(inner.steps());
                } else {
                    flat.add(rewritten);
                }
            }
            return flat.equals(n.steps()) ? n : new PathExpr(flat);
        }

        // ---- Predicate / subscript ----

        @Override
        public AstNode visitPredicateExpr(PredicateExpr n, Void c) {
            AstNode source    = rewrite(n.source());
            AstNode predicate = rewrite(n.predicate());
            return source == n.source() && predicate == n.predicate()
                    ? n : new PredicateExpr(source, predicate);
        }

        @Override
        public AstNode visitArraySubscript(ArraySubscript n, Void c) {
            AstNode source = rewrite(n.source());
            AstNode index  = rewrite(n.index());
            return source == n.source() && index == n.index()
                    ? n : new ArraySubscript(source, index);
        }

        // ---- Constructors ----

        @Override
        public AstNode visitArrayConstructor(ArrayConstructor n, Void c) {
            List<AstNode> elements = rewriteList(n.elements());
            return elements.equals(n.elements()) ? n : new ArrayConstructor(elements);
        }

        @Override
        public AstNode visitObjectConstructor(ObjectConstructor n, Void c) {
            List<KeyValuePair> pairs = rewritePairs(n.pairs());
            return pairs.equals(n.pairs()) ? n : new ObjectConstructor(pairs);
        }

        // ---- Functions / lambdas / binding ----

        @Override
        public AstNode visitFunctionCall(FunctionCall n, Void c) {
            List<AstNode> args = rewriteList(n.args());
            return args.equals(n.args()) ? n : new FunctionCall(n.name(), args);
        }

        @Override
        public AstNode visitLambda(Lambda n, Void c) {
            AstNode body = rewrite(n.body());
            return body == n.body() ? n : new Lambda(n.params(), body);
        }

        @Override
        public AstNode visitVariableBinding(VariableBinding n, Void c) {
            AstNode value = rewrite(n.value());
            return value == n.value() ? n : new VariableBinding(n.name(), value);
        }

        // ---- Range, sort, group-by, chain, transform ----

        @Override
        public AstNode visitRangeExpr(RangeExpr n, Void c) {
            AstNode from = rewrite(n.from());
            AstNode to   = rewrite(n.to());
            return from == n.from() && to == n.to() ? n : new RangeExpr(from, to);
        }

        @Override
        public AstNode visitSortExpr(SortExpr n, Void c) {
            AstNode source = rewrite(n.source());
            List<SortKey> keys = n.keys().stream()
                    .map(k -> new SortKey(rewrite(k.key()), k.descending()))
                    .toList();
            return source == n.source() && keys.equals(n.keys())
                    ? n : new SortExpr(source, keys);
        }

        @Override
        public AstNode visitGroupByExpr(GroupByExpr n, Void c) {
            AstNode source = rewrite(n.source());
            List<KeyValuePair> pairs = rewritePairs(n.pairs());
            return source == n.source() && pairs.equals(n.pairs())
                    ? n : new GroupByExpr(source, pairs);
        }

        @Override
        public AstNode visitChainExpr(ChainExpr n, Void c) {
            List<AstNode> steps = rewriteList(n.steps());
            return steps.equals(n.steps()) ? n : new ChainExpr(steps);
        }

        @Override
        public AstNode visitParenthesized(Parenthesized n, Void c) {
            // The Parenthesized wrapper only exists to suppress path-step subscript
            // folding during parsing.  Once parsing is done the AST structure itself
            // encodes the distinction: a folded subscript lives *inside* the PathExpr
            // steps, while a parenthesised subscript wraps the PathExpr with a plain
            // ArraySubscript.  The wrapper can therefore be stripped here so that
            // constant-folding and other rewrites see through it normally.
            return rewrite(n.inner());
        }

        @Override
        public AstNode visitForceArray(ForceArray n, Void c) {
            AstNode source = rewrite(n.source());
            return source == n.source() ? n : new ForceArray(source);
        }

        @Override
        public AstNode visitTransformExpr(TransformExpr n, Void c) {
            AstNode source  = rewrite(n.source());
            AstNode pattern = rewrite(n.pattern());
            AstNode update  = rewrite(n.update());
            return source == n.source() && pattern == n.pattern() && update == n.update()
                    ? n : new TransformExpr(source, pattern, update);
        }

        @Override
        public AstNode visitElvisExpr(ElvisExpr n, Void c) {
            AstNode left  = rewrite(n.left());
            AstNode right = rewrite(n.right());
            return left == n.left() && right == n.right() ? n : new ElvisExpr(left, right);
        }

        @Override
        public AstNode visitCoalesceExpr(CoalesceExpr n, Void c) {
            AstNode left  = rewrite(n.left());
            AstNode right = rewrite(n.right());
            return left == n.left() && right == n.right() ? n : new CoalesceExpr(left, right);
        }

        @Override
        public AstNode visitPartialPlaceholder(PartialPlaceholder n, Void c) { return n; }

        @Override
        public AstNode visitPartialApplication(PartialApplication n, Void c) {
            List<AstNode> args = rewriteList(n.args());
            return args.equals(n.args()) ? n : new PartialApplication(n.name(), args);
        }

        // =====================================================================
        // Constant-folding logic
        // =====================================================================

        /**
         * Attempts to fold a binary operation whose operands are already
         * (partially) optimized. Returns the folded node, or {@code null} if
         * no rule matched.
         */
        private static AstNode tryFold(String op, AstNode left, AstNode right) {
            // --- Number × Number ---
            if (left instanceof NumberLiteral l && right instanceof NumberLiteral r) {
                return foldNumNum(op, l.value(), r.value());
            }
            // --- String × String ---
            if (left instanceof StringLiteral l && right instanceof StringLiteral r) {
                return foldStrStr(op, l.value(), r.value());
            }
            // --- Boolean × Boolean ---
            if (left instanceof BooleanLiteral l && right instanceof BooleanLiteral r) {
                return foldBoolBool(op, l.value(), r.value());
            }
            // --- Boolean short-circuit identities (one side is a literal) ---
            AstNode boolFold = tryFoldBoolIdentity(op, left, right);
            if (boolFold != null) return boolFold;

            // --- Arithmetic identity / absorption ---
            AstNode numFold = tryFoldNumIdentity(op, left, right);
            if (numFold != null) return numFold;

            // --- String identity ---
            if ("&".equals(op)) {
                if (left  instanceof StringLiteral sl && sl.value().isEmpty()) return right;
                if (right instanceof StringLiteral sl && sl.value().isEmpty()) return left;
            }
            return null;
        }

        private static AstNode foldNumNum(String op, double l, double r) {
            return switch (op) {
                case "+"  -> new NumberLiteral(l + r);
                case "-"  -> new NumberLiteral(l - r);
                case "*"  -> new NumberLiteral(l * r);
                // Guard: do not fold division/modulo by zero — leave for runtime
                case "/"  -> r != 0 ? new NumberLiteral(l / r) : null;
                case "%"  -> r != 0 ? new NumberLiteral(l % r) : null;
                case "="  -> new BooleanLiteral(l == r);
                case "!=" -> new BooleanLiteral(l != r);
                case "<"  -> new BooleanLiteral(l <  r);
                case "<=" -> new BooleanLiteral(l <= r);
                case ">"  -> new BooleanLiteral(l >  r);
                case ">=" -> new BooleanLiteral(l >= r);
                default   -> null;
            };
        }

        private static AstNode foldStrStr(String op, String l, String r) {
            return switch (op) {
                case "&"  -> new StringLiteral(l + r);
                case "="  -> new BooleanLiteral(l.equals(r));
                case "!=" -> new BooleanLiteral(!l.equals(r));
                case "<"  -> new BooleanLiteral(l.compareTo(r) <  0);
                case "<=" -> new BooleanLiteral(l.compareTo(r) <= 0);
                case ">"  -> new BooleanLiteral(l.compareTo(r) >  0);
                case ">=" -> new BooleanLiteral(l.compareTo(r) >= 0);
                default   -> null;
            };
        }

        private static AstNode foldBoolBool(String op, boolean l, boolean r) {
            return switch (op) {
                case "and" -> new BooleanLiteral(l && r);
                case "or"  -> new BooleanLiteral(l || r);
                case "="   -> new BooleanLiteral(l == r);
                case "!="  -> new BooleanLiteral(l != r);
                default    -> null;
            };
        }

        /** Boolean identity/absorption rules where only one side is a literal. */
        private static AstNode tryFoldBoolIdentity(String op, AstNode left, AstNode right) {
            if ("and".equals(op)) {
                if (isFalse(left) || isFalse(right)) return new BooleanLiteral(false);
                if (isTrue(left))  return right;
                if (isTrue(right)) return left;
            }
            if ("or".equals(op)) {
                if (isTrue(left) || isTrue(right))   return new BooleanLiteral(true);
                if (isFalse(left))  return right;
                if (isFalse(right)) return left;
            }
            return null;
        }

        /** Arithmetic identity and absorption rules. */
        private static AstNode tryFoldNumIdentity(String op, AstNode left, AstNode right) {
            boolean leftZero  = isNumber(left,  0);
            boolean rightZero = isNumber(right, 0);
            boolean leftOne   = isNumber(left,  1);
            boolean rightOne  = isNumber(right, 1);

            return switch (op) {
                case "+" -> leftZero ? right : rightZero ? left : null;
                case "-" -> rightZero ? left : null;
                case "*" -> {
                    if (leftZero || rightZero) yield new NumberLiteral(0);
                    if (leftOne)  yield right;
                    if (rightOne) yield left;
                    yield null;
                }
                case "/" -> rightOne ? left : null;   // do NOT fold /0
                case "%" -> null;
                default  -> null;
            };
        }

        // ---- Helpers ----

        private static boolean isTrue(AstNode n) {
            return n instanceof BooleanLiteral bl && bl.value();
        }

        private static boolean isFalse(AstNode n) {
            return n instanceof BooleanLiteral bl && !bl.value();
        }

        private static boolean isNumber(AstNode n, double v) {
            return n instanceof NumberLiteral nl && nl.value() == v;
        }

        private List<AstNode> rewriteList(List<AstNode> nodes) {
            List<AstNode> result = new ArrayList<>(nodes.size());
            boolean changed = false;
            for (AstNode n : nodes) {
                AstNode r = rewrite(n);
                result.add(r);
                if (r != n) changed = true;
            }
            return changed ? result : nodes;
        }

        private List<KeyValuePair> rewritePairs(List<KeyValuePair> pairs) {
            List<KeyValuePair> result = new ArrayList<>(pairs.size());
            boolean changed = false;
            for (KeyValuePair p : pairs) {
                AstNode k = rewrite(p.key());
                AstNode v = rewrite(p.value());
                KeyValuePair rp = (k == p.key() && v == p.value()) ? p : new KeyValuePair(k, v);
                result.add(rp);
                if (rp != p) changed = true;
            }
            return changed ? result : pairs;
        }
    }
}
