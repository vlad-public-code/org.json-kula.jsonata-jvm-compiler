package org.json_kula.jsonata_jvm.translator;

import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.parser.ast.AstNode.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Code generation for path expressions — the largest and most intricate part of the translator.
 *
 * <p>A JSONata path is not a simple chain of field reads. Each step may rebind the context, map over
 * a sequence, filter it, keep a reference to its parent for {@code %}, cross-join through a context
 * binding ({@code @$v}), or construct a value that must not be flattened into the surrounding
 * sequence — and the code emitted for one step depends on what the following steps do. Keeping that
 * reasoning in one place, separate from the per-node visitor, is the only way it stays readable.
 *
 * <p>All methods are static; those that need to translate a sub-expression take the
 * {@link Translator} as their first argument, matching {@link FunctionCallCodeGen} and
 * {@link BlockCodeGen}.
 */
final class PathCodeGen {

    private PathCodeGen() {}

    static String visitPathExpr(Translator t, PathExpr n, GenCtx ctx) {
        List<AstNode> steps = n.steps();

        // Each PathExpr is a fresh path scope: clear any inherited cross-join context
        // so that sub-paths inside constructors or predicates don't inadvertently
        // reuse the cross-join base from an outer path.
        ctx = ctx.withCrossJoinParent(null);

        // Check whether the first step is a ForceArray marker.
        // If so, use its inner source as the actual first step and wrap the
        // final result in forceArray() to prevent singleton collapsing.
        AstNode firstStep = steps.get(0);
        boolean forceArr = firstStep instanceof ForceArray;
        if (forceArr) firstStep = ((ForceArray) firstStep).source();
        // Also check if the first step is a predicate whose source is a ForceArray:
        // e.g. Phone[][type="mobile"].number — the predicate source is ForceArray(Phone)
        // and the final path result must be kept as an array.
        if (!forceArr && firstStep instanceof PredicateExpr pe && pe.source() instanceof ForceArray) {
            forceArr = true;
        }

        // When ForceArray(inner_binding_path) is the first step and there are remaining
        // outer steps (e.g. ObjectConstructor), merge them into the inner path so that
        // cross-join variables ($l, $b) bound inside the inner path remain in scope for
        // the outer steps.  Without this, the OC is compiled outside the lambda scopes.
        if (forceArr && firstStep instanceof PathExpr innerPe
                && hasAnyBinding(innerPe.steps())
                && steps.size() > 1) {
            List<AstNode> mergedSteps = new ArrayList<>(innerPe.steps());
            mergedSteps.addAll(steps.subList(1, steps.size()));
            return "forceArray(" + visitPathExpr(t, new PathExpr(mergedSteps), ctx) + ")";
        }

        // When the first step is PredicateExpr(inner_binding_path, pred), unfold
        // the inner path + predicate + remaining steps into a single flat PathExpr so that:
        // (a) cross-join parent tracking works correctly, and
        // (b) variables bound by @$var / #$var inside the inner path remain in scope.
        // This handles patterns like:
        //   Employee@$e.(Contact)[ssn=$e.SSN].{...}
        //   Account.Order#$o.Product[pred].{'Order Index': $o}
        //   library.books#$pos.$[$...].pos
        if (!forceArr && firstStep instanceof PredicateExpr outerPe
                && outerPe.source() instanceof PathExpr innerPath
                && hasAnyBinding(innerPath.steps())) {
            List<AstNode> newSteps = new ArrayList<>(innerPath.steps());
            // Re-attach the outer predicate as a context-relative step.
            newSteps.add(new PredicateExpr(new ContextRef(), outerPe.predicate()));
            for (int i = 1; i < steps.size(); i++) newSteps.add(steps.get(i));
            return visitPathExpr(t, new PathExpr(newSteps), ctx);
        }

        // When the first step is SortExpr(inner_binding_path, keys), unfold
        // the inner path so that variables bound by @$var / #$var remain in scope
        // for subsequent steps (e.g. object constructors using $o / $e).
        // Account.Order#$o.Product^(ProductID).{'Order Index': $o} →
        //   PathExpr([Account, Order, #$o, SortExpr(Product, ^ProductID), ObjectConstructor])
        if (!forceArr && firstStep instanceof SortExpr outerSort
                && outerSort.source() instanceof PathExpr innerPath
                && hasAnyBinding(innerPath.steps())) {
            List<AstNode> innerSteps = innerPath.steps();
            // Find the last binding step and split there.
            int bindIdx = -1;
            for (int i = 0; i < innerSteps.size(); i++) {
                if (innerSteps.get(i) instanceof ContextBinding
                        || innerSteps.get(i) instanceof PositionBinding) bindIdx = i;
            }
            // Prefix: path steps up to and including the binding.
            List<AstNode> newSteps = new ArrayList<>(innerSteps.subList(0, bindIdx + 1));
            // Sort source: remaining inner steps after the binding.
            List<AstNode> sortSourceSteps = new ArrayList<>(
                    innerSteps.subList(bindIdx + 1, innerSteps.size()));
            AstNode sortSource = sortSourceSteps.isEmpty() ? new ContextRef()
                    : sortSourceSteps.size() == 1 ? sortSourceSteps.get(0)
                    : new PathExpr(sortSourceSteps);
            newSteps.add(new SortExpr(sortSource, outerSort.keys()));
            for (int i = 1; i < steps.size(); i++) newSteps.add(steps.get(i));
            return visitPathExpr(t, new PathExpr(newSteps), ctx);
        }

        // If the path has @$var.FieldRef cross-join AND uses %, inject the initial
        // context into parentVars as root so that %.% can navigate back to root.
        if (hasCrossJoinFieldRef(steps) && needsParentTracking(steps, 0)
                && ctx.parentVars.isEmpty()) {
            ctx = ctx.withParents(new ArrayList<>(List.of(ctx.ctxVar)));
        }

        // Handle ParentStep as first step: navigate up via parentVars and
        // adjust the parentVars for subsequent steps.
        String expr;
        int startFrom = 1;
        if (firstStep instanceof ParentStep) {
            if (ctx.parentVars.isEmpty()) {
                throw new RuntimeTranslatorException("S0217", "Parent operator % used with no parent context");
            }
            expr = ctx.parentVars.get(ctx.parentVars.size() - 1);
            List<String> newParents = ctx.parentVars.size() > 1
                    ? new ArrayList<>(ctx.parentVars.subList(0, ctx.parentVars.size() - 1))
                    : new ArrayList<>();
            ctx = ctx.withParents(newParents);
        } else {
            expr = stepExpr(t, firstStep, ctx);
        }
        // Detect cross-join subscript hoisting:
        // When a path has @$var cross-join(s) AND ends with ArraySubscript(pred, n) [ObjectConstructor?],
        // the subscript must apply to the ENTIRE flat cross-join result, not per-element.
        // Rewrite: remove the ArraySubscript, compile the rest (so oc applies per match),
        // then wrap the result in subscript(…, n).
        if (hasContextBinding(steps) && !forceArr) {
            for (int si = 1; si < steps.size(); si++) {
                if (steps.get(si) instanceof ArraySubscript hoistAs
                        && hoistAs.source() instanceof PredicateExpr
                        && si > 0 && (steps.get(si - 1) instanceof ContextBinding
                                       || steps.get(si - 1) instanceof PositionBinding
                                       || steps.get(si - 1) instanceof PredicateExpr)) {
                    // Replace ArraySubscript with its source (PredicateExpr) in the step list,
                    // compile the modified path, then wrap with subscript.
                    List<AstNode> hoistedSteps = new ArrayList<>(steps);
                    hoistedSteps.set(si, hoistAs.source());
                    String innerResult = visitPathExpr(t, new PathExpr(hoistedSteps), ctx);
                    String idxExpr = hoistAs.index().accept(t, ctx);
                    return "subscript(" + innerResult + ", " + idxExpr + ")";
                }
            }
        }

        // Use recursive compile to handle ContextBinding, PositionBinding, ParentStep.
        String result = compilePathSteps(t, steps, startFrom, expr, ctx);
        // When a GroupByExpr(ContextRef) appears as the last step and the path contains
        // binding operators (@$var / #$var), each iteration of the binding loop produces
        // a separate GroupBy object.  Merge all per-iteration objects into one.
        if (pathEndsWithGroupByAfterBinding(steps)) {
            result = "mergeGroupByObjects(" + result + ")";
        }
        return forceArr ? "forceArray(" + result + ")" : result;
    }

