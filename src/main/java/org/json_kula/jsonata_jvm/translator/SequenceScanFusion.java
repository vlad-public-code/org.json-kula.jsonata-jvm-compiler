package org.json_kula.jsonata_jvm.translator;

import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collapses the several passes a block makes over one sequence into a single loop.
 *
 * <p>An analytical expression typically binds a sequence once and then interrogates it many times:
 *
 * <pre>
 *   $employees := company.departments.employees;
 *   $totalPayroll := $sum($employees.salary);
 *   $topSalary    := $max($employees.salary);
 *   $seniorCount  := $count($employees[level = "senior"]);
 *   $leadCount    := $count($employees[level = "lead"]);
 * </pre>
 *
 * <p>Each of those is already fused to a single allocation-free loop on its own (see {@code
 * tryFusedCall}), but they remain four loops that read {@code salary} twice and {@code level}
 * twice. Profiling put {@code ObjectNode.get} — a {@code LinkedHashMap} lookup — at 38% of
 * evaluation time on such an expression, and roughly a third of those lookups were re-reads like
 * these. This pass rewrites the group into one pass that reads each distinct field once per element
 * and feeds every accumulator from that one read.
 *
 * <p><strong>Merging the loops is not the optimisation; sharing the field reads is.</strong> A
 * sibling port measured the two apart: one loop with the predicates left as opaque callbacks was
 * worth −9%, and the same loop with the predicates pattern-matched so their reads could be shared
 * was −60%. That is why {@link #matchPred} looks inside a predicate rather than compiling it, and
 * why a predicate shape it does not recognise is declined rather than absorbed generically.
 *
 * <h2>Why a separate method</h2>
 *
 * <p>The fused loop is emitted as its own {@code private static} helper rather than inlined into
 * the block method. That is not a style choice: inlining it into an already-large block method
 * measured <em>slower</em> than not fusing at all (about -5% against +21% for the same logic in a
 * helper), because the block method grows past what C2 compiles well. The win is in the loop, and
 * the loop has to be somewhere the JIT can still optimise it.
 *
 * <h2>What it will not touch</h2>
 *
 * <ul>
 *   <li>Anything under a lambda, a conditional branch, or the right side of {@code and}/{@code or}:
 *       the fused scan runs unconditionally, so only operations that would have run anyway qualify.</li>
 *   <li>Sequences that are not a variable bound exactly once, earlier, in this same block — a
 *       rebound name would make the hoisted scan read a different value than the source did.</li>
 *   <li>Groups too small to pay for themselves (see {@link #worthFusing}).</li>
 * </ul>
 *
 * <h2>Errors</h2>
 *
 * <p>An absorbed operation can fail — an aggregate on non-numeric data (T0412), an ordering
 * comparison on mismatched types (T2009/T2010) — and the fused loop must still report the error the
 * unfused statements would have. It cannot throw where it finds the offending value, because that
 * is not where the failure belonged: the operations were separate statements, and a statement the
 * scan did <em>not</em> absorb may sit between them and fail first.
 *
 * <pre>
 *   ( $e := bad;  $a := $count($e[s = 10]);  $z := $error("boom");  $b := $sum($e.s);  $b )
 * </pre>
 *
 * <p>reports {@code boom}. So each operation records its own first offending value in a slot of its
 * own and the loop keeps going; the throw happens in {@code aggRead} / {@code cmpRead} at the point
 * where the original statement read the result. That is exact against the non-absorbed statements
 * as well as among the absorbed ones, and it costs one null check on a path the type test already
 * visits.
 */
final class SequenceScanFusion {

    private SequenceScanFusion() {}

    /** The operations a fused scan can absorb. */
    private enum Kind { SUM, AVERAGE, MAX, MIN, COUNT, FILTER }

    // =============================================================================================
    // Absorbed predicates
    // =============================================================================================

    /**
     * The predicate shapes a scan can re-emit against fields it has already read.
     *
     * <p>The grammar is deliberately narrow. Everything in it is built from {@code field <op>
     * literal} leaves, so the fields a predicate reads are known before the loop is emitted, which
     * is the entire point of the pass — a predicate kept as an opaque callback would re-read inside
     * itself the fields the loop had just shared.
     */
    private sealed interface Pred {}

    /** {@code field <op> literal}. */
    private record Cmp(String op, String field, AstNode literal) implements Pred {}

    /** {@code left and right} / {@code left or right}. */
    private record Junction(boolean isAnd, Pred left, Pred right) implements Pred {}

    /** The comparisons that can raise, and so may only appear as a whole predicate. */
    private static final Set<String> ORDERING = Set.of("<", "<=", ">", ">=");

    /**
     * One absorbed operation, together with the AST node it replaces.
     *
     * @param field the field an aggregate reads; null for a predicate operation
     * @param pred  the predicate a count or filter applies; null for an aggregate
     */
    private record Spec(Kind kind, String seqVar, String field, Pred pred, AstNode node,
                        int stmtIndex) {

        boolean isAggregate() {
            return field != null;
        }

        /** The comparison that may raise, or null when nothing in this operation can. */
        Cmp throwingCmp() {
            return pred instanceof Cmp c && ORDERING.contains(c.op()) ? c : null;
        }

        /** Every field this operation reads, in the order it reads them. */
        Set<String> fields() {
            Set<String> out = new LinkedHashSet<>();
            if (field != null) out.add(field);
            if (pred != null) collectFields(pred, out);
            return out;
        }

        private static void collectFields(Pred p, Set<String> out) {
            switch (p) {
                case Cmp c -> out.add(c.field());
                case Junction j -> { collectFields(j.left(), out); collectFields(j.right(), out); }
            }
        }
    }

    /**
     * The result of planning one block.
     *
     * @param callsBeforeStatement statements to emit ahead of the block statement at that index
     * @param results              scan node identity to the Java expression yielding its value
     */
    record Plan(Map<Integer, String> callsBeforeStatement, IdentityHashMap<AstNode, String> results) {

        /** Nothing to fuse. */
        static final Plan NONE = new Plan(Map.of(), new IdentityHashMap<>());

        /** Emits the scan calls that must run before the block statement at {@code index}. */
        void emitCallsBefore(int index, StringBuilder sb) {
            String calls = callsBeforeStatement.get(index);
            if (calls != null) sb.append(calls);
        }
    }

    // =============================================================================================
    // Planning
    // =============================================================================================

    /**
     * Plans the fusion for one block, appending the helper methods it needs to {@code state}.
     *
     * @param statements the block's expressions, in source order
     */
    static Plan plan(List<AstNode> statements, Translator t, GenCtx ctx) {
        GenState state = ctx.state;
        // A sequence is only safe to scan once if this block binds its name exactly once: a second
        // binding would mean the hoisted scan and the original statement saw different values.
        Map<String, Integer> boundAt = new HashMap<>();
        Set<String> reboundOrUnsafe = new HashSet<>();
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i) instanceof VariableBinding vb) {
                if (boundAt.put(vb.name(), i) != null) reboundOrUnsafe.add(vb.name());
                // A chained binding ($a := $b := ...) binds more than the outer name.
                if (vb.value() instanceof VariableBinding) reboundOrUnsafe.add(vb.name());
            }
        }
        if (boundAt.isEmpty()) return Plan.NONE;

        // A name this block binds, or one already in scope, is not the built-in it looks like.
        java.util.function.Predicate<String> shadowed =
                name -> boundAt.containsKey(name) || state.isLocal(name);

        List<Spec> specs = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            collect(statements.get(i), i, specs, shadowed);
        }
        if (specs.isEmpty()) return Plan.NONE;

        // Group by sequence, keeping source order within each group: that order is what decides
        // which slot each result lands in.
        Map<String, List<Spec>> groups = new LinkedHashMap<>();
        for (Spec s : specs) {
            if (reboundOrUnsafe.contains(s.seqVar())) continue;
            Integer bound = boundAt.get(s.seqVar());
            // The scan is hoisted to the first statement that uses the group, so the sequence must
            // already be bound by then. Holder variables read as $nameRef[0] and are skipped.
            if (bound == null || bound >= s.stmtIndex() || state.holderVars.contains(s.seqVar())) continue;
            groups.computeIfAbsent(s.seqVar(), k -> new ArrayList<>()).add(s);
        }

        Map<Integer, String> calls = new HashMap<>();
        IdentityHashMap<AstNode, String> results = new IdentityHashMap<>();
        for (Map.Entry<String, List<Spec>> group : groups.entrySet()) {
            List<Spec> members = group.getValue();
            if (!worthFusing(members)) continue;

            int id = state.nextId();
            String scanVar = "__scan" + id;
            String method = "__scan" + id;

            state.helperMethods.append(emitScanMethod(method, members, results, scanVar, t, ctx));

            int firstUse = members.get(0).stmtIndex();
            for (Spec s : members) firstUse = Math.min(firstUse, s.stmtIndex());
            calls.merge(firstUse,
                    "    JsonNode[] " + scanVar + " = " + method + "($" + group.getKey() + ");\n",
                    String::concat);
        }
        return results.isEmpty() ? Plan.NONE : new Plan(calls, results);
    }

    /**
     * Fusing costs a helper method and an array, and saves a loop per operation plus a field read
     * per operation that shares a field with another. Two operations on two different fields save
     * only the loop, which does not pay for the code; anything that re-reads a field, or any group
     * of three, does.
     */
    private static boolean worthFusing(List<Spec> members) {
        if (members.size() < 2) return false;
        if (members.size() >= 3) return true;
        Set<String> shared = new LinkedHashSet<>(members.get(0).fields());
        shared.retainAll(members.get(1).fields());
        return !shared.isEmpty();
    }

    // =============================================================================================
    // Pattern matching
    // =============================================================================================

    /**
     * Collects the scan-shaped operations reachable from {@code n} without passing through anything
     * that might not run. A matched node is not descended into: it is absorbed whole.
     */
    private static void collect(AstNode n, int stmtIndex, List<Spec> out,
                                java.util.function.Predicate<String> shadowed) {
        Spec matched = match(n, stmtIndex, shadowed);
        if (matched != null) {
            out.add(matched);
            return;
        }
        switch (n) {
            case VariableBinding vb -> collect(vb.value(), stmtIndex, out, shadowed);
            case FunctionCall fc -> {
                // A built-in that takes a callback compiles its own arguments, and a bare
                // name(...) path step runs once per element; neither is safe to reach into.
                if (fc.isVariable() && !CALLBACK_BUILTINS.contains(fc.name())) {
                    for (AstNode arg : fc.args()) collect(arg, stmtIndex, out, shadowed);
                }
            }
            case BinaryOp op -> {
                collect(op.left(), stmtIndex, out, shadowed);
                // and/or evaluate their right side only when the left does not decide the result.
                if (!"and".equals(op.op()) && !"or".equals(op.op())) {
                    collect(op.right(), stmtIndex, out, shadowed);
                }
            }
            case UnaryMinus u -> collect(u.operand(), stmtIndex, out, shadowed);
            case Parenthesized p -> collect(p.inner(), stmtIndex, out, shadowed);
            case ObjectConstructor oc -> {
                for (KeyValuePair pair : oc.pairs()) {
                    collect(pair.key(), stmtIndex, out, shadowed);
                    collect(pair.value(), stmtIndex, out, shadowed);
                }
            }
            case ArrayConstructor ac -> {
                for (AstNode el : ac.elements()) collect(el, stmtIndex, out, shadowed);
            }
            // Only the condition of a conditional is certain to run.
            case ConditionalExpr c -> collect(c.condition(), stmtIndex, out, shadowed);
            default -> { }
        }
    }

    /** Built-ins whose callback argument {@link FunctionCallCodeGen} translates itself. */
    private static final Set<String> CALLBACK_BUILTINS =
            Set.of("map", "filter", "single", "sift", "each", "sort", "reduce");

    /**
     * Recognises one scan-shaped operation, or returns {@code null}.
     *
     * @param shadowed names this block or an enclosing scope binds, which therefore do not mean
     *                 the built-in of the same name
     */
    private static Spec match(AstNode n, int stmtIndex, java.util.function.Predicate<String> shadowed) {
        // $sum/$average/$max/$min($seq.field)
        if (n instanceof FunctionCall fc && fc.isVariable() && fc.args().size() == 1
                && !shadowed.test(fc.name())) {
            Kind aggregate = switch (fc.name()) {
                case "sum"     -> Kind.SUM;
                case "average" -> Kind.AVERAGE;
                case "max"     -> Kind.MAX;
                case "min"     -> Kind.MIN;
                default        -> null;
            };
            if (aggregate != null
                    && fc.args().get(0) instanceof PathExpr path
                    && path.steps().size() == 2
                    && path.steps().get(0) instanceof VariableRef seq
                    && path.steps().get(1) instanceof FieldRef fr) {
                return new Spec(aggregate, seq.name(), fr.name(), null, n, stmtIndex);
            }
            // $count($seq[<pred>])
            if ("count".equals(fc.name()) && fc.args().get(0) instanceof PredicateExpr pe) {
                Spec inner = matchPredicated(pe, Kind.COUNT, n, stmtIndex);
                if (inner != null) return inner;
            }
        }
        // $seq[<pred>] used as a value
        if (n instanceof PredicateExpr pe) {
            return matchPredicated(pe, Kind.FILTER, n, stmtIndex);
        }
        return null;
    }

    /** Matches {@code $seq[<pred>]} for a predicate the scan can re-emit. */
    private static Spec matchPredicated(PredicateExpr pe, Kind kind, AstNode node, int stmtIndex) {
        if (!(pe.source() instanceof VariableRef seq)) return null;
        Pred pred = matchPred(pe.predicate(), true);
        return pred == null ? null : new Spec(kind, seq.name(), null, pred, node, stmtIndex);
    }

    /**
     * Recognises a predicate the scan can re-emit against pre-read fields, or returns null.
     *
     * <p>{@code =} and {@code !=} cannot raise, so they may sit anywhere in the tree: moving one
     * earlier cannot move an error with it. The ordering comparisons can raise, and the error they
     * raise has to be deferred to the statement that reads the result (see {@code cmpRead}) — which
     * is only expressible for a single comparison, so they are admitted as a whole predicate and
     * not underneath an {@code and} or an {@code or}.
     *
     * @param root whether {@code n} is the entire predicate
     */
    private static Pred matchPred(AstNode n, boolean root) {
        if (n instanceof Parenthesized p) return matchPred(p.inner(), root);
        if (!(n instanceof BinaryOp op)) return null;
        if ("and".equals(op.op()) || "or".equals(op.op())) {
            Pred left = matchPred(op.left(), false);
            if (left == null) return null;
            Pred right = matchPred(op.right(), false);
            return right == null ? null : new Junction("and".equals(op.op()), left, right);
        }
        boolean comparison = "=".equals(op.op()) || "!=".equals(op.op())
                || (root && ORDERING.contains(op.op()));
        if (comparison && op.left() instanceof FieldRef fr && isLiteral(op.right())) {
            return new Cmp(op.op(), fr.name(), op.right());
        }
        return null;
    }

    private static boolean isLiteral(AstNode node) {
        return node instanceof StringLiteral || node instanceof NumberLiteral
                || node instanceof BooleanLiteral || node instanceof NullLiteral;
    }

    // =============================================================================================
    // Emission
    // =============================================================================================

    /**
     * Emits the helper that performs one fused scan and records how each absorbed node reads its
     * result back.
     *
     * <p>Every distinct field the group mentions is read once at the top of the loop body — that is
     * the whole point of the pass — and the operations then work from those locals.
     *
     * <p>One read serves every consumer here, which is worth stating because it is not free: an
     * aggregate and a predicate must navigate the same way for that to be sound. Both do. {@code
     * $sum($e.f)} is an aggregate over a <em>path</em> and {@code $e[f = 1]} applies a path step
     * per element, so both are {@code field(elem, name)} — including for an element that is itself
     * an array, where the step maps over its members and both can therefore see a value. A version
     * of this that gated the aggregates on {@code isObject} agreed with its own unfused helpers and
     * with neither the reference nor the filter beside it.
     */
    private static String emitScanMethod(String name, List<Spec> members,
                                         IdentityHashMap<AstNode, String> results, String scanVar,
                                         Translator t, GenCtx ctx) {
        StringBuilder body = new StringBuilder();
        StringBuilder decls = new StringBuilder();
        List<String> resultExprs = new ArrayList<>();

        Map<Spec, String> var = new IdentityHashMap<>();
        for (int k = 0; k < members.size(); k++) {
            var.put(members.get(k), "__v" + k);
        }

        // One local per distinct field, in first-mention order.
        Map<String, String> local = new LinkedHashMap<>();
        for (Spec s : members) {
            for (String f : s.fields()) {
                if (!local.containsKey(f)) local.put(f, "__fv" + local.size());
            }
        }
        for (Map.Entry<String, String> e : local.entrySet()) {
            body.append("            JsonNode ").append(e.getValue())
                .append(" = field(__e, ").append(ClassAssembler.javaString(e.getKey())).append(");\n");
        }

        // Aggregates first, grouped by field so the ones sharing a field share a type test too.
        for (String field : local.keySet()) {
            List<Spec> numeric = members.stream()
                    .filter(s -> s.isAggregate() && s.field().equals(field)).toList();
            if (!numeric.isEmpty()) emitAggregates(body, numeric, var, local.get(field));
        }
        for (Spec s : members) {
            if (!s.isAggregate()) emitPredicated(body, s, var.get(s), local, t, ctx);
        }

        // Result slots, in source order: the value, then whatever the operation may have to raise.
        for (Spec s : members) {
            String v = var.get(s);
            int value = resultExprs.size();
            switch (s.kind()) {
                case SUM -> {
                    decls.append("        double ").append(v).append("s = 0; boolean ").append(v)
                         .append("a = false; JsonNode ").append(v).append("b = null;\n");
                    resultExprs.add(v + "a ? numNode(" + v + "s) : MISSING");
                    resultExprs.add(v + "b");
                    results.put(s.node(), "aggRead(" + slot(scanVar, value) + ", "
                            + slot(scanVar, value + 1) + ", \"$sum\")");
                }
                case AVERAGE -> {
                    decls.append("        double ").append(v).append("s = 0; int ").append(v)
                         .append("n = 0; JsonNode ").append(v).append("b = null;\n");
                    resultExprs.add(v + "n == 0 ? MISSING : numNode(" + v + "s / " + v + "n)");
                    resultExprs.add(v + "b");
                    results.put(s.node(), "avgRead(" + slot(scanVar, value) + ", "
                            + slot(scanVar, value + 1) + ")");
                }
                case MAX, MIN -> {
                    String seed = s.kind() == Kind.MAX
                            ? "Double.NEGATIVE_INFINITY" : "Double.POSITIVE_INFINITY";
                    decls.append("        double ").append(v).append("s = ").append(seed)
                         .append("; boolean ").append(v).append("a = false; JsonNode ").append(v)
                         .append("b = null;\n");
                    resultExprs.add(v + "a ? numNode(" + v + "s) : MISSING");
                    resultExprs.add(v + "b");
                    results.put(s.node(), "aggRead(" + slot(scanVar, value) + ", "
                            + slot(scanVar, value + 1) + ", \""
                            + (s.kind() == Kind.MAX ? "$max" : "$min") + "\")");
                }
                case COUNT -> {
                    decls.append("        int ").append(v).append("c = 0;\n");
                    resultExprs.add("number((long) " + v + "c)");
                    results.put(s.node(), readWithDeferredCmp(s, v, decls, resultExprs, scanVar, value));
                }
                case FILTER -> {
                    decls.append("        JsonNode ").append(v).append("x = null, ")
                         .append(v).append("y = null;\n");
                    resultExprs.add("seqResult(" + v + "x, " + v + "y)");
                    results.put(s.node(), readWithDeferredCmp(s, v, decls, resultExprs, scanVar, value));
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("\n/**\n * Fused scan over one sequence: ").append(members.size())
           .append(" operations sharing ").append(local.size())
           .append(local.size() == 1 ? " field read" : " field reads").append(" per element.\n */\n");
        out.append("private static JsonNode[] ").append(name)
           .append("(JsonNode __seq) throws RuntimeEvaluationException {\n");
        out.append(decls);
        out.append("    if (!missing(__seq)) {\n");
        out.append("        boolean __isArr = __seq.isArray();\n");
        out.append("        int __n = __isArr ? __seq.size() : 1;\n");
        out.append("        for (int __i = 0; __i < __n; __i++) {\n");
        out.append("            JsonNode __e = __isArr ? __seq.get(__i) : __seq;\n");
        out.append(body);
        out.append("        }\n    }\n");
        out.append("    return new JsonNode[]{ ").append(String.join(", ", resultExprs)).append(" };\n");
        out.append("}\n");
        return out.toString();
    }

    private static String slot(String scanVar, int index) {
        return scanVar + "[" + index + "]";
    }

    /**
     * Adds the two operand slots an ordering comparison needs and returns the reading expression.
     * A predicate that cannot raise reads its slot directly.
     */
    private static String readWithDeferredCmp(Spec s, String v, StringBuilder decls,
                                              List<String> resultExprs, String scanVar, int value) {
        if (s.throwingCmp() == null) return slot(scanVar, value);
        decls.append("        JsonNode ").append(v).append("l = null, ").append(v).append("r = null;\n");
        int bad = resultExprs.size();
        resultExprs.add(v + "l");
        resultExprs.add(v + "r");
        return "cmpRead(" + slot(scanVar, value) + ", " + slot(scanVar, bad) + ", "
                + slot(scanVar, bad + 1) + ")";
    }

    /**
     * Emits the accumulation shared by every numeric aggregate on one field: one type test on the
     * value, then a fold per aggregate. A value that is neither a number nor an array of numbers is
     * recorded rather than thrown on — see the class comment on error ordering.
     */
    private static void emitAggregates(StringBuilder body, List<Spec> numeric,
                                       Map<Spec, String> var, String value) {
        StringBuilder fold = new StringBuilder();
        StringBuilder mark = new StringBuilder();
        for (Spec s : numeric) {
            String v = var.get(s);
            switch (s.kind()) {
                case SUM     -> fold.append(v).append("s += __d; ").append(v).append("a = true; ");
                case AVERAGE -> fold.append(v).append("s += __d; ").append(v).append("n++; ");
                case MAX     -> fold.append("if (__d > ").append(v).append("s) ").append(v)
                                    .append("s = __d; ").append(v).append("a = true; ");
                case MIN     -> fold.append("if (__d < ").append(v).append("s) ").append(v)
                                    .append("s = __d; ").append(v).append("a = true; ");
                default      -> { }
            }
            mark.append("if (").append(v).append("b == null) ").append(v).append("b = __bad; ");
        }
        body.append("            if (").append(value).append(" != MISSING) {\n");
        body.append("                if (").append(value).append(".isNumber()) { double __d = ")
            .append(value).append(".doubleValue(); ").append(fold).append("}\n");
        body.append("                else if (").append(value).append(".isArray()) {\n");
        body.append("                    for (JsonNode __sub : ").append(value).append(") {\n");
        body.append("                        if (__sub.isNumber()) { double __d = __sub.doubleValue(); ")
            .append(fold).append("}\n");
        body.append("                        else { JsonNode __bad = __sub; ").append(mark).append("}\n");
        body.append("                    }\n                }\n");
        body.append("                else { JsonNode __bad = ").append(value).append("; ")
            .append(mark).append("}\n");
        body.append("            }\n");
    }

    /**
     * Emits one {@code $count($seq[<pred>])} or {@code $seq[<pred>]}, testing the pre-read field
     * locals rather than navigating again.
     */
    private static void emitPredicated(StringBuilder body, Spec s, String v,
                                       Map<String, String> local, Translator t, GenCtx ctx) {
        String action = s.kind() == Kind.COUNT ? countAction(v) : filterAction(v);
        Cmp throwing = s.throwingCmp();
        if (throwing == null) {
            body.append("            if (").append(predExpr(s.pred(), local, t, ctx)).append(") {\n")
                .append(action).append("            }\n");
            return;
        }
        // An ordering comparison records the operands it could not compare and carries on; the
        // error is raised by cmpRead at the statement that reads this operation's result.
        String cmp = v + "p";
        body.append("            JsonNode ").append(cmp).append(" = cmpSafe(")
            .append(local.get(throwing.field())).append(", ")
            .append(throwing.literal().accept(t, ctx)).append(", ")
            .append(cmpOp(throwing.op())).append(");\n");
        body.append("            if (").append(cmp).append(" == null) { if (").append(v)
            .append("l == null) { ").append(v).append("l = ").append(local.get(throwing.field()))
            .append("; ").append(v).append("r = ").append(throwing.literal().accept(t, ctx))
            .append("; } }\n");
        body.append("            else if (isTruthy(").append(cmp).append(")) {\n")
            .append(action).append("            }\n");
    }

    private static String countAction(String v) {
        return "                " + v + "c++;\n";
    }

    /** Accumulates exactly as {@code filter()} does, so nothing is allocated below two matches. */
    private static String filterAction(String v) {
        return "                if (" + v + "y != null) seqAdd(" + v + "y, __e);\n"
             + "                else if (" + v + "x == null) " + v + "x = __e;\n"
             + "                else { " + v + "y = seqStart(" + v + "x, __e); " + v + "x = null; }\n";
    }

    private static String cmpOp(String op) {
        return switch (op) {
            case "<"  -> "CMP_LT";
            case "<=" -> "CMP_LE";
            case ">"  -> "CMP_GT";
            default   -> "CMP_GE";
        };
    }

    /**
     * Renders a predicate as a Java boolean expression over the pre-read field locals. {@code &&}
     * and {@code ||} reproduce the short-circuit of {@code and_}/{@code or_} exactly, and the
     * equality tests are specialised on the literal's type here, where it is known, rather than
     * re-dispatched per element.
     */
    private static String predExpr(Pred p, Map<String, String> local, Translator t, GenCtx ctx) {
        return switch (p) {
            case Junction j -> "(" + predExpr(j.left(), local, t, ctx)
                    + (j.isAnd() ? " && " : " || ") + predExpr(j.right(), local, t, ctx) + ")";
            case Cmp c -> {
                String value = local.get(c.field());
                if ("!=".equals(c.op())) {
                    yield "isTruthy(ne(" + value + ", " + c.literal().accept(t, ctx) + "))";
                }
                yield switch (c.literal()) {
                    case StringLiteral sl -> value + ".isTextual() && "
                            + ClassAssembler.javaString(sl.value()) + ".equals(" + value + ".textValue())";
                    case NumberLiteral nl -> value + ".isNumber() && " + value + ".doubleValue() == "
                            + nl.value();
                    case BooleanLiteral bl -> value + ".isBoolean() && " + value + ".booleanValue() == "
                            + bl.value();
                    default -> "fieldEq(" + value + ", " + c.literal().accept(t, ctx) + ")";
                };
            }
        };
    }

}
