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
 * <p>Aggregates raise T0412 on non-numeric data, and the fused loop must still report the error the
 * unfused statements would have. It cannot throw where it finds the bad value: {@code $sum} was
 * bound before {@code $max}, so {@code $sum}'s failure has to win even if {@code $max} meets bad
 * data at an earlier element. So each aggregate records its own first offending value and the scan
 * raises, after the loop, the error of the earliest-bound aggregate that recorded one. That is
 * exactly the outcome of running the passes in order, since a pass fails if and only if it meets
 * any bad value.
 */
final class SequenceScanFusion {

    private SequenceScanFusion() {}

    /** The operations a fused scan can absorb. */
    private enum Kind { SUM, AVERAGE, MAX, MIN, COUNT_EQ, FILTER_EQ }

    /** One absorbed operation, together with the AST node it replaces. */
    private record Spec(Kind kind, String seqVar, String field, AstNode node,
                        AstNode literal, int stmtIndex) {}

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
        // which aggregate's error wins, and which slot each result lands in.
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

            state.helperMethods.append(emitScanMethod(method, members, t, ctx));

            int firstUse = members.get(0).stmtIndex();
            for (Spec s : members) firstUse = Math.min(firstUse, s.stmtIndex());
            calls.merge(firstUse,
                    "    JsonNode[] " + scanVar + " = " + method + "($" + group.getKey() + ");\n",
                    String::concat);