    /**
     * Returns {@code true} when the last step is a {@link GroupByExpr} with a
     * {@link ContextRef} source AND an earlier step is a {@link ContextBinding}
     * or {@link PositionBinding}.  In that case every iteration of the binding
     * produces its own GroupBy object, and they must all be merged together.
     */
    private static boolean pathEndsWithGroupByAfterBinding(List<AstNode> steps) {
        if (steps.size() < 2) return false;
        AstNode last = steps.get(steps.size() - 1);
        if (!(last instanceof GroupByExpr gbe && gbe.source() instanceof ContextRef)) return false;
        for (int i = 0; i < steps.size() - 1; i++) {
            if (steps.get(i) instanceof ContextBinding || steps.get(i) instanceof PositionBinding) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the step list contains an {@code @$var} ContextBinding
     * immediately followed by a FieldRef — the pattern that introduces a cross-join
     * where subsequent FieldRef steps navigate from the same parent rather than from
     * the bound variable.
     */
    private static boolean hasCrossJoinFieldRef(List<AstNode> steps) {
        for (int i = 0; i + 1 < steps.size(); i++) {
            if (steps.get(i) instanceof ContextBinding && steps.get(i + 1) instanceof FieldRef) return true;
            // @$var#$pos.FieldRef pattern
            if (steps.get(i) instanceof ContextBinding && i + 2 < steps.size()
                    && steps.get(i + 1) instanceof PositionBinding
                    && steps.get(i + 2) instanceof FieldRef) return true;
            // @$var ^(sort) FieldRef pattern
            if (steps.get(i) instanceof ContextBinding && i + 2 < steps.size()
                    && steps.get(i + 1) instanceof SortExpr se2
                    && se2.source() instanceof ContextRef
                    && steps.get(i + 2) instanceof FieldRef) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the step list contains any {@link ContextBinding} node,
     * indicating that variables bound by {@code @$var} may be used in later predicates.
     */
    private static boolean hasContextBinding(List<AstNode> steps) {
        for (AstNode step : steps) {
            if (step instanceof ContextBinding) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the step list contains any {@link ContextBinding} or
     * {@link PositionBinding} node — either {@code @$var} or {@code #$var}.
     * Used to decide whether an outer PredicateExpr or SortExpr that wraps a path
     * with binding steps needs to be unfolded so the bound variables remain in scope.
     */
    private static boolean hasAnyBinding(List<AstNode> steps) {
        for (AstNode step : steps) {
            if (step instanceof ContextBinding || step instanceof PositionBinding) return true;
        }
        return false;
    }

    /**
     * Recursively compiles path steps from index {@code from} onwards.
     * Parent context is tracked via {@code ctx.parentVars} (a stack of Java variable
     * names for each preceding path level, innermost last).
     */
    private static String compilePathSteps(Translator t, List<AstNode> steps, int from,
                                           String prevExpr, GenCtx ctx) {
        if (from >= steps.size()) return prevExpr;

        AstNode step = steps.get(from);

        String contextBound = compileContextBindingStep(t, steps, from, prevExpr, ctx);
        if (contextBound != null) return contextBound;

        String positionBound = compilePositionBindingStep(t, steps, from, prevExpr, ctx);
        if (positionBound != null) return positionBound;

        if (step instanceof ParentStep) {
            // Navigate up one level in the parent vars stack.
            List<String> parents = ctx.parentVars;
            if (parents.isEmpty()) {
                throw new RuntimeTranslatorException("S0217", "Parent operator % used with no parent context in path");
            }
            String parentExpr = parents.get(parents.size() - 1);
            List<String> newParents = parents.size() > 1
                    ? new ArrayList<>(parents.subList(0, parents.size() - 1))
                    : new ArrayList<>();
            // Guard: if prevExpr is MISSING (e.g. from an empty step before %), propagate MISSING.
            String guardVar = "__gu" + ctx.state.nextId();
            String guardedExpr = "mapStep(" + prevExpr + ", " + guardVar + " -> " + parentExpr + ")";
            return compilePathSteps(t, steps, from + 1, guardedExpr, ctx.withParents(newParents));
        }

        // Normal step.
        // If any remaining step contains a ParentStep, generate a mapStep lambda
        // that introduces a named variable for the current element so it becomes
        // accessible as a "parent" reference (via %) in inner expressions.
        boolean needsTracking = needsParentTracking(steps, from);
        if (needsTracking && step instanceof FieldRef fr) {
            String elemVar = "__el" + ctx.state.nextId();

            if (ctx.crossJoinParent != null) {
                // Cross-join FieldRef: navigate from the cross-join parent (e.g. library),
                // not from prevExpr (which may be a per-element value like a filtered book).
                // prevExpr acts as a gate — if it is MISSING (predicate didn't match) the
                // lambda body is never entered; otherwise we navigate from the fixed parent.
                String parentVar = ctx.crossJoinParent;
                List<String> newParents = new ArrayList<>(ctx.parentVars);
                newParents.add(parentVar);
                String fieldExpr = "field(" + parentVar + ", " + ClassAssembler.javaString(fr.name()) + ")";
                String dummyVar  = "__dc" + ctx.state.nextId();
                // Preserve crossJoinParent for further cross-join navigations in the same scope.
                GenCtx innerCtx = ctx.withCtx(elemVar).withParents(newParents)
                        .withCrossJoinParent(parentVar);
                String restExpr = compilePathSteps(t, steps, from + 1, elemVar, innerCtx);
                return "mapStep(" + prevExpr + ", " + dummyVar + " -> mapStep(" + fieldExpr + ", " + elemVar + " -> " + restExpr + "))";
            }

            // When the next pattern is @$var#$pos (ContextBinding + PositionBinding), use a
            // "parent-only mapStep": capture the parent but don't iterate the field result —
            // the combined @$var#$pos eachIndexed handler will iterate it instead.
            if (from + 1 < steps.size() && steps.get(from + 1) instanceof ContextBinding
                    && from + 2 < steps.size() && steps.get(from + 2) instanceof PositionBinding) {
                String parentVar = "__par" + ctx.state.nextId();
                List<String> newParents = new ArrayList<>(ctx.parentVars);
                newParents.add(parentVar);
                String fieldExpr = "field(" + parentVar + ", " + ClassAssembler.javaString(fr.name()) + ")";
                GenCtx innerCtx = ctx.withCtx(fieldExpr).withParents(newParents).withCrossJoinParent(parentVar);
                String restExpr = compilePathSteps(t, steps, from + 1, fieldExpr, innerCtx);
                return "mapStep(" + prevExpr + ", " + parentVar + " -> " + restExpr + ")";
            }

            // Normal double-mapStep: prevExpr is the sequence of parent elements;
            // the outer lambda captures each parent so % can reference it inside.
            String parentVar = "__par" + ctx.state.nextId();
            List<String> newParents = new ArrayList<>(ctx.parentVars);
            newParents.add(parentVar);
            String fieldExpr = "field(" + parentVar + ", " + ClassAssembler.javaString(fr.name()) + ")";
            GenCtx innerCtx = ctx.withCtx(elemVar).withParents(newParents);
            String restExpr = compilePathSteps(t, steps, from + 1, elemVar, innerCtx);
            // Outer mapStep over prevExpr, inner mapStep over the field result
            return "mapStep(" + prevExpr + ", " + parentVar + " -> mapStep(" + fieldExpr + ", " + elemVar + " -> " + restExpr + "))";
        }

        // Cross-join FieldRef without parent-step tracking: when a cross-join parent is active
        // and prevExpr differs from it (we are "below" the parent level, e.g. inside a filter
        // result from @$var[pred]), navigate from the cross-join parent using prevExpr as a gate.
        // This handles patterns like: library.loans@$l.books@$b[$l.isbn=$b.isbn].customers[...]
        // where .customers must navigate from library, not from the filtered book $b.
        if (!needsTracking && ctx.crossJoinParent != null
                && !ctx.crossJoinParent.equals(prevExpr) && step instanceof FieldRef cjFr) {
            String cjParent  = ctx.crossJoinParent;
            String fieldExpr = "field(" + cjParent + ", " + ClassAssembler.javaString(cjFr.name()) + ")";
            String dummyVar  = "__dc" + ctx.state.nextId();
            GenCtx innerCtx  = ctx.withCrossJoinParent(cjParent);
            String restExpr  = compilePathSteps(t, steps, from + 1, fieldExpr, innerCtx);
            return "mapStep(" + prevExpr + ", " + dummyVar + " -> " + restExpr + ")";
        }

        String newExpr = applyStep(t, prevExpr, step, ctx);
        return compilePathSteps(t, steps, from + 1, newExpr, ctx);
    }

    /**
     * Returns true if any step from {@code from} onwards (or nested within it)
     * contains a {@link ParentStep} that would require parent variable tracking,
     * OR contains a cross-join pattern ({@code @$var} followed by a FieldRef)
     * that requires the parent to be captured as a named variable.
     */
    /** Returns true if any step before {@code upTo} is a ContextBinding or PositionBinding. */
    private static boolean hasOuterBindings(List<AstNode> steps, int upTo) {
        for (int i = 0; i < upTo; i++) {
            if (steps.get(i) instanceof ContextBinding || steps.get(i) instanceof PositionBinding) return true;
        }
        return false;
    }

    private static boolean needsParentTracking(List<AstNode> steps, int from) {
        for (int i = from; i < steps.size(); i++) {
            if (ScopeAnalyzer.containsParentStep(steps.get(i))) return true;
            // Cross-join: @$var followed by FieldRef needs parent captured
            if (steps.get(i) instanceof AstNode.ContextBinding && i + 1 < steps.size()
                    && steps.get(i + 1) instanceof AstNode.FieldRef) return true;
            // @$var#$pos.FieldRef pattern also needs parent captured
            if (steps.get(i) instanceof AstNode.ContextBinding && i + 2 < steps.size()
                    && steps.get(i + 1) instanceof AstNode.PositionBinding
                    && steps.get(i + 2) instanceof AstNode.FieldRef) return true;
            // @$var ^(sort) FieldRef pattern also needs parent captured
            if (steps.get(i) instanceof AstNode.ContextBinding && i + 2 < steps.size()
                    && steps.get(i + 1) instanceof AstNode.SortExpr se3
                    && se3.source() instanceof AstNode.ContextRef
                    && steps.get(i + 2) instanceof AstNode.FieldRef) return true;
        }
        return false;
    }

    /** Returns true if {@code node} is a PathExpr whose last step is an ArrayConstructor. */
    static boolean pathEndsWithArrayConstructor(AstNode node) {
        if (node instanceof AstNode.PathExpr pe) {
            List<AstNode> steps = pe.steps();
            return !steps.isEmpty() && steps.get(steps.size() - 1) instanceof AstNode.ArrayConstructor;
        }
        return false;
    }

    /** Generates an expression for the FIRST step in a path (uses {@code ctx.ctxVar}). */
    private static String stepExpr(Translator t, AstNode step, GenCtx ctx) {
        return switch (step) {
            case FieldRef fr ->
                "field(" + ctx.ctxVar + ", " + ClassAssembler.javaString(fr.name()) + ")";
            // A quoted string as the leading path step is a field reference by name,
            // e.g. "foo".**.bar navigates to the field named "foo" on the context.
            case StringLiteral sl ->
                "field(" + ctx.ctxVar + ", " + ClassAssembler.javaString(sl.value()) + ")";
            case WildcardStep ws  -> "wildcard(" + ctx.ctxVar + ")";
            case DescendantStep ds-> "descendant(" + ctx.ctxVar + ")";
            case ContextRef cr    -> ctx.ctxVar;
            case RootRef rr       -> ctx.rootVar;
            default               -> step.accept(t, ctx);
        };
    }

    /**
     * Generates an expression that applies {@code step} to {@code prevExpr}
     * (used for steps 2 and onwards in a path).
     */
    static String applyStep(Translator t, String prevExpr, AstNode step, GenCtx ctx) {
        return switch (step) {
            case FieldRef fr -> {
                yield "field(" + prevExpr + ", " + ClassAssembler.javaString(fr.name()) + ")";
            }
            case WildcardStep ws   -> "wildcard(" + prevExpr + ")";
            case DescendantStep ds -> "descendant(" + prevExpr + ")";
            case ContextRef cr     -> prevExpr;
            case RootRef rr        -> ctx.rootVar;
            case PredicateExpr pe  -> {
                // Range subscript: arr[[from..to]] — select elements by index range
                if (pe.predicate() instanceof RangeExpr re) {
                    String fromExpr = re.from().accept(t, ctx);
                    String toExpr   = re.to().accept(t, ctx);
                    yield "rangeSubscript(" + prevExpr + ", " + fromExpr + ", " + toExpr + ")";
                }
                // source is prevExpr; predicate uses a fresh element variable
                String elemVar = "__el" + ctx.state.nextId();
                String predExpr = pe.predicate().accept(t, ctx.withCtx(elemVar));
                yield "dynamicFilter(" + prevExpr + ", " + elemVar + " -> " + predExpr + ")";
            }
            case ArraySubscript as -> {
                // Path-step subscript — apply per-element via mapStep so that
                // a.b[n] maps [n] over each element rather than the whole sequence.
                String tmpCtx  = "__c" + ctx.state.nextId();
                String srcExpr = as.source().accept(t, ctx.withCtx(tmpCtx));
                String idxExpr = as.index().accept(t, ctx.withCtx(tmpCtx));
                yield "mapStep(" + prevExpr + ", " + tmpCtx + " -> subscript(" + srcExpr + ", " + idxExpr + "))";
            }
            case ArrayConstructor ac -> {
                // e.g. Email.[address] — map per element.
                // In preserve mode ($.[arr][] pattern): keep each result array as a single item.
                // In default mode ($.[arr]): unwrap collapses a single-element outer array.
                String tmpCtx  = "__c" + ctx.state.nextId();
                String stepExpr = ac.accept(t, ctx.withCtx(tmpCtx).withInArrayConstructorStep());
                String call = "mapConstructorStep(" + prevExpr + ", " + tmpCtx + " -> " + stepExpr + ")";
                yield ctx.arrayConstructorPreserve ? call : "unwrap(" + call + ")";
            }
            case ObjectConstructor oc -> {
                // e.g. Phone.{type: number} — map per element, collect without flattening.
                // When tuplePos is set the elements are (item, $pos) tuples produced by a
                // position-aware global sort; unpack them in a block lambda.
                if (ctx.tuplePos != null) {
                    String tmpTuple = "__c" + ctx.state.nextId();
                    String tupleElem = "__te" + ctx.state.nextId();
                    String tPos = ctx.tuplePos;
                    String stepExpr = oc.accept(t, ctx.withCtx(tupleElem).withTuplePos(null));
                    yield "unwrap(mapConstructorStep(" + prevExpr + ", " + tmpTuple + " -> { "
                            + "JsonNode " + tPos + " = " + tmpTuple + ".isArray() ? " + tmpTuple + ".get(1) : MISSING; "
                            + "JsonNode " + tupleElem + " = " + tmpTuple + ".isArray() ? " + tmpTuple + ".get(0) : " + tmpTuple + "; "
                            + "return " + stepExpr + "; }))";
                }
                String tmpCtx  = "__c" + ctx.state.nextId();
                String stepExpr = oc.accept(t, ctx.withCtx(tmpCtx));
                yield "unwrap(mapConstructorStep(" + prevExpr + ", " + tmpCtx + " -> " + stepExpr + "))";
            }
            case GroupByExpr gbe when gbe.source() instanceof ContextRef -> {
                // GroupByExpr(ContextRef) appears as a path step only when the Optimizer's
                // Rule D has rewritten a binding-based GroupBy (e.g. @$e.@$c.{key:val}).
                // The GroupBy must receive the ENTIRE accumulated sequence, not be applied
                // per-element via mapStep — otherwise aggregation functions like $join
                // would only see individual elements rather than the whole group.
                yield gbe.accept(t, ctx.withCtx(prevExpr));
            }
            default -> {
                // For any other step type: rebind __ctx to prevExpr inside a lambda.
                String tmpCtx = "__c" + ctx.state.nextId();
                GenCtx innerCtx;
                if (ScopeAnalyzer.containsParentStep(step) && ctx.parentVars.isEmpty()) {
                    // No parent tracking has been established yet (e.g. first path step is
                    // a non-FieldRef that uses %).  Make the outer ctxVar available as the
                    // parent level so % can resolve inside the step.
                    innerCtx = ctx.withCtx(tmpCtx).withParents(List.of(ctx.ctxVar));
                } else {
                    // Parent tracking already established via double-mapStep — preserve it.
                    innerCtx = ctx.withCtx(tmpCtx);
                }
                String stepExpr = step.accept(t, innerCtx);
                yield "mapStep(" + prevExpr + ", " + tmpCtx + " -> " + stepExpr + ")";
            }
        };
    }

    static String visitPredicateExpr(Translator t, PredicateExpr n, GenCtx ctx) {
        // If source is a ForceArray (expr[]) the result must remain an array
        // even when only one element passes the predicate.
        AstNode sourceNode = n.source();
        boolean forceArr = sourceNode instanceof ForceArray;
        if (forceArr) sourceNode = ((ForceArray) sourceNode).source();
        // When the source (possibly unwrapped from ForceArray) is a PathExpr whose last
        // step is a ContextBinding (@$var) or PositionBinding (#$var), fold the predicate
        // as a path step so that the binding variable is in scope while the predicate is
        // compiled.  Without folding the scope is pushed/popped during source compilation
        // and $var is no longer visible when the predicate expression is visited.
        // E.g.: $#$pos[][$pos<3] — the predicate [$pos<3] must be folded into the path
        //       PathExpr([$, #$pos, PredicateExpr(ContextRef, $pos<3)]).
        if (sourceNode instanceof PathExpr innerPath && !innerPath.steps().isEmpty()) {
            AstNode lastStep = innerPath.steps().get(innerPath.steps().size() - 1);
            if (lastStep instanceof PositionBinding || lastStep instanceof ContextBinding) {
                List<AstNode> newSteps = new ArrayList<>(innerPath.steps());
                newSteps.add(new PredicateExpr(new ContextRef(), n.predicate()));
                AstNode newSource = new PathExpr(newSteps);
                String result = (forceArr ? new ForceArray(newSource) : newSource).accept(t, ctx);
                return result;
            }
        }
        // Range subscript: arr[[from..to]] — select elements by index range
        if (n.predicate() instanceof RangeExpr re) {
            // $.$[[from..to]] spread pattern: source is a PathExpr ending with ContextRef
            // (the $. part), meaning apply the range per-element rather than to the
            // collected sequence.  Each scalar element is treated as a 1-element sequence
            // at position 0; rangeSubscript returns it when 0 is in [from, to].
            if (!forceArr && sourceNode instanceof PathExpr spreadPath
                    && !spreadPath.steps().isEmpty()
                    && spreadPath.steps().get(spreadPath.steps().size() - 1) instanceof ContextRef) {
                List<AstNode> baseSteps = spreadPath.steps().subList(0, spreadPath.steps().size() - 1);
                AstNode baseSource = baseSteps.isEmpty() ? new ContextRef()
                        : baseSteps.size() == 1 ? baseSteps.get(0) : new PathExpr(baseSteps);
                String spreadExpr = baseSource.accept(t, ctx);
                String fromExpr   = re.from().accept(t, ctx);
                String toExpr     = re.to().accept(t, ctx);
                String elemVar    = "__me" + ctx.state.nextId();
                return "mapStep(" + spreadExpr + ", " + elemVar
                        + " -> rangeSubscript(" + elemVar + ", " + fromExpr + ", " + toExpr + "))";
            }
            String srcExpr  = sourceNode.accept(t, ctx);
            String fromExpr = re.from().accept(t, ctx);
            String toExpr   = re.to().accept(t, ctx);
            String r = "rangeSubscript(" + srcExpr + ", " + fromExpr + ", " + toExpr + ")";
            return forceArr ? "forceArray(" + r + ")" : r;
        }
        // When the predicate references %, fold this predicate (and any inner predicates
        // from a nested PredicateExpr chain) as path steps on the base PathExpr so that
        // compilePathSteps can set up proper parent tracking via double-mapStep.
        if (ScopeAnalyzer.containsParentStep(n.predicate())) {
            List<AstNode> collectedPreds = new ArrayList<>();
            PathExpr basePath = extractBasePathAndPredicates(sourceNode, collectedPreds);
            if (basePath != null) {
                collectedPreds.add(n.predicate()); // outermost predicate last
                List<AstNode> newSteps = new ArrayList<>(basePath.steps());
                for (AstNode pred : collectedPreds) {
                    newSteps.add(new PredicateExpr(new ContextRef(), pred));
                }
                String result = new PathExpr(newSteps).accept(t, ctx);
                return forceArr ? "forceArray(" + result + ")" : result;
            }
        }
        String srcExpr  = sourceNode.accept(t, ctx);
        String elemVar  = "__el" + ctx.state.nextId();
        String predExpr = n.predicate().accept(t, ctx.withCtx(elemVar));
        // Use filter() directly for statically boolean predicates — avoids the probe
        // call (predicate.apply(MISSING)) that dynamicFilter() uses to detect subscript mode.
        String filterFn = isStaticBooleanPredicate(n.predicate()) ? "filter" : "dynamicFilter";
        String r = filterFn + "(" + srcExpr + ", " + elemVar + " -> " + predExpr + ")";
        return forceArr ? "forceArray(" + r + ")" : r;
    }

    /**
     * Returns {@code true} if the predicate is statically guaranteed to produce a boolean
     * (never a number), so {@code filter()} can be used instead of {@code dynamicFilter()}.
     */
    private static boolean isStaticBooleanPredicate(AstNode node) {
        return switch (node) {
            case BinaryOp bo -> switch (bo.op()) {
                case "=", "!=", "<", ">", "<=", ">=", "in", "and", "or" -> true;
                default -> false;
            };
            case BooleanLiteral ignored -> true;
            case FunctionCall fc -> switch (fc.name()) {
                case "boolean", "not", "exists", "contains" -> true;
                default -> false;
            };
            case Parenthesized p -> isStaticBooleanPredicate(p.inner());
            default -> false;
        };
    }

    /**
     * Recursively extracts the base {@link PathExpr} and collects predicates from a
     * chain of nested {@link PredicateExpr} nodes.  The predicates are appended in
     * inside-out order (innermost first) so they can be re-attached as ordered path
     * steps.  Returns {@code null} if the innermost source is not a {@link PathExpr}.
     */
    private static PathExpr extractBasePathAndPredicates(AstNode source, List<AstNode> collectedPreds) {
        if (source instanceof PathExpr pe) return pe;
        if (source instanceof PredicateExpr pe) {
            PathExpr base = extractBasePathAndPredicates(pe.source(), collectedPreds);
            if (base != null) {
                collectedPreds.add(pe.predicate());
                return base;
            }
        }
        return null;
    }

    /**
     * Compiles a context binding step ({@code @$v}) and everything after it.
     *
     * <p>The binding names the current element so later steps — and the cross-join form
     * {@code @$v.(…)}, which evaluates its parenthesised expression from the document root — can
     * refer back to it.
     *
     * @return the compiled expression, or {@code null} if this step is not a context binding
     */
    private static String compileContextBindingStep(Translator t, List<AstNode> steps, int from,
                                                    String prevExpr, GenCtx ctx) {
        if (!(steps.get(from) instanceof ContextBinding cb)) return null;
        String varName = "$" + cb.varName();
        ctx.state.pushScope();
        ctx.state.addLocalVar(cb.varName());
        try {
            if (from + 1 < steps.size() && steps.get(from + 1) instanceof Parenthesized p) {
                // Cross-join: the parenthesized expression evaluates from document root.
                String innerExpr = p.inner().accept(t, ctx.withCtx(ctx.rootVar));
                GenCtx innerCtx = ctx.withCtx(varName).withParents(new ArrayList<>(List.of(varName)));
                String restExpr = compilePathSteps(t, steps, from + 2, innerExpr, innerCtx);
                return "mapStep(" + prevExpr + ", " + varName + " -> " + restExpr + ")";
            } else if (from + 1 < steps.size() && steps.get(from + 1) instanceof PositionBinding pb2
                    && from + 2 < steps.size() && steps.get(from + 2) instanceof FieldRef) {
                // @$var#$pos.FieldRef cross-join: combine @$var and #$pos into a single
                // eachIndexed call so that (a) positions are correct and (b) the FieldRef
                // navigates from the cross-join parent (not from the individual element).
                String idxVar = "$" + pb2.varName();
                ctx.state.addLocalVar(pb2.varName());
                String pairVar = "__pair" + ctx.state.nextId();
                String cjp = ctx.crossJoinParent != null ? ctx.crossJoinParent
                        : (ctx.parentVars.isEmpty() ? varName
                           : ctx.parentVars.get(ctx.parentVars.size() - 1));
                GenCtx innerCtx = ctx.withCtx(varName).withCrossJoinParent(cjp);
                // Compile from the FieldRef step (from+2) with prevExpr=cjp
                String restExpr = compilePathSteps(t, steps, from + 2, cjp, innerCtx);
                String lambdaBody = "(" + pairVar + " -> { JsonNode " + varName + " = " + pairVar + ".isArray() ? " + pairVar + ".get(0) : " + pairVar + "; "
                        + "JsonNode " + idxVar + " = " + pairVar + ".isArray() ? " + pairVar + ".get(1) : number(0L); "
                        + "return " + restExpr + "; })";
                return "eachIndexed(" + prevExpr + ", " + lambdaBody + ")";
            } else if (from + 1 < steps.size() && steps.get(from + 1) instanceof PositionBinding pb3) {
                // @$var#$pos without cross-join FieldRef: combine into single eachIndexed
                // so that positions are correct (mapStep+eachIndexed would always give index 0).
                String idxVar3 = "$" + pb3.varName();
                ctx.state.addLocalVar(pb3.varName());
                String pairVar3 = "__pair" + ctx.state.nextId();
                List<String> poppedParents3 = ctx.parentVars.isEmpty() ? List.of()
                        : new ArrayList<>(ctx.parentVars.subList(0, ctx.parentVars.size() - 1));
                GenCtx innerCtx3 = ctx.withCtx(varName).withParents(poppedParents3)
                        .withCrossJoinParent(ctx.crossJoinParent);
                String restExpr3 = compilePathSteps(t, steps, from + 2, varName, innerCtx3);
                String lambdaBody3 = "(" + pairVar3 + " -> { JsonNode " + varName + " = " + pairVar3 + ".isArray() ? " + pairVar3 + ".get(0) : " + pairVar3 + "; "
                        + "JsonNode " + idxVar3 + " = " + pairVar3 + ".isArray() ? " + pairVar3 + ".get(1) : number(0L); "
                        + "return " + restExpr3 + "; })";
                return "eachIndexed(" + prevExpr + ", " + lambdaBody3 + ")";
            } else if (from + 1 < steps.size() && steps.get(from + 1) instanceof FieldRef) {
                // Cross-join: @$var followed by a FieldRef means the FieldRef navigates
                // from the cross-join parent (last in parentVars), not from each $var.
                // Use the last parentVar as the navigation base (= library in the tests).
                String cjp = ctx.parentVars.isEmpty()
                        ? varName
                        : ctx.parentVars.get(ctx.parentVars.size() - 1);
                // innerCtx preserves existing parentVars and sets crossJoinParent so
                // subsequent FieldRef steps also navigate from the cross-join base.
                GenCtx innerCtx = ctx.withCtx(varName).withCrossJoinParent(cjp);
                String restExpr = compilePathSteps(t, steps, from + 1, cjp, innerCtx);
                return "mapStep(" + prevExpr + ", " + varName + " -> " + restExpr + ")";
            } else if (from + 1 < steps.size() && steps.get(from + 1) instanceof SortExpr se
                    && se.source() instanceof ContextRef) {
                // @$var ^(sort) more_steps: sort the WHOLE source (prevExpr) first, then map.
                // This handles Employee@$e^($e.Surname).Contact where Contact navigates from root.
                String sortedExpr = prevExpr;
                for (int ki = se.keys().size() - 1; ki >= 0; ki--) {
                    SortKey sk = se.keys().get(ki);
                    String keyVar  = "__sk" + ctx.state.nextId();
                    // Alias $var → keyVar so sort keys like $e.Surname compile as field(keyVar, "Surname")
                    ctx.state.pushScope();
                    ctx.state.addLocalVarWithAlias(cb.varName(), keyVar);
                    String keyExpr = sk.key().accept(t, ctx.withCtx(keyVar));
                    ctx.state.popScope();
                    String sorted  = "fn_sort(" + sortedExpr + ", " + keyVar + " -> " + keyExpr + ")";
                    sortedExpr = sk.descending() ? "fn_reverse(" + sorted + ")" : sorted;
                }
                if (from + 2 < steps.size() && steps.get(from + 2) instanceof FieldRef) {
                    // Cross-join: FieldRef after sort navigates from cross-join parent (root)
                    String cjp2 = ctx.parentVars.isEmpty()
                            ? ctx.ctxVar
                            : ctx.parentVars.get(ctx.parentVars.size() - 1);
                    GenCtx innerCtx2 = ctx.withCtx(varName).withCrossJoinParent(cjp2);
                    String restExpr2 = compilePathSteps(t, steps, from + 2, cjp2, innerCtx2);
                    return "mapStep(" + sortedExpr + ", " + varName + " -> " + restExpr2 + ")";
                } else if (from + 2 < steps.size()) {
                    // Non-cross-join: remaining steps navigate from $var
                    List<String> poppedParents2 = ctx.parentVars.isEmpty() ? List.of()
                            : new ArrayList<>(ctx.parentVars.subList(0, ctx.parentVars.size() - 1));
                    GenCtx innerCtx2 = ctx.withCtx(varName).withParents(poppedParents2)
                            .withCrossJoinParent(ctx.crossJoinParent);
                    String restExpr2 = compilePathSteps(t, steps, from + 2, varName, innerCtx2);
                    return "mapStep(" + sortedExpr + ", " + varName + " -> " + restExpr2 + ")";
                } else {
                    // Only sort, nothing follows — just return the sorted expr
                    return "unwrap(" + sortedExpr + ")";
                }
            } else {
                // Regular @$var binding (no cross-join FieldRef follows).
                // Special case: @$var [pred?] {GroupBy(ContextRef)} as the last steps.
                // Instead of per-element mapStep, filter the whole collection and pass to
                // GroupBy so that aggregation functions see the full group at once.
                {
                    int ni = from + 1;
                    PredicateExpr predStep = null;
                    if (ni < steps.size() && steps.get(ni) instanceof PredicateExpr pe
                            && pe.source() instanceof ContextRef) {
                        predStep = pe;
                        ni++;
                    }
                    if (ni == steps.size() - 1
                            && steps.get(ni) instanceof GroupByExpr gbe
                            && gbe.source() instanceof ContextRef) {
                        // Filter the collection first (without per-element mapStep)
                        String filteredExpr = prevExpr;
                        if (predStep != null) {
                            String predExpr = predStep.predicate().accept(t, ctx.withCtx(varName));
                            filteredExpr = "dynamicFilter(" + prevExpr + ", " + varName + " -> " + predExpr + ")";
                        }
                        // Compile GroupBy with the filtered collection as source context,
                        // and tell it that varName should be rebound to the group element.
                        GenCtx gbCtx = ctx.withCtx(filteredExpr).withPrimaryContextVar(varName);
                        return gbe.accept(t, gbCtx);
                    }
                }
                // Pop one level from parentVars: this @$var ends one cross-join nesting
                // level so that %.% can navigate back one additional step.
                List<String> poppedParents = ctx.parentVars.isEmpty() ? List.of()
                        : new ArrayList<>(ctx.parentVars.subList(0, ctx.parentVars.size() - 1));
                GenCtx innerCtx = ctx.withCtx(varName).withParents(poppedParents)
                        .withCrossJoinParent(ctx.crossJoinParent);
                String restExpr = compilePathSteps(t, steps, from + 1, varName, innerCtx);
                return "mapStep(" + prevExpr + ", " + varName + " -> " + restExpr + ")";
            }
        } finally {
            ctx.state.popScope();
        }
    }

    /**
     * Compiles a positional binding step ({@code #$i}) and everything after it.
     *
     * <p>{@code eachIndexed} hands the callback {@code [element, index]} pairs; the generated lambda
     * unpacks them so the remaining steps see the element as context and the index under its name.
     *
     * @return the compiled expression, or {@code null} if this step is not a positional binding
     */
    private static String compilePositionBindingStep(Translator t, List<AstNode> steps, int from,
                                                     String prevExpr, GenCtx ctx) {
        if (!(steps.get(from) instanceof PositionBinding pb)) return null;
        // eachIndexed passes [element, index] pairs to the lambda.
        // We inline the lambda using a block expression to unpack the pair
        // and then compile the remaining steps.
        String elemVar = "__pe"   + ctx.state.nextId();
        String idxVar  = "$" + pb.varName();
        ctx.state.pushScope();
        ctx.state.addLocalVar(pb.varName());
        try {
            // Push current context as parent for the elem
            List<String> newParents = new ArrayList<>(ctx.parentVars);
            newParents.add(prevExpr);
            GenCtx innerCtx = ctx.withCtx(elemVar).withParents(newParents);

            // Detect the $#$pos[pred][n] pattern:
            //   steps[from+1] = ArraySubscript(source=PredicateExpr(...), index=numericExpr)
            // In this case the predicate filter must be applied per-element INSIDE the
            // eachIndexed lambda (because it may reference $pos), but the numeric subscript
            // [n] must be applied to the COLLECTED sequence OUTSIDE.
            // If we included the full ArraySubscript inside the lambda it would subscript each
            // scalar individually, always producing MISSING.
            int innerEndFrom = from + 1;
            String postCollectSubscriptIdx = null; // idxExpr for outside subscript, or null
            int outerStepStart = from + 1;         // first step to compile after eachIndexed
            if (from + 1 < steps.size()
                    && steps.get(from + 1) instanceof ArraySubscript splitAs
                    && splitAs.source() instanceof PredicateExpr) {
                // Compile the predicate source as the restExpr for the lambda.
                // The source (PredicateExpr) applied to innerCtx produces e.g.
                // dynamicFilter(__pe0, __el -> lt($pos, 3)), which is correct per-element.
                String innerRestExpr = splitAs.source().accept(t, innerCtx);
                postCollectSubscriptIdx = splitAs.index().accept(t, innerCtx);
                outerStepStart = from + 2; // steps after the ArraySubscript go outside
                String pairVar = "__pair" + ctx.state.nextId();
                String lambdaBody = "(" + pairVar + " -> { JsonNode " + elemVar + " = " + pairVar + ".isArray() ? " + pairVar + ".get(0) : " + pairVar + "; "
                        + "JsonNode " + idxVar + " = " + pairVar + ".isArray() ? " + pairVar + ".get(1) : number(0L); "
                        + "return " + innerRestExpr + "; })";
                boolean perElement = from >= 2 && steps.get(from - 1) instanceof ContextRef;
                String eachResult;
                if (perElement) {
                    String mapElem = "__me" + ctx.state.nextId();
                    eachResult = "mapStep(" + prevExpr + ", " + mapElem + " -> eachIndexed(" + mapElem + ", " + lambdaBody + "))";
                } else {
                    eachResult = "eachIndexed(" + prevExpr + ", " + lambdaBody + ")";
                }
                String subscriptResult = "subscript(" + eachResult + ", " + postCollectSubscriptIdx + ")";
                return compilePathSteps(t, steps, outerStepStart, subscriptResult, ctx);
            }

            // #$pos.SortExpr.rest pattern: sort must be global (across ALL elements),
            // not per-element. Build (sortSource, $pos) tuples via collectPosTuples,
            // sort globally, then compile remaining steps with tuplePos so
            // ObjectConstructor unpacks [sortItem, $pos] tuples.
            if (from + 1 < steps.size() && steps.get(from + 1) instanceof SortExpr se) {
                String pairVar2 = "__pair" + ctx.state.nextId();
                // Compile sort source in context of individual element (elemVar = pair[0])
                String sortSrcExpr = se.source().accept(t, innerCtx);
                // collectPosTuples: for each (elem[i], i), apply lambda to get sort source
                // and package [sortItem, i] without flattening.
                String tupleCollect = "collectPosTuples(" + prevExpr + ", (" + pairVar2 + " -> { "
                        + "JsonNode " + elemVar + " = " + pairVar2 + ".isArray() ? " + pairVar2 + ".get(0) : " + pairVar2 + "; "
                        + "JsonNode " + idxVar + " = " + pairVar2 + ".isArray() ? " + pairVar2 + ".get(1) : number(0L); "
                        + "return " + sortSrcExpr + "; }))";
                // Sort tuples globally by each sort key (applied to tuple.get(0))
                String result = tupleCollect;
                for (int i = se.keys().size() - 1; i >= 0; i--) {
                    SortKey sk = se.keys().get(i);
                    String tkVar = "__tk" + ctx.state.nextId();
                    String keyExpr = sk.key().accept(t, ctx.withCtx(tkVar + ".get(0)"));
                    String sorted = "fn_sort(" + result + ", " + tkVar + " -> " + keyExpr + ")";
                    result = sk.descending() ? "fn_reverse(" + sorted + ")" : sorted;
                }
                // Compile remaining steps with tuplePos so ObjectConstructor unpacks tuples
                return compilePathSteps(t, steps, from + 2, result, ctx.withTuplePos(idxVar));
            }

            // When #$pos follows a PredicateExpr inside outer loops (cross-join context),
            // the position must be global across all outer iterations, not per-filter-result.
            // Declare a counter array in the evaluate() method body and increment per match.
            if (from > 0 && steps.get(from - 1) instanceof PredicateExpr
                    && hasOuterBindings(steps, from - 1)) {
                String ctrVar = "__ctr" + ctx.state.nextId();
                ctx.state.localDeclarations.append("final long[] " + ctrVar + " = {0};\n        ");
                // prevExpr is a filter result (0 or 1 elements); use mapStep (not eachIndexed)
                // so MISSING → MISSING and a present element invokes the lambda once with the counter.
                String mapElem = "__me" + ctx.state.nextId();
                GenCtx innerCtxCtr = ctx.withCtx(mapElem).withParents(newParents);
                String restExprCtr = compilePathSteps(t, steps, from + 1, mapElem, innerCtxCtr);
                return "mapStep(" + prevExpr + ", " + mapElem + " -> { JsonNode " + idxVar + " = number(" + ctrVar + "[0]++); return " + restExprCtr + "; })";
            }

            String restExpr = compilePathSteps(t, steps, from + 1, elemVar, innerCtx);
            // Inline lambda: (__pairN -> { elem = pair.get(0); $var = pair.get(1); return rest; })
            // Use a unique name for the pair parameter to avoid Java shadowing errors when
            // multiple PositionBindings appear in nested scope (e.g. @$l#$il.@$b#$ib).
            String pairVar = "__pair" + ctx.state.nextId();
            String lambdaBody = "(" + pairVar + " -> { JsonNode " + elemVar + " = " + pairVar + ".isArray() ? " + pairVar + ".get(0) : " + pairVar + "; "
                    + "JsonNode " + idxVar + " = " + pairVar + ".isArray() ? " + pairVar + ".get(1) : number(0L); "
                    + "return " + restExpr + "; })";
            // $.$#$pos pattern: when preceded by a ContextRef (the $. spread), apply
            // eachIndexed per-element so each element gets its own position starting at 0.
            boolean perElement = from >= 2 && steps.get(from - 1) instanceof ContextRef;
            if (perElement) {
                String mapElem = "__me" + ctx.state.nextId();
                return "mapStep(" + prevExpr + ", " + mapElem + " -> eachIndexed(" + mapElem + ", " + lambdaBody + "))";
            }
            return "eachIndexed(" + prevExpr + ", " + lambdaBody + ")";
        } finally {
            ctx.state.popScope();
        }
    }
}