            for (int k = 0; k < members.size(); k++) {
                results.put(members.get(k).node(), scanVar + "[" + k + "]");
            }
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
        return members.get(0).field().equals(members.get(1).field());
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
                return new Spec(aggregate, seq.name(), fr.name(), n, null, stmtIndex);
            }
            // $count($seq[field = literal])
            if ("count".equals(fc.name()) && fc.args().get(0) instanceof PredicateExpr pe) {
                Spec inner = matchLiteralPredicate(pe, Kind.COUNT_EQ, n, stmtIndex);
                if (inner != null) return inner;
            }
        }
        // $seq[field = literal] used as a value
        if (n instanceof PredicateExpr pe) {
            return matchLiteralPredicate(pe, Kind.FILTER_EQ, n, stmtIndex);
        }
        return null;
    }

    /** Matches {@code $seq[field = <literal>]}, the only predicate shape a scan can absorb. */
    private static Spec matchLiteralPredicate(PredicateExpr pe, Kind kind, AstNode node, int stmtIndex) {
        if (pe.source() instanceof VariableRef seq
                && pe.predicate() instanceof BinaryOp op
                && "=".equals(op.op())
                && op.left() instanceof FieldRef fr
                && isLiteral(op.right())) {
            return new Spec(kind, seq.name(), fr.name(), node, op.right(), stmtIndex);
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
     * Emits the helper that performs one fused scan. Specs are grouped by field so that each
     * distinct field is read once per element, which is the whole point of the pass; within a
     * field, the numeric aggregates further share a single type test on the value read.
     */
    private static String emitScanMethod(String name, List<Spec> members,
                                        Translator t, GenCtx ctx) {
        StringBuilder body = new StringBuilder();
        StringBuilder decls = new StringBuilder();
        StringBuilder checks = new StringBuilder();
        List<String> resultExprs = new ArrayList<>();

        // Slot index is the spec's position in source order, which is also the order the results
        // array is read back in and the order aggregate errors are reported in.
        Map<Spec, String> var = new IdentityHashMap<>();
        for (int k = 0; k < members.size(); k++) {
            var.put(members.get(k), "__v" + k);
        }

        Set<String> fields = new LinkedHashSet<>();
        for (Spec s : members) fields.add(s.field());

        int fieldIndex = 0;
        for (String field : fields) {
            List<Spec> onField = members.stream().filter(s -> s.field().equals(field)).toList();
            String value = "__fv" + fieldIndex++;
            body.append("            JsonNode ").append(value)
                .append(" = field(__e, ").append(ClassAssembler.javaString(field)).append(");\n");

            List<Spec> numeric = onField.stream().filter(s -> isNumericAggregate(s.kind())).toList();
            if (!numeric.isEmpty()) {
                emitNumericAggregates(body, numeric, var, value);
            }
            for (Spec s : onField) {
                switch (s.kind()) {
                    case COUNT_EQ  -> emitCountEq(body, s, var.get(s), value, t, ctx);
                    case FILTER_EQ -> emitFilterEq(body, var.get(s), value, s.literal(), t, ctx);
                    default        -> { }
                }
            }
        }

        for (Spec s : members) {
            String v = var.get(s);
            switch (s.kind()) {
                case SUM -> {
                    decls.append("        double ").append(v).append("s = 0; boolean ").append(v)
                         .append("a = false; JsonNode ").append(v).append("b = null;\n");
                    checks.append("        if (").append(v).append("b != null) aggFail(")
                          .append(v).append("b, \"$sum\");\n");
                    resultExprs.add(v + "a ? numNode(" + v + "s) : MISSING");
                }
                case AVERAGE -> {
                    decls.append("        double ").append(v).append("s = 0; int ").append(v)
                         .append("n = 0; JsonNode ").append(v).append("b = null;\n");
                    checks.append("        if (").append(v).append("b != null) avgFail(")
                          .append(v).append("b);\n");
                    resultExprs.add(v + "n == 0 ? MISSING : numNode(" + v + "s / " + v + "n)");
                }
                case MAX -> {
                    decls.append("        double ").append(v).append("s = Double.NEGATIVE_INFINITY; boolean ")
                         .append(v).append("a = false; JsonNode ").append(v).append("b = null;\n");
                    checks.append("        if (").append(v).append("b != null) aggFail(")
                          .append(v).append("b, \"$max\");\n");
                    resultExprs.add(v + "a ? numNode(" + v + "s) : MISSING");
                }
                case MIN -> {
                    decls.append("        double ").append(v).append("s = Double.POSITIVE_INFINITY; boolean ")
                         .append(v).append("a = false; JsonNode ").append(v).append("b = null;\n");
                    checks.append("        if (").append(v).append("b != null) aggFail(")
                          .append(v).append("b, \"$min\");\n");
                    resultExprs.add(v + "a ? numNode(" + v + "s) : MISSING");
                }
                case COUNT_EQ -> {
                    decls.append("        int ").append(v).append("c = 0;\n");
                    resultExprs.add("number((long) " + v + "c)");
                }
                case FILTER_EQ -> {
                    decls.append("        JsonNode ").append(v).append("x = null, ")
                         .append(v).append("y = null;\n");
                    resultExprs.add("seqResult(" + v + "x, " + v + "y)");
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("\n/**\n * Fused scan over one sequence: ").append(members.size())
           .append(" operations sharing ").append(fields.size())
           .append(fields.size() == 1 ? " field read" : " field reads").append(" per element.\n */\n");
        out.append("private static JsonNode[] ").append(name)
           .append("(JsonNode __seq) throws RuntimeEvaluationException {\n");
        out.append(decls);
        out.append("    if (!missing(__seq)) {\n");
        out.append("        boolean __isArr = __seq.isArray();\n");
        out.append("        int __n = __isArr ? __seq.size() : 1;\n");
        out.append("        for (int __i = 0; __i < __n; __i++) {\n");
        out.append("            JsonNode __e = __isArr ? __seq.get(__i) : __seq;\n");
        // The aggregates skip non-object elements, exactly as the unfused helpers do; the filters
        // do not, because filter() applies field() to whatever the element is.
        out.append("            boolean __obj = __e.isObject();\n");
        out.append(body);
        out.append("        }\n    }\n");
        out.append(checks);
        out.append("    return new JsonNode[]{ ").append(String.join(", ", resultExprs)).append(" };\n");
        out.append("}\n");
        return out.toString();
    }

    private static boolean isNumericAggregate(Kind kind) {
        return kind == Kind.SUM || kind == Kind.AVERAGE || kind == Kind.MAX || kind == Kind.MIN;
    }

    /**
     * Emits the accumulation shared by every numeric aggregate on one field: one type test on the
     * value, then a fold per aggregate. A value that is neither a number nor an array of numbers is
     * recorded rather than thrown on — see the class comment on error ordering.
     */
    private static void emitNumericAggregates(StringBuilder body, List<Spec> numeric,
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
        body.append("            if (__obj && ").append(value).append(" != MISSING) {\n");
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
     * Emits the {@code $count(seq[field = literal])} test. The comparison is specialised on the
     * literal's type here, where it is known, rather than re-dispatched per element as {@code
     * fn_count_field_eq} must do.
     */
    private static void emitCountEq(StringBuilder body, Spec s, String v, String value,
                                    Translator t, GenCtx ctx) {
        String test = switch (s.literal()) {
            case StringLiteral sl -> value + ".isTextual() && "
                    + ClassAssembler.javaString(sl.value()) + ".equals(" + value + ".textValue())";
            case NumberLiteral nl -> value + ".isNumber() && " + value + ".doubleValue() == "
                    + nl.value();
            case BooleanLiteral bl -> value + ".isBoolean() && " + value + ".booleanValue() == " + bl.value();
            default -> "fieldEq(" + value + ", " + s.literal().accept(t, ctx) + ")";
        };
        body.append("            if (__obj && ").append(test).append(") ").append(v).append("c++;\n");
    }

    /**
     * Emits the {@code seq[field = literal]} selection, accumulating exactly as {@code filter()}
     * does so that a result of nothing or one element allocates nothing.
     */
    private static void emitFilterEq(StringBuilder body, String v, String value,
                                     AstNode literal, Translator t, GenCtx ctx) {
        body.append("            if (isTruthy(eq(").append(value).append(", ")
            .append(literal.accept(t, ctx)).append("))) {\n");
        body.append("                if (").append(v).append("y != null) seqAdd(").append(v).append("y, __e);\n");
        body.append("                else if (").append(v).append("x == null) ").append(v).append("x = __e;\n");
        body.append("                else { ").append(v).append("y = seqStart(").append(v)
            .append("x, __e); ").append(v).append("x = null; }\n");
        body.append("            }\n");
    }

}
