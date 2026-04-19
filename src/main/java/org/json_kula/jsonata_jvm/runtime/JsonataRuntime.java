package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataBoundFunction;

import java.util.*;
import java.util.function.Supplier;

/**
 * Runtime support library for generated JSONata expression classes.
 *
 * <p>All methods are static. Generated classes import this class with a static
 * wildcard import ({@code import static org.json_kula.jsonata_jvm.runtime.JsonataRuntime.*;}) so
 * the call sites read as plain function calls.
 *
 * <h2>Sequence semantics</h2>
 * <p>JSONata treats the document as a stream of values. Many operations
 * automatically map over arrays ("sequence mapping"). The {@link #field},
 * {@link #wildcard}, and {@link #descendant} methods implement this by
 * recursively visiting every element of an array input and collecting results.
 *
 * <h2>Undefined / missing values</h2>
 * <p>JSONata's concept of "undefined" is represented by Jackson's
 * {@link MissingNode}. Operations on missing values propagate the missing
 * value rather than throwing (arithmetic excepted — see individual methods).
 */
public final class JsonataRuntime {

    private JsonataRuntime() {}

    static final JsonNodeFactory NF = JsonNodeFactory.instance;

    /** The JSONata {@code null} literal. */
    public static final JsonNode NULL    = NullNode.getInstance();

    /** The JSONata "undefined" sentinel (a value that is absent). */
    public static final JsonNode MISSING = MissingNode.getInstance();

    /**
     * Delegate for {@code $eval()} — evaluates a JSONata expression string at runtime.
     * Registered by {@code JsonataExpressionFactory} so the runtime package does not
     * create a circular dependency on the top-level package.
     */
    @FunctionalInterface
    public interface EvalDelegate {
        JsonNode eval(String expr, JsonNode context) throws RuntimeEvaluationException;
    }

    private static volatile EvalDelegate EVAL_DELEGATE = null;

    /** Registers the delegate used by {@code $eval()}. */
    public static void registerEvalDelegate(EvalDelegate delegate) {
        EVAL_DELEGATE = delegate;
    }

    /** Returns the currently registered eval delegate, or {@code null} if none. */
    static EvalDelegate getEvalDelegate() {
        return EVAL_DELEGATE;
    }

    // =========================================================================
    // Factory helpers
    // =========================================================================

    public static JsonNode text(String s)   { return NF.textNode(s); }
    public static JsonNode number(double v) { return NF.numberNode(v); }
    public static JsonNode number(long v)   { return NF.numberNode(v); }
    public static JsonNode bool(boolean v)  { return NF.booleanNode(v); }

    // =========================================================================
    // Path navigation
    // =========================================================================

    /**
     * Navigates to {@code name} field on {@code node}, automatically mapping
     * over arrays (JSONata sequence semantics).
     */
    public static JsonNode field(JsonNode node, String name) {
        if (node == null || node.isMissingNode() || node.isNull()) return MISSING;
        if (node.isArray()) {
            ArrayNode result = NF.arrayNode(node.size());
            for (JsonNode elem : node) {
                JsonNode val = field(elem, name);
                if (!val.isMissingNode()) appendToSequence(result, val);
            }
            return unwrap(result);
        }
        if (node.isObject()) {
            JsonNode val = node.get(name);
            return val != null ? val : MISSING;
        }
        return MISSING;
    }

    /**
     * Like {@link #field} but does NOT flatten array results. Used for array/object
     * constructor steps where we need to preserve each element's result as-is.
     */
    public static JsonNode fieldPreserve(JsonNode node, String name) {
        if (node == null || node.isMissingNode() || node.isNull()) return MISSING;
        if (node.isArray()) {
            ArrayNode result = NF.arrayNode();
            for (JsonNode elem : node) {
                JsonNode val = fieldPreserve(elem, name);
                if (!val.isMissingNode()) result.add(val);
            }
            return unwrap(result);
        }
        if (node.isObject()) {
            JsonNode val = node.get(name);
            return val != null ? val : MISSING;
        }
        return MISSING;
    }

    /** Returns all field values of an object, or maps over an array. */
    public static JsonNode wildcard(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return MISSING;
        if (node.isArray()) {
            ArrayNode result = NF.arrayNode();
            for (JsonNode elem : node) {
                if (elem.isObject()) {
                    appendToSequence(result, wildcard(elem));
                } else if (!elem.isMissingNode()) {
                    // Primitive array elements are returned as-is
                    result.add(elem);
                }
            }
            return unwrap(result);
        }
        if (node.isObject()) {
            ArrayNode result = NF.arrayNode();
            node.fields().forEachRemaining(e -> appendToSequence(result, e.getValue()));
            return unwrap(result);
        }
        return MISSING;
    }

    /** Recursively collects all descendant values (depth-first). */
    public static JsonNode descendant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return MISSING;
        ArrayNode result = NF.arrayNode();
        collectDescendants(node, result);
        return unwrap(result);
    }

    private static void collectDescendants(JsonNode node, ArrayNode acc) {
        if (node.isArray()) {
            for (JsonNode elem : node) collectDescendants(elem, acc);
        } else if (node.isObject() && node.size() > 0) {
            acc.add(node);
            node.fields().forEachRemaining(e -> collectDescendants(e.getValue(), acc));
        }
    }

    /**
     * Forces {@code node} to be an array, wrapping it in a single-element
     * array if it is not already one.  Implements the {@code expr[]} operator.
     * MISSING propagates as MISSING (nothing to wrap).
     */
    public static JsonNode forceArray(JsonNode node) {
        if (node == null || node.isMissingNode()) return MISSING;
        if (node.isArray()) return node;
        ArrayNode result = NF.arrayNode();
        result.add(node);
        return result;
    }

    /**
     * Filters {@code seq} by {@code predicate}, preserving elements for which
     * the predicate returns a truthy value.
     */
    public static JsonNode filter(JsonNode seq, JsonataLambda predicate)
            throws RuntimeEvaluationException {
        if (seq == null || seq.isMissingNode()) return MISSING;
        if (!seq.isArray()) {
            return isTruthy(predicate.apply(seq)) ? seq : MISSING;
        }
        ArrayNode result = NF.arrayNode(seq.size());
        for (JsonNode elem : seq) {
            if (isTruthy(predicate.apply(elem))) result.add(elem);
        }
        return unwrap(result);
    }

    /**
     * Dynamic filter: probes the predicate with MISSING to determine mode.
     * If the result is a number → index subscript; otherwise → boolean filter.
     * This implements JSONata semantics where {@code arr[expr]} can be either
     * a positional subscript or a filter depending on what {@code expr} evaluates to.
     * <p>
     * Rationale: numeric expressions like {@code 5*0.2} or variable references
     * like {@code $n} (bound to a number) are context-independent so they return
     * the same value regardless of which element they are applied to — including
     * MISSING. Boolean/path expressions applied to MISSING return MISSING or a
     * boolean, never a number, so they fall through to filter mode.
     */
    public static JsonNode dynamicFilter(JsonNode seq, JsonataLambda predicate)
            throws RuntimeEvaluationException {
        if (seq == null || seq.isMissingNode()) return MISSING;
        JsonNode probe = predicate.apply(MISSING);
        if (probe != null && !probe.isMissingNode()) {
            if (probe.isNumber()) return subscript(seq, probe);
            if (probe.isArray()) {
                // Check if all elements are integers; if not, fall through to filter mode
                boolean allInts = true;
                for (JsonNode idx : probe) {
                    if (!idx.isNumber()) { allInts = false; break; }
                }
                if (allInts) {
                    // Multi-index subscript: sort indices to preserve natural array order
                    int size = seq.isArray() ? seq.size() : 1;
                    java.util.List<Integer> indices = new java.util.ArrayList<>();
                    for (JsonNode idx : probe) {
                        int i = (int) idx.doubleValue();
                        int actual = i < 0 ? size + i : i;
                        if (actual >= 0 && actual < size && !indices.contains(actual)) {
                            indices.add(actual);
                        }
                    }
                    java.util.Collections.sort(indices);
                    ArrayNode result = NF.arrayNode();
                    for (int i : indices) {
                        JsonNode val = seq.isArray() ? seq.get(i) : (i == 0 ? seq : MISSING);
                        if (!missing(val)) result.add(val);
                    }
                    return unwrap(result);
                }
            }
        }
        return filter(seq, predicate);
    }

    /**
     * Returns the element at {@code index} (zero-based, negatives count from end).
     * If {@code index} is a non-integer JsonNode, coerces to int.
     */
    public static JsonNode subscript(JsonNode seq, JsonNode index)
            throws RuntimeEvaluationException {
        if (seq == null || seq.isMissingNode()) return MISSING;
        int i = (int) toNumber(index);
        if (!seq.isArray()) {
            return i == 0 || i == -1 ? seq : MISSING;
        }
        int size = seq.size();
        int actual = i < 0 ? size + i : i;
        return (actual >= 0 && actual < size) ? seq.get(actual) : MISSING;
    }

    /**
     * Returns a sub-array containing elements at indices {@code from} through
     * {@code to} (inclusive, zero-based, negatives count from end).
     * Used for the {@code arr[[from..to]]} range-subscript syntax.
     */
    public static JsonNode rangeSubscript(JsonNode seq, JsonNode from, JsonNode to)
            throws RuntimeEvaluationException {
        if (seq == null || seq.isMissingNode()) return MISSING;
        if (!seq.isArray()) {
            // Treat scalar as a single-element sequence at position 0 (or -1 as last).
            int f = (int) toNumber(from);
            int t = (int) toNumber(to);
            int normF = f < 0 ? 1 + f : f;   // size=1: -1→0, anything more negative → out of range
            int normT = t < 0 ? 1 + t : t;
            return (normF <= 0 && normT >= 0) ? seq : MISSING;
        }
        int f = (int) toNumber(from);
        int t = (int) toNumber(to);
        int size = seq.size();
        int actualF = f < 0 ? size + f : f;
        int actualT = t < 0 ? size + t : t;
        ArrayNode result = NF.arrayNode();
        for (int i = Math.max(0, actualF); i <= Math.min(size - 1, actualT); i++) {
            result.add(seq.get(i));
        }
        return unwrap(result);
    }

    /**
     * Applies {@code fn} with the element as the new context. Used by the
     * translator for complex path steps where context must be rebound.
     */
    public static JsonNode applyStep(JsonNode node, JsonataLambda fn)
            throws RuntimeEvaluationException {
        if (node == null || node.isMissingNode()) return MISSING;
        return fn.apply(node);
    }

    /**
     * Maps {@code fn} over every element of a sequence, collecting non-missing
     * results. Used by the translator for subscript steps inside path expressions
     * (e.g. the {@code [n]} in {@code a.b[n]}) where the subscript must be
     * applied per-element rather than to the whole collected sequence.
     */
    public static JsonNode mapStep(JsonNode node, JsonataLambda fn)
            throws RuntimeEvaluationException {
        if (node == null || node.isMissingNode()) return MISSING;
        if (node.isArray()) {
            ArrayNode result = NF.arrayNode();
            for (JsonNode elem : node) {
                JsonNode val = fn.apply(elem);
                if (!val.isMissingNode()) appendToSequence(result, val);
            }
            return unwrap(result);
        }
        return fn.apply(node);
    }

    /**
     * Maps {@code fn} over every element of a sequence and collects results
     * <em>without</em> flattening. Used for array and object constructor steps
     * inside path expressions (e.g. the {@code [addr]} or {@code {key:val}} step
     * in {@code Email.[address]} / {@code Phone.{type: number}}) where each
     * constructed value must be kept as a single element of the output sequence.
     * 
     * Unwraps {@link #preserveArray} wrappers before adding to the result.
     */
    public static JsonNode mapConstructorStep(JsonNode node, JsonataLambda fn)
            throws RuntimeEvaluationException {
        if (node == null || node.isMissingNode()) return MISSING;
        if (node.isArray()) {
            ArrayNode result = NF.arrayNode();
            for (JsonNode elem : node) {
                JsonNode val = fn.apply(elem);
                if (!val.isMissingNode()) {
                    result.add(unwrapPreserve(val));
                }
            }
            return result.isEmpty() ? MISSING : result;
        }
        JsonNode val = fn.apply(node);
        if (val.isMissingNode()) return MISSING;
        return unwrapPreserve(val);
    }

    /**
     * Variant of {@link #mapConstructorStep} for the non-preserve (flatten) case.
     * Inner arrays are flattened into the result sequence using {@link #appendToSequence},
     * matching JSONata's sequence-merge semantics for {@code $.[arr]} (without {@code []}).
     */
    public static JsonNode mapConstructorStepFlat(JsonNode node, JsonataLambda fn)
            throws RuntimeEvaluationException {
        if (node == null || node.isMissingNode()) return MISSING;
        if (node.isArray()) {
            ArrayNode result = NF.arrayNode();
            for (JsonNode elem : node) {
                JsonNode val = fn.apply(elem);
                if (!val.isMissingNode()) {
                    appendToSequence(result, val);
                }
            }
            return unwrap(result);
        }
        JsonNode val = fn.apply(node);
        if (val.isMissingNode()) return MISSING;
        return val;
    }

    /** Unwraps a preserveArray wrapper if present, otherwise returns the node as-is. */
    private static JsonNode unwrapPreserve(JsonNode node) {
        if (node.isObject() && node.has("__PRESERVE__")) {
            return node.get("__PRESERVE__");
        }
        return node;
    }

    // =========================================================================
    // Arithmetic
    // =========================================================================

    public static JsonNode add(JsonNode a, JsonNode b) throws RuntimeEvaluationException {
        if (!missing(a) && !a.isNumber()) throw new RuntimeEvaluationException(
                "T2001", "The left side of the + operator must evaluate to a number");
        if (!missing(b) && !b.isNumber()) throw new RuntimeEvaluationException(
                "T2002", "The right side of the + operator must evaluate to a number");
        if (missing(a) || missing(b)) return MISSING;
        return numNode(a.doubleValue() + b.doubleValue());
    }

    public static JsonNode subtract(JsonNode a, JsonNode b) throws RuntimeEvaluationException {
        if (!missing(a) && !a.isNumber()) throw new RuntimeEvaluationException(
                "T2001", "The left side of the - operator must evaluate to a number");
        if (!missing(b) && !b.isNumber()) throw new RuntimeEvaluationException(
                "T2002", "The right side of the - operator must evaluate to a number");
        if (missing(a) || missing(b)) return MISSING;
        return numNode(a.doubleValue() - b.doubleValue());
    }

    public static JsonNode multiply(JsonNode a, JsonNode b) throws RuntimeEvaluationException {
        if (!missing(a) && !a.isNumber()) throw new RuntimeEvaluationException(
                "T2001", "The left side of the * operator must evaluate to a number");
        if (!missing(b) && !b.isNumber()) throw new RuntimeEvaluationException(
                "T2002", "The right side of the * operator must evaluate to a number");
        if (missing(a) || missing(b)) return MISSING;
        double av = a.doubleValue();
        double bv = b.doubleValue();
        double result = av * bv;
        // Check for NaN (e.g., infinity * 0 = NaN)
        if (Double.isNaN(result))
            throw new RuntimeEvaluationException("D1001", "Numeric value out of range");
        // Check for infinity - throw D1001 (not D3001) because the result flows to division
        if (Double.isInfinite(result))
            throw new RuntimeEvaluationException("D1001", "Numeric value out of range");
        return numNode(result);
    }

    public static JsonNode divide(JsonNode a, JsonNode b) throws RuntimeEvaluationException {
        if (!missing(a) && !a.isNumber()) throw new RuntimeEvaluationException(
                "T2001", "The left side of the / operator must evaluate to a number");
        if (!missing(b) && !b.isNumber()) throw new RuntimeEvaluationException(
                "T2002", "The right side of the / operator must evaluate to a number");
        if (missing(a) || missing(b)) return MISSING;
        double numer = a.doubleValue();
        double denom = b.doubleValue();
        // Check for zero denominator
        if (denom == 0) {
            if (Double.isInfinite(numer))
                throw new RuntimeEvaluationException("D1001", "Numeric value out of range");
            // JSONata: 1/0 returns Infinity (no immediate error); downstream functions
            // like $string(Infinity) then throw D3001, and $string({key: 1/0}) throws D1001.
            return numNode(numer >= 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        }
        // Check for infinite denominator
        if (Double.isInfinite(denom)) {
            // e.g. 1/(10e300 * 10e100) — denominator overflowed to Infinity → D1001
            throw new RuntimeEvaluationException("D1001", "Numeric value out of range");
        }
        // Check for NaN denominator
        if (Double.isNaN(denom))
            throw new RuntimeEvaluationException("D1001", "Numeric value out of range");
        double result = numer / denom;
        if (Double.isNaN(result))
            throw new RuntimeEvaluationException("D1001", "Numeric value out of range");
        return numNode(result);
    }

    public static JsonNode modulo(JsonNode a, JsonNode b) throws RuntimeEvaluationException {
        if (!missing(a) && !a.isNumber()) throw new RuntimeEvaluationException(
                "T2001", "The left side of the % operator must evaluate to a number");
        if (!missing(b) && !b.isNumber()) throw new RuntimeEvaluationException(
                "T2002", "The right side of the % operator must evaluate to a number");
        if (missing(a) || missing(b)) return MISSING;
        double denom = b.doubleValue();
        if (denom == 0) throw new RuntimeEvaluationException("D1001", "Division by zero");
        return numNode(a.doubleValue() % denom);
    }

    public static JsonNode negate(JsonNode a) throws RuntimeEvaluationException {
        if (missing(a)) return MISSING;
        if (!a.isNumber()) throw new RuntimeEvaluationException(
                "D1002", "The operand of the - operator must evaluate to a number");
        return numNode(-a.doubleValue());
    }

    /** Throws the given error message as a RuntimeEvaluationException. Returns {@code JsonNode} so it can be used as an expression. */
    public static JsonNode fn_throw(String code, String message) throws RuntimeEvaluationException {
        throw new RuntimeEvaluationException(code, message);
    }

    /** Throws a T0410 arity error. Returns {@code JsonNode} so it can be used as an expression. */
    public static JsonNode fn_arity_error(String name, int expected, int actual)
            throws RuntimeEvaluationException {
        throw new RuntimeEvaluationException(
                "T0410", "Function $" + name + " requires " + expected
                        + " argument(s) but received " + actual);
    }

    /** Throws a T0411 "wrong number of arguments" error. Returns {@code JsonNode} so it can be used as an expression. */
    public static JsonNode fn_arg_count_error(String name, int min, int actual)
            throws RuntimeEvaluationException {
        throw new RuntimeEvaluationException(
                "T0411", "Function $" + name + " requires at least " + min
                        + " argument(s) but received " + actual);
    }

    /** Returns a LongNode when {@code v} is a whole number within long range, else DoubleNode. */
    static JsonNode numNode(double v) {
        if (!Double.isInfinite(v) && !Double.isNaN(v) && v == Math.floor(v)
                && v >= Long.MIN_VALUE && v <= Long.MAX_VALUE) {
            return NF.numberNode((long) v);
        }
        return NF.numberNode(v);
    }

    // =========================================================================
    // String concatenation
    // =========================================================================

    public static JsonNode concat(JsonNode a, JsonNode b) throws RuntimeEvaluationException {
        if (missing(a) && missing(b)) return MISSING;
        String sa = missing(a) ? "" : toText(a);
        String sb = missing(b) ? "" : toText(b);
        return NF.textNode(sa + sb);
    }

    // =========================================================================
    // Comparisons
    // =========================================================================

    public static JsonNode eq(JsonNode a, JsonNode b) {
        if (missing(a) || missing(b)) return bool(false);
        return bool(deepEquals(a, b));
    }

    public static JsonNode ne(JsonNode a, JsonNode b) {
        if (missing(a) || missing(b)) return bool(false);
        return bool(!deepEquals(a, b));
    }

    /**
     * Recursive structural equality that compares numbers by value (not by Jackson
     * node subclass), so that e.g. {@code IntNode(1)} and {@code LongNode(1)} are
     * considered equal — matching JSONata's deep-equals semantics for arrays and objects.
     */
    private static boolean deepEquals(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.doubleValue() == b.doubleValue();
        if (a.isTextual() && b.isTextual()) return a.textValue().equals(b.textValue());
        if (a.isBoolean() && b.isBoolean()) return a.booleanValue() == b.booleanValue();
        if (a.isNull() && b.isNull()) return true;
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                if (!deepEquals(a.get(i), b.get(i))) return false;
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = a.fields();
            while (it.hasNext()) {
                java.util.Map.Entry<String, JsonNode> entry = it.next();
                JsonNode bVal = b.get(entry.getKey());
                if (bVal == null || bVal.isMissingNode()) return false;
                if (!deepEquals(entry.getValue(), bVal)) return false;
            }
            return true;
        }
        return false;
    }

    public static JsonNode lt(JsonNode a, JsonNode b) throws RuntimeEvaluationException {
        if (!orderingOk(a) || !orderingOk(b)) throw orderingError(a, b);
        if (missing(a) || missing(b)) return MISSING;
        if (a.isNumber() && b.isNumber()) return bool(a.doubleValue() < b.doubleValue());
        if (a.isTextual() && b.isTextual()) return bool(a.textValue().compareTo(b.textValue()) < 0);
        throw orderingError(a, b);
    }

    public static JsonNode le(JsonNode a, JsonNode b) throws RuntimeEvaluationException {
        if (!orderingOk(a) || !orderingOk(b)) throw orderingError(a, b);
        if (missing(a) || missing(b)) return MISSING;
        if (a.isNumber() && b.isNumber()) return bool(a.doubleValue() <= b.doubleValue());
        if (a.isTextual() && b.isTextual()) return bool(a.textValue().compareTo(b.textValue()) <= 0);
        throw orderingError(a, b);
    }

    public static JsonNode gt(JsonNode a, JsonNode b) throws RuntimeEvaluationException {
        if (!orderingOk(a) || !orderingOk(b)) throw orderingError(a, b);
        if (missing(a) || missing(b)) return MISSING;
        if (a.isNumber() && b.isNumber()) return bool(a.doubleValue() > b.doubleValue());
        if (a.isTextual() && b.isTextual()) return bool(a.textValue().compareTo(b.textValue()) > 0);
        throw orderingError(a, b);
    }

    public static JsonNode ge(JsonNode a, JsonNode b) throws RuntimeEvaluationException {
        if (!orderingOk(a) || !orderingOk(b)) throw orderingError(a, b);
        if (missing(a) || missing(b)) return MISSING;
        if (a.isNumber() && b.isNumber()) return bool(a.doubleValue() >= b.doubleValue());
        if (a.isTextual() && b.isTextual()) return bool(a.textValue().compareTo(b.textValue()) >= 0);
        throw orderingError(a, b);
    }

    /** Returns true if the value is a valid ordering operand: number, string, or absent (MISSING). */
    private static boolean orderingOk(JsonNode n) {
        return missing(n) || n.isNumber() || n.isTextual();
    }

    private static RuntimeEvaluationException orderingError(JsonNode a, JsonNode b) {
        // T2009: both sides are comparable types (number/string/missing) but differ
        // T2010: at least one side is an incomparable type (null, boolean, object, array)
        if (orderingOk(a) && orderingOk(b)) {
            return new RuntimeEvaluationException(
                    "T2009", "operands of ordering operator must be of the same type");
        }
        return new RuntimeEvaluationException(
                "T2010", "operands of ordering operator must be numeric or string values");
    }

    // =========================================================================
    // Boolean logic
    // =========================================================================

    public static JsonNode and_(JsonNode a, Supplier<JsonNode> b) {
        boolean leftResult = isTruthy(a);
        return !leftResult ? BooleanNode.FALSE : bool(isTruthy(b.get()));
    }

    public static JsonNode or_(JsonNode a, Supplier<JsonNode> b) {
        boolean leftResult = isTruthy(a);
        return leftResult ? BooleanNode.TRUE : bool(isTruthy(b.get()));
    }

    /**
     * Elvis / default operator: returns {@code left} if truthy, otherwise {@code right}.
     * Evaluates {@code left} exactly once.
     */
    public static JsonNode elvis(JsonNode left, JsonNode right) {
        return isTruthy(left) ? left : right;
    }

    /**
     * Coalescing operator: returns {@code left} if defined (not MISSING), otherwise {@code right}.
     * Evaluates {@code left} exactly once.
     */
    public static JsonNode coalesce(JsonNode left, JsonNode right) {
        return missing(left) ? right : left;
    }

    /** Tests whether {@code item} is contained in {@code seq}. */
    public static JsonNode in_(JsonNode item, JsonNode seq) {
        if (missing(item) || missing(seq)) return bool(false);
        if (seq.isArray()) {
            for (JsonNode elem : seq) {
                if (eq(item, elem).booleanValue()) return bool(true);
            }
            return bool(false);
        }
        return eq(item, seq);
    }

    /**
     * JSONata boolean coercion rules:
     * <ul>
     *   <li>false, null, missing → false</li>
     *   <li>0, "" → false</li>
     *   <li>empty object → false</li>
     *   <li>array: empty or all members falsy → false; at least one truthy member → true</li>
     *   <li>function (lambda token) → false</li>
     *   <li>everything else → true</li>
     * </ul>
     */
    public static boolean isTruthy(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return false;
        if (n.isBoolean()) return n.booleanValue();
        if (n.isNumber()) return n.doubleValue() != 0;
        if (n.isTextual()) {
            // Lambda / function tokens are falsy per the JSONata spec ("functions → false")
            if (LambdaRegistry.isLambdaToken(n)) return false;
            return !n.textValue().isEmpty();
        }
        if (n.isObject()) return n.size() > 0;
        if (n.isArray()) {
            // An array is truthy iff at least one of its members is truthy (spec rule).
            // An empty array or an array whose every member is falsy is falsy.
            for (JsonNode elem : n) { if (isTruthy(elem)) return true; }
            return false;
        }
        return true;
    }

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Packs function arguments into an array WITHOUT flattening — unlike {@link #array}
     * which flattens array values. Used by generated code to pack multi-arg calls to
     * user-defined lambdas so that array arguments are preserved as single elements.
     */
    public static ArrayNode packArgs(JsonNode... elements) {
        ArrayNode result = NF.arrayNode();
        for (JsonNode e : elements) result.add(e != null ? e : MISSING);
        return result;
    }

    /** Creates a JSON array from the given elements, skipping missing values. */
    public static JsonNode array(JsonNode... elements) {
        ArrayNode result = NF.arrayNode();
        for (JsonNode e : elements) {
            if (missing(e)) continue;
            // A sequence/array value contributes its elements individually so that
            // [Phone.number] collects all numbers into a flat array rather than
            // wrapping the whole sequence in a nested array.
            if (e.isArray()) e.forEach(result::add);
            else result.add(e);
        }
        return result;
    }

    /** Creates a guaranteed 2-element {@code [a, b]} array without flattening inner arrays.
     *  Used to build {@code (element, position)} tuples for position-aware global sorts. */
    public static JsonNode tuple2(JsonNode a, JsonNode b) {
        return NF.arrayNode().add(a).add(b);
    }

    /**
     * Collects {@code [sortItem, posIndex]} tuples for a position-aware global sort.
     * For each element in {@code seq} at position {@code i}, applies {@code elemsFn}
     * to get the sort items, then stores {@code [sortItem, i]} in the result without
     * flattening.  Handles both scalar and array-valued {@code elemsFn} results.
     */
    public static JsonNode collectPosTuples(JsonNode seq, JsonataLambda elemsFn)
            throws RuntimeEvaluationException {
        if (seq == null || seq.isMissingNode()) return MISSING;
        int size = seq.isArray() ? seq.size() : 1;
        ArrayNode result = NF.arrayNode();
        for (int i = 0; i < size; i++) {
            JsonNode item = seq.isArray() ? seq.get(i) : seq;
            JsonNode elems = elemsFn.apply(NF.arrayNode().add(item).add(NF.numberNode(i)));
            if (elems == null || elems.isMissingNode()) continue;
            if (elems.isArray()) {
                for (JsonNode e : elems) {
                    if (!missing(e)) result.add(NF.arrayNode().add(e).add(NF.numberNode(i)));
                }
            } else {
                result.add(NF.arrayNode().add(elems).add(NF.numberNode(i)));
            }
        }
        return result.isEmpty() ? MISSING : result;
    }

    /** Marker to indicate an array element should be flattened (from range expression). */
    public record RangeHolder(int from, int to) {}

    /**
     * Wraps an ArrayNode to signal that it should NOT be flattened by {@link #arrayOf}.
     * Returns an ObjectNode wrapper (a valid JsonNode) so it can be used in lambdas.
     */
    public static JsonNode preserveArray(JsonNode arr) {
        ObjectNode wrapper = NF.objectNode();
        wrapper.set("__PRESERVE__", arr);
        return wrapper;
    }

    /**
     * Creates a JSON array from the given elements, handling RangeHolder markers
     * to flatten range expressions and preserveArray wrappers to preserve nested
     * array constructors.
     *
     * <p>ArrayNode values are flattened into the result (JSONata sequence rule),
     * unless wrapped with {@link #preserveArray}.
     */
    public static JsonNode arrayOf(Object... elements) {
        ArrayNode result = NF.arrayNode();
        for (Object e : elements) {
            if (e == null) continue;
            if (e instanceof RangeHolder rh) {
                for (int i = rh.from(); i <= rh.to(); i++) {
                    result.add(IntNode.valueOf(i));
                }
            } else if (e instanceof JsonNode jn) {
                if (!missing(jn)) {
                    // Check for preserveArray wrapper
                    if (jn.isObject() && jn.has("__PRESERVE__")) {
                        result.add(jn.get("__PRESERVE__"));
                    } else if (jn.isArray()) {
                        // Flatten ArrayNode from path navigation
                        for (JsonNode item : jn) {
                            result.add(item);
                        }
                    } else {
                        result.add(jn);
                    }
                }
            }
        }
        return result;
    }

    /** Creates a RangeHolder to signal that the range should be flattened. */
    public static RangeHolder rangeFlatten(int from, int to) {
        return new RangeHolder(from, to);
    }

    /** Legacy method for backward compatibility. */
    public static JsonNode arrayOf(JsonNode... elements) {
        return arrayOf((Object[]) elements);
    }

    /**
     * Creates a JSON object from alternating key-value pairs.
     * Keys are coerced to strings; missing values are skipped.
     *
     * @param keyValuePairs alternating {@code JsonNode} key, {@code JsonNode} value
     */
    public static JsonNode object(Object... keyValuePairs) throws RuntimeEvaluationException {
        if (keyValuePairs.length % 2 != 0) {
            throw new RuntimeEvaluationException("T1001", "object() requires an even number of arguments");
        }
        ObjectNode result = NF.objectNode();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            JsonNode key = (JsonNode) keyValuePairs[i];
            JsonNode val = (JsonNode) keyValuePairs[i + 1];
            if (!missing(key) && !missing(val)) {
                if (!key.isTextual())
                    throw new RuntimeEvaluationException(
                            "T1003", "The key expression of an object component must evaluate to a string");
                if (result.has(key.textValue()))
                    throw new RuntimeEvaluationException(
                            "D1009", "Multiple key definitions evaluate to the same key: \"" + key.textValue() + "\"");
                result.set(key.textValue(), val);
            }
        }
        return result;
    }

    /** Creates an integer range array {@code [from, from+1, ..., to]}. */
    public static JsonNode range(JsonNode from, JsonNode to) throws RuntimeEvaluationException {
        // Undefined endpoint: if the other is a non-numeric type, throw; otherwise empty array
        if (missing(from)) {
            if (!missing(to) && !to.isNumber())
                throw new RuntimeEvaluationException(
                        "T2004", "The right side of the range operator (..) must evaluate to an integer");
            return NF.arrayNode();
        }
        if (missing(to)) {
            if (!from.isNumber())
                throw new RuntimeEvaluationException(
                        "T2003", "The left side of the range operator (..) must evaluate to an integer");
            return NF.arrayNode();
        }
        if (!from.isNumber()) throw new RuntimeEvaluationException(
                "T2003", "The left side of the range operator (..) must evaluate to an integer");
        if (!to.isNumber()) throw new RuntimeEvaluationException(
                "T2004", "The right side of the range operator (..) must evaluate to an integer");
        double fd = from.doubleValue();
        double td = to.doubleValue();
        if (fd != Math.floor(fd)) throw new RuntimeEvaluationException(
                "T2003", "The left side of the range operator (..) must be an integer");
        if (td != Math.floor(td)) throw new RuntimeEvaluationException(
                "T2004", "The right side of the range operator (..) must be an integer");
        long f = (long) fd;
        long t = (long) td;
        if (t - f >= 10_000_000L)
            throw new RuntimeEvaluationException("D2014", "The range expression generates too many values");
        ArrayNode result = NF.arrayNode(t >= f ? (int)(t - f + 1) : 0);
        for (long i = f; i <= t; i++) result.add(i);
        return result;
    }

    // =========================================================================
    // Built-in functions — type coercion
    // =========================================================================

    public static JsonNode fn_string(JsonNode arg) throws RuntimeEvaluationException {
        if (missing(arg)) return MISSING;
        if (arg.isNumber() && (Double.isInfinite(arg.doubleValue()) || Double.isNaN(arg.doubleValue())))
            throw new RuntimeEvaluationException("D3001", "Attempting to invoke a non-numeric value as a numeric function");
        // Check if containers contain Infinity values (throws D1001)
        if (arg.isObject() || arg.isArray()) checkNoInfinity(arg);
        return NF.textNode(toText(arg));
    }

    private static void checkNoInfinity(JsonNode node) throws RuntimeEvaluationException {
        if (node.isNumber() && (Double.isInfinite(node.doubleValue()) || Double.isNaN(node.doubleValue())))
            throw new RuntimeEvaluationException("D1001", "Numeric value out of range");
        if (node.isArray()) { for (JsonNode e : node) checkNoInfinity(e); }
        if (node.isObject()) { for (JsonNode v : node) checkNoInfinity(v); }
    }

    public static JsonNode fn_string(JsonNode arg, JsonNode prettify) throws RuntimeEvaluationException {
        if (missing(arg)) return MISSING;
        if (!missing(prettify) && !prettify.isBoolean())
            throw new RuntimeEvaluationException("T0410", "Argument 2 of function $string must be a boolean");
        if (missing(prettify) || !isTruthy(prettify)) return fn_string(arg);
        return StringBuiltins.fn_string_prettify(arg);
    }

    public static JsonNode fn_number(JsonNode arg) throws RuntimeEvaluationException {
        return NumericBuiltins.fn_number(arg);
    }

    public static JsonNode fn_boolean(JsonNode arg) {
        if (missing(arg)) return MISSING;
        return bool(isTruthy(arg));
    }

    public static JsonNode fn_not(JsonNode arg) {
        if (missing(arg)) return MISSING;
        return bool(!isTruthy(arg));
    }

    public static JsonNode fn_type(JsonNode arg) {
        if (missing(arg)) return MISSING;
        if (LambdaRegistry.isLambdaToken(arg)) return NF.textNode("function");
        if (arg.isNull())    return NF.textNode("null");
        if (arg.isNumber())  return NF.textNode("number");
        if (arg.isTextual()) return NF.textNode("string");
        if (arg.isBoolean()) return NF.textNode("boolean");
        if (arg.isArray())   return NF.textNode("array");
        if (arg.isObject())  return NF.textNode("object");
        return MISSING;
    }

    public static JsonNode fn_exists(JsonNode arg) {
        return bool(arg != null && !arg.isMissingNode());
    }

    // =========================================================================
    // Built-in functions — numeric
    // =========================================================================

    public static JsonNode fn_floor(JsonNode arg) throws RuntimeEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.numberNode((long) Math.floor(toNumber(arg)));
    }

    public static JsonNode fn_ceil(JsonNode arg) throws RuntimeEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.numberNode((long) Math.ceil(toNumber(arg)));
    }

    public static JsonNode fn_round(JsonNode arg) throws RuntimeEvaluationException {
        return NumericBuiltins.fn_round(arg, MISSING);
    }

    public static JsonNode fn_round(JsonNode arg, JsonNode precision) throws RuntimeEvaluationException {
        return NumericBuiltins.fn_round(arg, precision);
    }

    public static JsonNode fn_abs(JsonNode arg) throws RuntimeEvaluationException {
        if (missing(arg)) return MISSING;
        return numNode(Math.abs(toNumber(arg)));
    }

    public static JsonNode fn_sqrt(JsonNode arg) throws RuntimeEvaluationException {
        if (missing(arg)) return MISSING;
        double v = toNumber(arg);
        if (v < 0) throw new RuntimeEvaluationException("D3060", "$sqrt: the sqrt function cannot be applied to a negative number");
        return numNode(Math.sqrt(v));
    }

    public static JsonNode fn_power(JsonNode base, JsonNode exp) throws RuntimeEvaluationException {
        if (missing(base) || missing(exp)) return MISSING;
        double result = Math.pow(toNumber(base), toNumber(exp));
        if (Double.isInfinite(result) || Double.isNaN(result))
            throw new RuntimeEvaluationException(
                    "D3061", "$power() function: the result of the power function is out of range");
        return numNode(result);
    }

    public static JsonNode fn_random() {
        return NumericBuiltins.fn_random();
    }

    public static JsonNode fn_formatBase(JsonNode number, JsonNode radix)
            throws RuntimeEvaluationException {
        return NumericBuiltins.fn_formatBase(number, radix);
    }

    public static JsonNode fn_formatNumber(JsonNode number, JsonNode picture)
            throws RuntimeEvaluationException {
        return NumericBuiltins.fn_formatNumber(number, picture, MISSING);
    }

    public static JsonNode fn_formatNumber(JsonNode number, JsonNode picture, JsonNode options)
            throws RuntimeEvaluationException {
        return NumericBuiltins.fn_formatNumber(number, picture, options);
    }

    public static JsonNode fn_formatInteger(JsonNode number, JsonNode picture)
            throws RuntimeEvaluationException {
        return NumericBuiltins.fn_formatInteger(number, picture);
    }

    public static JsonNode fn_parseInteger(JsonNode string, JsonNode picture)
            throws RuntimeEvaluationException {
        return NumericBuiltins.fn_parseInteger(string, picture);
    }

    // =========================================================================
    // Built-in functions — string (thin delegations to StringBuiltins)
    // =========================================================================

    public static JsonNode fn_uppercase(JsonNode arg) throws RuntimeEvaluationException {
        return StringBuiltins.fn_uppercase(arg);
    }

    public static JsonNode fn_lowercase(JsonNode arg) throws RuntimeEvaluationException {
        return StringBuiltins.fn_lowercase(arg);
    }

    public static JsonNode fn_trim(JsonNode arg) throws RuntimeEvaluationException {
        return StringBuiltins.fn_trim(arg);
    }

    public static JsonNode fn_length(JsonNode arg) throws RuntimeEvaluationException {
        return StringBuiltins.fn_length(arg);
    }

    public static JsonNode fn_length_ctx(JsonNode arg) throws RuntimeEvaluationException {
        return StringBuiltins.fn_length_ctx(arg);
    }

    public static JsonNode fn_substring(JsonNode str, JsonNode start)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_substring(str, start);
    }

    public static JsonNode fn_substring(JsonNode str, JsonNode start, JsonNode length)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_substring(str, start, length);
    }

    public static JsonNode fn_substringBefore(JsonNode str, JsonNode chars)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_substringBefore(str, chars);
    }

    public static JsonNode fn_substringBefore_ctx(JsonNode str, JsonNode chars)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_substringBefore_ctx(str, chars);
    }

    public static JsonNode fn_substringAfter(JsonNode str, JsonNode chars)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_substringAfter(str, chars);
    }

    public static JsonNode fn_substringAfter_ctx(JsonNode str, JsonNode chars)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_substringAfter_ctx(str, chars);
    }

    public static JsonNode fn_contains(JsonNode str, JsonNode search)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_contains(str, search);
    }

    public static JsonNode fn_split(JsonNode str, JsonNode separator)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_split(str, separator);
    }

    public static JsonNode fn_split(JsonNode str, JsonNode separator, JsonNode limit)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_split(str, separator, limit);
    }

    public static JsonNode fn_match(JsonNode str, JsonNode pattern)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_match(str, pattern);
    }

    public static JsonNode fn_match(JsonNode str, JsonNode pattern, JsonNode limit)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_match(str, pattern, limit);
    }

    public static JsonNode fn_replace(JsonNode str, JsonNode pattern, JsonNode replacement)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_replace(str, pattern, replacement);
    }

    public static JsonNode fn_replace(JsonNode str, JsonNode pattern,
                                       JsonNode replacement, JsonNode limit)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_replace(str, pattern, replacement, limit);
    }

    public static JsonNode fn_join(JsonNode arr, JsonNode separator)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_join(arr, separator);
    }

    public static JsonNode fn_pad(JsonNode str, JsonNode width) throws RuntimeEvaluationException {
        return StringBuiltins.fn_pad(str, width, MISSING);
    }

    public static JsonNode fn_pad(JsonNode str, JsonNode width, JsonNode padChar)
            throws RuntimeEvaluationException {
        return StringBuiltins.fn_pad(str, width, padChar);
    }

    public static JsonNode fn_eval(JsonNode expr) throws RuntimeEvaluationException {
        return StringBuiltins.fn_eval(expr, MISSING);
    }

    public static JsonNode fn_eval(JsonNode expr, JsonNode context) throws RuntimeEvaluationException {
        return StringBuiltins.fn_eval(expr, context);
    }

    public static JsonNode fn_base64encode(JsonNode str) throws RuntimeEvaluationException {
        return StringBuiltins.fn_base64encode(str);
    }

    public static JsonNode fn_base64decode(JsonNode str) throws RuntimeEvaluationException {
        return StringBuiltins.fn_base64decode(str);
    }

    public static JsonNode fn_encodeUrlComponent(JsonNode str) throws RuntimeEvaluationException {
        return StringBuiltins.fn_encodeUrlComponent(str);
    }

    public static JsonNode fn_decodeUrlComponent(JsonNode str) throws RuntimeEvaluationException {
        return StringBuiltins.fn_decodeUrlComponent(str);
    }

    public static JsonNode fn_encodeUrl(JsonNode str) throws RuntimeEvaluationException {
        return StringBuiltins.fn_encodeUrl(str);
    }

    public static JsonNode fn_decodeUrl(JsonNode str) throws RuntimeEvaluationException {
        return StringBuiltins.fn_decodeUrl(str);
    }

    // =========================================================================
    // Built-in functions — array / sequence
    // =========================================================================

    public static JsonNode fn_count(JsonNode arg) {
        if (missing(arg)) return NF.numberNode(0);
        return NF.numberNode(arg.isArray() ? arg.size() : 1);
    }

    /** Fused $count(arr.field): counts elements in all field values without materializing the field array. */
    public static JsonNode fn_count_field(JsonNode seq, String fieldName) {
        if (missing(seq)) return NF.numberNode(0);
        if (!seq.isArray()) {
            if (!seq.isObject()) return NF.numberNode(0);
            JsonNode v = seq.get(fieldName);
            if (v == null || v.isMissingNode()) return NF.numberNode(0);
            return v.isArray() ? NF.numberNode(v.size()) : NF.numberNode(1);
        }
        int count = 0;
        for (JsonNode elem : seq) {
            if (!elem.isObject()) continue;
            JsonNode v = elem.get(fieldName);
            if (v == null || v.isMissingNode()) continue;
            count += v.isArray() ? v.size() : 1;
        }
        return NF.numberNode(count);
    }

    /** Fused $count(arr[pred]): counts matching elements without materializing a filtered array. */
    public static JsonNode fn_count_filter(JsonNode seq, JsonataLambda predicate) throws RuntimeEvaluationException {
        if (missing(seq)) return NF.numberNode(0);
        if (!seq.isArray()) return isTruthy(predicate.apply(seq)) ? NF.numberNode(1) : NF.numberNode(0);
        int count = 0;
        for (JsonNode elem : seq) { if (isTruthy(predicate.apply(elem))) count++; }
        return NF.numberNode(count);
    }

    /** Fused $sum(arr.field): navigates field and sums without an intermediate array. */
    public static JsonNode fn_sum_field(JsonNode seq, String fieldName) throws RuntimeEvaluationException {
        if (missing(seq)) return MISSING;
        double sum = 0; boolean any = false;
        Iterable<JsonNode> items = seq.isArray() ? seq : List.of(seq);
        for (JsonNode elem : items) {
            if (!elem.isObject()) continue;
            JsonNode v = elem.get(fieldName);
            if (v == null || v.isMissingNode()) continue;
            if (v.isArray()) {
                for (JsonNode sub : v) { requireT0412(sub, "$sum"); sum += sub.doubleValue(); any = true; }
            } else { requireT0412(v, "$sum"); sum += v.doubleValue(); any = true; }
        }
        return any ? numNode(sum) : MISSING;
    }

    /** Fused $average(arr.field): navigates field and averages without an intermediate array. */
    public static JsonNode fn_average_field(JsonNode seq, String fieldName) throws RuntimeEvaluationException {
        if (missing(seq)) return MISSING;
        double sum = 0; int count = 0;
        Iterable<JsonNode> items = seq.isArray() ? seq : List.of(seq);
        for (JsonNode elem : items) {
            if (!elem.isObject()) continue;
            JsonNode v = elem.get(fieldName);
            if (v == null || v.isMissingNode()) continue;
            if (v.isArray()) {
                for (JsonNode sub : v) { requireAverageArg(sub); sum += sub.doubleValue(); count++; }
            } else { requireAverageArg(v); sum += v.doubleValue(); count++; }
        }
        return count == 0 ? MISSING : numNode(sum / count);
    }

    /** Fused $max(arr.field): navigates field and finds max without an intermediate array. */
    public static JsonNode fn_max_field(JsonNode seq, String fieldName) throws RuntimeEvaluationException {
        if (missing(seq)) return MISSING;
        double max = Double.NEGATIVE_INFINITY; boolean any = false;
        Iterable<JsonNode> items = seq.isArray() ? seq : List.of(seq);
        for (JsonNode elem : items) {
            if (!elem.isObject()) continue;
            JsonNode v = elem.get(fieldName);
            if (v == null || v.isMissingNode()) continue;
            if (v.isArray()) {
                for (JsonNode sub : v) { requireT0412(sub, "$max"); double d = sub.doubleValue(); if (d > max) max = d; any = true; }
            } else { requireT0412(v, "$max"); double d = v.doubleValue(); if (d > max) max = d; any = true; }
        }
        return any ? numNode(max) : MISSING;
    }

    /** Fused $sum(arr.f1.f2): two-level field navigation and sum without intermediate arrays. */
    public static JsonNode fn_sum_field(JsonNode seq, String f1, String f2) throws RuntimeEvaluationException {
        if (missing(seq)) return MISSING;
        double sum = 0; boolean any = false;
        Iterable<JsonNode> items = seq.isArray() ? seq : List.of(seq);
        for (JsonNode elem : items) {
            if (!elem.isObject()) continue;
            JsonNode v1 = elem.get(f1);
            if (v1 == null || v1.isMissingNode()) continue;
            Iterable<JsonNode> sub = v1.isArray() ? v1 : List.of(v1);
            for (JsonNode s : sub) {
                if (!s.isObject()) continue;
                JsonNode v2 = s.get(f2);
                if (v2 == null || v2.isMissingNode()) continue;
                if (v2.isArray()) { for (JsonNode n : v2) { requireT0412(n, "$sum"); sum += n.doubleValue(); any = true; } }
                else { requireT0412(v2, "$sum"); sum += v2.doubleValue(); any = true; }
            }
        }
        return any ? numNode(sum) : MISSING;
    }

    /** Fused $average(arr.f1.f2): two-level field navigation and average without intermediate arrays. */
    public static JsonNode fn_average_field(JsonNode seq, String f1, String f2) throws RuntimeEvaluationException {
        if (missing(seq)) return MISSING;
        double sum = 0; int count = 0;
        Iterable<JsonNode> items = seq.isArray() ? seq : List.of(seq);
        for (JsonNode elem : items) {
            if (!elem.isObject()) continue;
            JsonNode v1 = elem.get(f1);
            if (v1 == null || v1.isMissingNode()) continue;
            Iterable<JsonNode> sub = v1.isArray() ? v1 : List.of(v1);
            for (JsonNode s : sub) {
                if (!s.isObject()) continue;
                JsonNode v2 = s.get(f2);
                if (v2 == null || v2.isMissingNode()) continue;
                if (v2.isArray()) { for (JsonNode n : v2) { requireAverageArg(n); sum += n.doubleValue(); count++; } }
                else { requireAverageArg(v2); sum += v2.doubleValue(); count++; }
            }
        }
        return count == 0 ? MISSING : numNode(sum / count);
    }

    /** Fused $max(arr.f1.f2): two-level field navigation and max without intermediate arrays. */
    public static JsonNode fn_max_field(JsonNode seq, String f1, String f2) throws RuntimeEvaluationException {
        if (missing(seq)) return MISSING;
        double max = Double.NEGATIVE_INFINITY; boolean any = false;
        Iterable<JsonNode> items = seq.isArray() ? seq : List.of(seq);
        for (JsonNode elem : items) {
            if (!elem.isObject()) continue;
            JsonNode v1 = elem.get(f1);
            if (v1 == null || v1.isMissingNode()) continue;
            Iterable<JsonNode> sub = v1.isArray() ? v1 : List.of(v1);
            for (JsonNode s : sub) {
                if (!s.isObject()) continue;
                JsonNode v2 = s.get(f2);
                if (v2 == null || v2.isMissingNode()) continue;
                if (v2.isArray()) { for (JsonNode n : v2) { requireT0412(n, "$max"); double d = n.doubleValue(); if (d > max) max = d; any = true; } }
                else { requireT0412(v2, "$max"); double d = v2.doubleValue(); if (d > max) max = d; any = true; }
            }
        }
        return any ? numNode(max) : MISSING;
    }

    /** Fused $min(arr.f1.f2): two-level field navigation and min without intermediate arrays. */
    public static JsonNode fn_min_field(JsonNode seq, String f1, String f2) throws RuntimeEvaluationException {
        if (missing(seq)) return MISSING;
        double min = Double.POSITIVE_INFINITY; boolean any = false;
        Iterable<JsonNode> items = seq.isArray() ? seq : List.of(seq);
        for (JsonNode elem : items) {
            if (!elem.isObject()) continue;
            JsonNode v1 = elem.get(f1);
            if (v1 == null || v1.isMissingNode()) continue;
            Iterable<JsonNode> sub = v1.isArray() ? v1 : List.of(v1);
            for (JsonNode s : sub) {
                if (!s.isObject()) continue;
                JsonNode v2 = s.get(f2);
                if (v2 == null || v2.isMissingNode()) continue;
                if (v2.isArray()) { for (JsonNode n : v2) { requireT0412(n, "$min"); double d = n.doubleValue(); if (d < min) min = d; any = true; } }
                else { requireT0412(v2, "$min"); double d = v2.doubleValue(); if (d < min) min = d; any = true; }
            }
        }
        return any ? numNode(min) : MISSING;
    }

    /** Fused $min(arr.field): navigates field and finds min without an intermediate array. */
    public static JsonNode fn_min_field(JsonNode seq, String fieldName) throws RuntimeEvaluationException {
        if (missing(seq)) return MISSING;
        double min = Double.POSITIVE_INFINITY; boolean any = false;
        Iterable<JsonNode> items = seq.isArray() ? seq : List.of(seq);
        for (JsonNode elem : items) {
            if (!elem.isObject()) continue;
            JsonNode v = elem.get(fieldName);
            if (v == null || v.isMissingNode()) continue;
            if (v.isArray()) {
                for (JsonNode sub : v) { requireT0412(sub, "$min"); double d = sub.doubleValue(); if (d < min) min = d; any = true; }
            } else { requireT0412(v, "$min"); double d = v.doubleValue(); if (d < min) min = d; any = true; }
        }
        return any ? numNode(min) : MISSING;
    }

    public static JsonNode fn_sum(JsonNode arg) throws RuntimeEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) {
            requireT0412(arg, "$sum");
            return numNode(arg.doubleValue());
        }
        if (arg.size() == 0) return NF.numberNode(0);
        double sum = 0;
        for (JsonNode elem : arg) { requireT0412(elem, "$sum"); sum += elem.doubleValue(); }
        return numNode(sum);
    }

    public static JsonNode fn_max(JsonNode arg) throws RuntimeEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) { requireT0412(arg, "$max"); return numNode(arg.doubleValue()); }
        if (arg.size() == 0) return MISSING;
        double max = Double.NEGATIVE_INFINITY;
        for (JsonNode elem : arg) { requireT0412(elem, "$max"); double v = elem.doubleValue(); if (v > max) max = v; }
        return numNode(max);
    }

    public static JsonNode fn_min(JsonNode arg) throws RuntimeEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) { requireT0412(arg, "$min"); return numNode(arg.doubleValue()); }
        if (arg.size() == 0) return MISSING;
        double min = Double.POSITIVE_INFINITY;
        for (JsonNode elem : arg) { requireT0412(elem, "$min"); double v = elem.doubleValue(); if (v < min) min = v; }
        return numNode(min);
    }

    public static JsonNode fn_average(JsonNode arg) throws RuntimeEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) { requireT0412(arg, "$average"); return numNode(arg.doubleValue()); }
        if (arg.size() == 0) return MISSING;
        double sum = 0;
        for (JsonNode elem : arg) { requireAverageArg(elem); sum += elem.doubleValue(); }
        return numNode(sum / arg.size());
    }

    private static void requireT0412(JsonNode n, String fnName) throws RuntimeEvaluationException {
        if (!n.isNumber())
            throw new RuntimeEvaluationException(
                    "T0412", fnName + " requires an array of numbers, but found " + n.getNodeType());
    }

    private static void requireAverageArg(JsonNode n) throws RuntimeEvaluationException {
        if (!n.isNumber())
            throw new RuntimeEvaluationException(
                    "T0412", "$average requires an array of numbers, but found " + n.getNodeType());
    }

    public static JsonNode fn_append(JsonNode a, JsonNode b) {
        if (missing(a)) return b;
        if (missing(b)) return a;
        ArrayNode result = NF.arrayNode();
        appendToSequence(result, a);
        appendToSequence(result, b);
        return result;
    }

    public static JsonNode fn_reverse(JsonNode arg) {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) return arg;
        ArrayNode result = NF.arrayNode();
        for (int i = arg.size() - 1; i >= 0; i--) result.add(arg.get(i));
        return result;
    }

    public static JsonNode fn_distinct(JsonNode arg) {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) return arg;
        java.util.LinkedHashSet<DistinctKey> seen = new java.util.LinkedHashSet<>();
        ArrayNode result = NF.arrayNode();
        for (JsonNode elem : arg) {
            if (seen.add(new DistinctKey(elem))) result.add(elem);
        }
        return result;
    }

    private record DistinctKey(JsonNode node) {
        @Override public boolean equals(Object o) {
            return o instanceof DistinctKey dk && deepEquals(node, dk.node);
        }
        @Override public int hashCode() { return deepHashCode(node); }
    }

    private static int deepHashCode(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return 0;
        if (n.isNumber())  return Double.hashCode(n.doubleValue());
        if (n.isTextual()) return n.textValue().hashCode();
        if (n.isBoolean()) return Boolean.hashCode(n.booleanValue());
        if (n.isArray()) {
            int h = 1;
            for (JsonNode e : n) h = 31 * h + deepHashCode(e);
            return h;
        }
        if (n.isObject()) {
            int h = 0;
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                java.util.Map.Entry<String, JsonNode> e = it.next();
                h += e.getKey().hashCode() ^ deepHashCode(e.getValue());
            }
            return h;
        }
        return n.hashCode();
    }

    public static JsonNode fn_flatten(JsonNode arg) {
        if (missing(arg)) return MISSING;
        ArrayNode result = NF.arrayNode();
        flattenInto(arg, result);
        return result;
    }

    private static void flattenInto(JsonNode node, ArrayNode acc) {
        if (node.isArray()) { for (JsonNode e : node) flattenInto(e, acc); }
        else if (!node.isMissingNode()) acc.add(node);
    }

    public static JsonNode fn_shuffle(JsonNode arg) {
        return SequenceBuiltins.fn_shuffle(arg);
    }

    public static JsonNode fn_zip(JsonNode... arrays) {
        if (arrays.length == 0) return MISSING;
        // Normalise: wrap scalars as single-element arrays; MISSING → empty array → result is []
        JsonNode[] normalised = new JsonNode[arrays.length];
        int minLen = Integer.MAX_VALUE;
        for (int i = 0; i < arrays.length; i++) {
            JsonNode arr = arrays[i];
            if (missing(arr)) {
                normalised[i] = NF.arrayNode(); // MISSING treated as empty → minLen becomes 0
            } else if (!arr.isArray()) {
                normalised[i] = NF.arrayNode().add(arr); // wrap scalar
            } else {
                normalised[i] = arr;
            }
            minLen = Math.min(minLen, normalised[i].size());
        }
        if (minLen == Integer.MAX_VALUE) minLen = 0;
        ArrayNode result = NF.arrayNode();
        for (int i = 0; i < minLen; i++) {
            ArrayNode tuple = NF.arrayNode();
            for (JsonNode arr : normalised) tuple.add(arr.get(i));
            result.add(tuple);
        }
        return result;
    }

    // =========================================================================
    // Built-in functions — higher-order sequence (thin delegations to SequenceBuiltins)
    // =========================================================================

    public static JsonNode fn_sort(JsonNode arg) throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_sort(arg);
    }

    public static JsonNode fn_sort(JsonNode arg, JsonataLambda keyFn)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_sort(arg, keyFn);
    }

    public static JsonNode fn_sort_comparator(JsonNode arg, JsonataLambda comparatorFn)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_sort_comparator(arg, comparatorFn);
    }

    public static JsonNode fn_collect_pairs(JsonNode source, JsonataLambda elemFn)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_collect_pairs(source, elemFn);
    }

    public static JsonNode fn_collect_triples(JsonNode grandparents, JsonataLambda parentFn,
                                               JsonataLambda elemFn)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_collect_triples(grandparents, parentFn, elemFn);
    }

    public static JsonNode fn_map(JsonNode arr, JsonataLambda fn)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_map(arr, fn);
    }

    public static JsonNode fn_filter(JsonNode arr, JsonataLambda predicate)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_filter(arr, predicate);
    }

    public static JsonNode fn_reduce(JsonNode arr, JsonataLambda fn, JsonNode init)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_reduce(arr, fn, init);
    }

    /**
     * Variant of {@link #fn_map} for multi-param lambdas.
     * Passes {@code [value, index, array]} to the lambda so that the
     * {@code $i} and {@code $a} parameters are available.
     */
    public static JsonNode fn_map_indexed(JsonNode arr, JsonataLambda fn)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_map_indexed(arr, fn);
    }

    /**
     * Variant of {@link #fn_filter} for multi-param lambdas.
     * Passes {@code [value, index, array]} to the predicate.
     */
    public static JsonNode fn_filter_indexed(JsonNode arr, JsonataLambda predicate)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_filter_indexed(arr, predicate);
    }

    /**
     * Maps {@code fn} over each element of {@code seq}, passing {@code [element, index]}
     * to the lambda. Results are collected (non-missing) and flattened one level.
     * Used by the positional-binding operator ({@code #$i}).
     */
    public static JsonNode eachIndexed(JsonNode seq, JsonataLambda fn)
            throws RuntimeEvaluationException {
        if (seq == null || seq.isMissingNode()) return MISSING;
        ArrayNode result = NF.arrayNode();
        if (seq.isArray()) {
            for (int i = 0; i < seq.size(); i++) {
                JsonNode val = fn.apply(NF.arrayNode().add(seq.get(i)).add(NF.numberNode(i)));
                if (!val.isMissingNode()) appendToSequence(result, val);
            }
        } else {
            JsonNode val = fn.apply(NF.arrayNode().add(seq).add(NF.numberNode(0)));
            if (!val.isMissingNode()) appendToSequence(result, val);
        }
        return unwrap(result);
    }

    /**
     * Merges an array of ObjectNode results (one per binding-loop iteration) into a
     * single ObjectNode.  Used when a GroupBy expression follows a binding operator
     * ({@code @$var} / {@code #$var}) — each iteration produces its own grouped object
     * and they must all be combined.  Duplicate keys accumulate into arrays (JSONata
     * group-by semantics for the same key appearing across iterations).
     */
    public static JsonNode mergeGroupByObjects(JsonNode seq) {
        if (seq == null || seq.isMissingNode()) return MISSING;
        if (!seq.isArray()) return seq.isObject() ? seq : MISSING;
        ObjectNode result = NF.objectNode();
        for (JsonNode item : seq) {
            if (!item.isObject()) continue;
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = item.fields();
            while (it.hasNext()) {
                java.util.Map.Entry<String, JsonNode> entry = it.next();
                String k = entry.getKey();
                JsonNode v = entry.getValue();
                if (!result.has(k)) {
                    result.set(k, v);
                } else {
                    JsonNode existing = result.get(k);
                    if (existing.isArray()) {
                        ArrayNode arr = (ArrayNode) existing;
                        if (v.isArray()) arr.addAll((ArrayNode) v); else arr.add(v);
                    } else {
                        ArrayNode arr = NF.arrayNode();
                        arr.add(existing);
                        if (v.isArray()) arr.addAll((ArrayNode) v); else arr.add(v);
                        result.set(k, arr);
                    }
                }
            }
        }
        return result.isEmpty() ? MISSING : result;
    }

    /**
     * Returns the single element of {@code arr} for which {@code predicate}
     * returns truthy. Throws if zero or more than one element matches.
     */
    public static JsonNode fn_single(JsonNode arr, JsonataLambda predicate)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_single(arr, predicate);
    }

    /** 1-arg $single: returns the single element, or throws D3138/D3139. */
    public static JsonNode fn_single(JsonNode arr) throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_single(arr);
    }

    /** Multi-param $single: passes [value, index, array] to the predicate. */
    public static JsonNode fn_single_indexed(JsonNode arr, JsonataLambda predicate)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_single_indexed(arr, predicate);
    }

    /**
     * Returns an object containing only the key/value pairs of {@code obj}
     * for which {@code fn} returns truthy.
     * Passes {@code [value, key, object]} to the lambda so both
     * {@code $v} and {@code $k} parameters are available.
     */
    public static JsonNode fn_sift(JsonNode obj, JsonataLambda fn)
            throws RuntimeEvaluationException {
        return SequenceBuiltins.fn_sift(obj, fn);
    }

    // =========================================================================
    // Built-in functions — object
    // =========================================================================

    public static JsonNode fn_keys(JsonNode obj) {
        if (missing(obj)) return MISSING;
        // When applied to an array of objects, collect all unique keys (union)
        if (obj.isArray()) {
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (JsonNode elem : obj) {
                if (elem.isObject()) elem.fieldNames().forEachRemaining(seen::add);
            }
            if (seen.isEmpty()) return MISSING;
            ArrayNode result = NF.arrayNode();
            seen.forEach(result::add);
            return unwrap(result);
        }
        if (!obj.isObject()) return MISSING;
        ArrayNode result = NF.arrayNode();
        obj.fieldNames().forEachRemaining(result::add);
        return result.isEmpty() ? MISSING : unwrap(result);
    }

    public static JsonNode fn_values(JsonNode obj) {
        if (missing(obj) || !obj.isObject()) return MISSING;
        ArrayNode result = NF.arrayNode();
        obj.fields().forEachRemaining(e -> result.add(e.getValue()));
        return result.isEmpty() ? MISSING : result;
    }

    /**
     * Implements the JSONata transform operator {@code src ~> |location|update[,delete]|}.
     *
     * <ol>
     *   <li>Deep-copies {@code source} so the original is never mutated.</li>
     *   <li>Evaluates {@code locationFn} against the copy to obtain the target nodes.</li>
     *   <li>For each target (ObjectNode), merges the result of {@code updateFn} into it.</li>
     *   <li>Optionally removes fields named by {@code deleteFields} from each target.</li>
     * </ol>
     *
     * @param source      the document to transform
     * @param locationFn  lambda that navigates to the nodes to update (receives the copy)
     * @param updateFn    lambda that produces the update object (receives each matched node)
     * @param deleteFields a string or array-of-strings naming fields to delete, or MISSING
     */
    public static JsonNode fn_transform(JsonNode source,
                                        JsonataLambda locationFn,
                                        JsonataLambda updateFn,
                                        JsonNode deleteFields)
            throws RuntimeEvaluationException {
        if (missing(source)) return MISSING;
        // Deep-copy so mutations don't affect the caller's document.
        JsonNode copy = source.deepCopy();
        // Navigate to the target nodes (they are in-copy references, so mutations stick).
        JsonNode targets = locationFn.apply(copy);
        if (!missing(targets)) {
            List<JsonNode> targetList = new ArrayList<>();
            if (targets.isArray()) targets.forEach(targetList::add);
            else targetList.add(targets);
            for (JsonNode target : targetList) {
                if (!target.isObject()) continue;
                ObjectNode targetObj = (ObjectNode) target;
                // Merge update fields (evaluate update against the ORIGINAL target values).
                JsonNode update = updateFn.apply(target);
                if (!missing(update)) {
                    if (!update.isObject())
                        throw new RuntimeEvaluationException(
                                "T2011", "The update clause of the transform operator requires an object literal as the second operand");
                    update.fields().forEachRemaining(e -> targetObj.set(e.getKey(), e.getValue()));
                }
                // Delete fields.
                if (!missing(deleteFields)) {
                    if (!deleteFields.isTextual() && !deleteFields.isArray())
                        throw new RuntimeEvaluationException(
                                "T2012", "The delete clause of the transform operator is not valid, must be a string or array of strings");
                    if (deleteFields.isTextual()) {
                        targetObj.remove(deleteFields.textValue());
                    } else {
                        for (JsonNode f : deleteFields) {
                            if (f.isTextual()) targetObj.remove(f.textValue());
                        }
                    }
                }
            }
        }
        return copy;
    }

    public static JsonNode fn_merge(JsonNode arr) {
        if (missing(arr)) return MISSING;
        ObjectNode result = NF.objectNode();
        Iterable<JsonNode> items = arr.isArray() ? arr : List.of(arr);
        for (JsonNode item : items) {
            if (item.isObject()) item.fields().forEachRemaining(e -> result.set(e.getKey(), e.getValue()));
        }
        return result;
    }

    /**
     * Returns the value associated with {@code key} in {@code obj}.
     * When {@code obj} is an array of objects, returns an array of all matching values.
     */
    public static JsonNode fn_lookup(JsonNode obj, JsonNode key) {
        if (missing(obj) || missing(key)) return MISSING;
        String k = key.textValue();
        if (k == null) return MISSING;
        if (obj.isObject()) {
            JsonNode v = obj.get(k);
            return (v == null || v.isMissingNode()) ? MISSING : v;
        }
        if (obj.isArray()) {
            ArrayNode result = NF.arrayNode();
            for (JsonNode elem : obj) {
                if (elem.isObject()) {
                    JsonNode v = elem.get(k);
                    if (v != null && !v.isMissingNode()) appendToSequence(result, v);
                }
            }
            return unwrap(result);
        }
        return MISSING;
    }

    /**
     * Splits each key/value pair of {@code obj} into a separate single-key object,
     * returning an array of those objects.
     */
    public static JsonNode fn_spread(JsonNode obj) {
        if (missing(obj)) return MISSING;
        if (obj.isArray()) {
            ArrayNode result = NF.arrayNode();
            for (JsonNode elem : obj) {
                JsonNode spread = fn_spread(elem);
                if (!missing(spread)) appendToSequence(result, spread);
            }
            return unwrap(result);
        }
        if (!obj.isObject()) return obj;
        ArrayNode result = NF.arrayNode();
        obj.fields().forEachRemaining(e -> {
            ObjectNode single = NF.objectNode();
            single.set(e.getKey(), e.getValue());
            result.add(single);
        });
        return result;
    }

    /**
     * Throws a {@link RuntimeEvaluationException} with {@code message} if
     * {@code condition} is falsy; otherwise returns MISSING (undefined).
     */
    public static JsonNode fn_assert(JsonNode condition, JsonNode message)
            throws RuntimeEvaluationException {
        if (!missing(condition) && !condition.isBoolean())
            throw new RuntimeEvaluationException(
                    "T0410", "Argument 1 of function $assert must be a boolean");
        if (!isTruthy(condition)) {
            String m = missing(message) ? "$assert() statement failed" : toText(message);
            throw new RuntimeEvaluationException("D3141", m);
        }
        return MISSING;
    }

    public static JsonNode fn_each(JsonNode obj, JsonataLambda fn)
            throws RuntimeEvaluationException {
        if (missing(obj) || !obj.isObject()) return MISSING;
        ArrayNode result = NF.arrayNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            // fn receives [value, key, object] — value first, matching JSONata spec
            JsonNode triple = NF.arrayNode().add(e.getValue()).add(NF.textNode(e.getKey())).add(obj);
            JsonNode r = fn.apply(triple);
            if (!missing(r)) result.add(r);
        }
        return unwrap(result);
    }

    // =========================================================================
    // Built-in functions — date/time
    // =========================================================================

    /**
     * {@code $now()} — returns the evaluation-start timestamp as an ISO 8601 UTC string.
     *
     * <p>The same frozen timestamp is returned for every call within one evaluation,
     * per the JSONata specification.
     */
    public static JsonNode fn_now() {
        return NF.textNode(DateTimeUtils.millisToIso(EvaluationContext.evaluationMillis()));
    }

    /**
     * {@code $now(picture)} — returns the evaluation-start timestamp formatted with
     * an XPath/XQuery picture string (UTC timezone).
     */
    public static JsonNode fn_now(JsonNode picture) throws RuntimeEvaluationException {
        if (missing(picture)) return fn_now();
        return NF.textNode(DateTimeUtils.millisToPicture(
                EvaluationContext.evaluationMillis(), toText(picture), null));
    }

    /**
     * {@code $now(picture, timezone)} — returns the evaluation-start timestamp formatted
     * with an XPath/XQuery picture string in the given {@code ±HHMM} timezone.
     */
    public static JsonNode fn_now(JsonNode picture, JsonNode timezone)
            throws RuntimeEvaluationException {
        if (missing(picture)) return fn_now();
        String tz = missing(timezone) ? null : toText(timezone);
        return NF.textNode(DateTimeUtils.millisToPicture(
                EvaluationContext.evaluationMillis(), toText(picture), tz));
    }

    /**
     * {@code $millis()} — returns the evaluation-start timestamp as milliseconds
     * since the Unix epoch.
     *
     * <p>The same frozen value is returned for every call within one evaluation.
     */
    public static JsonNode fn_millis() {
        return NF.numberNode(EvaluationContext.evaluationMillis());
    }

    /**
     * {@code $fromMillis(number)} — converts milliseconds since the Unix epoch to
     * an ISO 8601 UTC string, e.g. {@code "2017-11-07T15:12:37.121Z"}.
     */
    public static JsonNode fn_fromMillis(JsonNode millis) throws RuntimeEvaluationException {
        if (missing(millis)) return MISSING;
        return NF.textNode(DateTimeUtils.millisToIso((long) toNumber(millis)));
    }

    /**
     * {@code $fromMillis(number, picture)} — converts milliseconds since the Unix epoch
     * to a string formatted with an XPath/XQuery picture string (UTC timezone).
     */
    public static JsonNode fn_fromMillis(JsonNode millis, JsonNode picture)
            throws RuntimeEvaluationException {
        if (missing(millis)) return MISSING;
        if (missingOrEmpty(picture)) return fn_fromMillis(millis);
        return NF.textNode(DateTimeUtils.millisToPicture(
                (long) toNumber(millis), toText(picture), ""));
    }

    /**
     * {@code $fromMillis(number, picture, timezone)} — converts milliseconds since the
     * Unix epoch to a string formatted with an XPath/XQuery picture string in the given
     * {@code ±HHMM} timezone.
     */
    public static JsonNode fn_fromMillis(JsonNode millis, JsonNode picture, JsonNode timezone)
            throws RuntimeEvaluationException {
        if (missing(millis)) return MISSING;
        if (missingOrEmpty(picture) && missingOrEmpty(timezone)) return fn_fromMillis(millis);
        if (missingOrEmpty(picture)) {
            String tz = missingOrEmpty(timezone) ? null : toText(timezone);
            return NF.textNode(DateTimeUtils.millisToIso((long) toNumber(millis), tz));
        }
        String tz = missingOrEmpty(timezone) ? null : toText(timezone);
        return NF.textNode(DateTimeUtils.millisToPicture(
                (long) toNumber(millis), toText(picture), tz));
    }

    /**
     * {@code $toMillis(timestamp)} — parses an ISO 8601 timestamp string and returns
     * milliseconds since the Unix epoch.
     */
    public static JsonNode fn_toMillis(JsonNode timestamp) throws RuntimeEvaluationException {
        if (missing(timestamp)) return MISSING;
        return NF.numberNode(DateTimeUtils.isoToMillis(toText(timestamp)));
    }

    /**
     * {@code $toMillis(timestamp, picture)} — parses a timestamp string using an
     * XPath/XQuery picture string and returns milliseconds since the Unix epoch.
     */
    public static JsonNode fn_toMillis(JsonNode timestamp, JsonNode picture)
            throws RuntimeEvaluationException {
        if (missing(timestamp)) return MISSING;
        if (missing(picture)) return fn_toMillis(timestamp);
        long result = DateTimeUtils.pictureToMillis(toText(timestamp), toText(picture));
        if (result == Long.MIN_VALUE) return MISSING;
        return NF.numberNode(result);
    }

    // =========================================================================
    // Error
    // =========================================================================

    public static JsonNode fn_error(JsonNode msg) throws RuntimeEvaluationException {
        if (!missing(msg) && !msg.isTextual())
            throw new RuntimeEvaluationException(
                    "T0410", "$error: argument must be a string");
        String m = missing(msg) ? "$error() function evaluated" : msg.textValue();
        throw new RuntimeEvaluationException("D3137", m);
    }

    // =========================================================================
    // Chain operator helper (thin delegations to LambdaRegistry)
    // =========================================================================

    /**
     * Implements the {@code ~>} (chain/pipe) operator.
     *
     * <ul>
     *   <li>If {@code arg} is also a lambda token the two are <em>composed</em>:
     *       returns a new lambda that applies {@code arg} first, then {@code fn}.
     *       This supports {@code $f ~> $g} yielding a composed function.</li>
     *   <li>Otherwise {@code fn} is invoked with {@code arg} as its argument
     *       (standard value-piping: {@code value ~> $fn}).</li>
     * </ul>
     */
    public static JsonNode fn_pipe(JsonNode arg, JsonNode fn)
            throws RuntimeEvaluationException {
        return LambdaRegistry.fn_pipe(arg, fn);
    }

    /**
     * Applies {@code fn} to {@code arg} — used when calling a user-defined
     * lambda stored in a local variable.
     * {@code fn} must be a lambda token produced by {@link #lambdaNode}.
     */
    public static JsonNode fn_apply(JsonNode fn, JsonNode arg)
            throws RuntimeEvaluationException {
        return LambdaRegistry.fn_apply(fn, arg);
    }

    /**
     * Tail-call variant of {@link #fn_apply}: stores the next call as a pending
     * tail call and returns a sentinel so the trampoline loop in
     * {@link LambdaRegistry#fn_apply} can iterate without growing the JVM stack.
     * Must only be called from strict tail position in a lambda body.
     */
    public static JsonNode fn_apply_tco(JsonNode fn, JsonNode arg)
            throws RuntimeEvaluationException {
        return LambdaRegistry.fn_apply_tco(fn, arg);
    }

    /**
     * Registers {@code fn} in the lambda registry and returns a sentinel
     * {@link TextNode} that can be stored as a {@link JsonNode} value and
     * later resolved by {@link #fn_apply}.
     */
    public static JsonNode lambdaNode(JsonataLambda fn) {
        return LambdaRegistry.lambdaNode(fn);
    }

    // =========================================================================
    // Regex node (thin delegation to RegexRegistry)
    // =========================================================================

    /**
     * Compiles a JSONata regex literal and stores it in the registry.
     * Returns a sentinel {@link TextNode} with prefix {@code "__rx:"} that
     * can be passed through the Jackson type system and later resolved by
     * the regex runtime.
     *
     * @param pattern the regex pattern string (without delimiters)
     * @param flags   flags string: {@code "i"} for case-insensitive,
     *                {@code "m"} for multiline, or {@code ""} for none
     */
    public static JsonNode regexNode(String pattern, String flags)
            throws RuntimeEvaluationException {
        return RegexRegistry.regexNode(pattern, flags);
    }

    // =========================================================================
    // Bindings support (thin delegations to EvaluationContext)
    // =========================================================================

    /**
     * Merges permanent bindings from the generated class with per-evaluation
     * bindings and installs the result as the active bindings for this thread.
     *
     * <p>Must be paired with a {@link #endEvaluation()} call in a finally block.
     *
     * @param permanentValues    permanent named values registered on the expression instance
     * @param permanentFunctions permanent named functions registered on the expression instance
     * @param perEval            per-evaluation bindings, or {@code null}
     * @param instanceRegexes    per-instance regex cache from the expression instance
     */
    public static void beginEvaluation(Map<String, JsonNode> permanentValues,
                                       Map<String, JsonataBoundFunction> permanentFunctions,
                                       JsonataBindings perEval,
                                       Map<String, org.joni.Regex> instanceRegexes) {
        EvaluationContext.beginEvaluation(permanentValues, permanentFunctions, perEval, instanceRegexes);
    }

    /**
     * Clears the active bindings for the current thread.
     * Always call this in a {@code finally} block after {@link #beginEvaluation}.
     */
    public static void endEvaluation() {
        EvaluationContext.endEvaluation();
    }

    /**
     * Resolves a named value from the active bindings.
     *
     * @param name the variable name (without the leading {@code $})
     * @return the bound {@link JsonNode}, or {@link #MISSING} if not bound
     */
    public static JsonNode resolveBinding(String name) {
        return EvaluationContext.resolveBinding(name);
    }

    /**
     * Calls a named function from the active bindings.
     *
     * @param name the function name (without the leading {@code $})
     * @param args the arguments to pass
     * @return the function result, or {@link #MISSING} if no function is bound to {@code name}
     * @throws RuntimeEvaluationException if the function throws
     */
    public static JsonNode callBoundFunction(String name, JsonNode[] args)
            throws RuntimeEvaluationException {
        return EvaluationContext.callBoundFunction(name, args);
    }

    // =========================================================================
    // Internal helpers (package-private so helper classes can call them)
    // =========================================================================

    /** Public variant used by generated coalesce expressions ({@code ??}). */
    public static boolean isMissing(JsonNode n) {
        return n == null || n.isMissingNode();
    }

    static boolean missing(JsonNode n) {
        return n == null || n.isMissingNode();
    }

    static boolean missingOrEmpty(JsonNode n) {
        return n == null || n.isMissingNode() || n.isTextual() && n.asText().isEmpty();
    }

    /**
     * Converts a {@link JsonNode} to a Java {@code double}.
     * Coerces string representations of numbers; throws for other types.
     */
    public static double toNumber(JsonNode n) throws RuntimeEvaluationException {
        if (n.isNumber()) return n.doubleValue();
        if (n.isTextual()) {
            try { return Double.parseDouble(n.textValue()); }
            catch (NumberFormatException e) {
                throw new RuntimeEvaluationException("D3020", "Cannot coerce string to number: " + n.textValue());
            }
        }
        if (n.isBoolean()) return n.booleanValue() ? 1 : 0;
        throw new RuntimeEvaluationException("D3020", "Cannot coerce " + n.getNodeType() + " to number");
    }

    /** Converts a {@link JsonNode} to a String representation. */
    static String toText(JsonNode n) throws RuntimeEvaluationException {
        if (n.isTextual()) {
            // Lambda/function tokens serialize as empty string per JSONata spec
            if (LambdaRegistry.isLambdaToken(n) || RegexRegistry.isRegexToken(n)) return "";
            return n.textValue();
        }
        if (n.isNumber()) return numberToString(n.doubleValue());
        if (n.isBoolean()) return String.valueOf(n.booleanValue());
        if (n.isNull()) return "null";
        // Arrays/objects may contain lambda-valued fields — sanitize before serializing
        return sanitizeForString(n).toString();
    }

    /** Replaces lambda/regex tokens with empty string nodes recursively for JSON serialization. */
    static JsonNode sanitizeForString(JsonNode n) {
        if (n.isTextual() && (LambdaRegistry.isLambdaToken(n) || RegexRegistry.isRegexToken(n))) {
            return NF.textNode("");
        }
        if (n.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode copy = NF.arrayNode();
            n.forEach(elem -> copy.add(sanitizeForString(elem)));
            return copy;
        }
        if (n.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode copy = NF.objectNode();
            n.fields().forEachRemaining(e -> copy.set(e.getKey(), sanitizeForString(e.getValue())));
            return copy;
        }
        return n;
    }

    /**
     * Converts a double to string using JSONata's number-to-string rules:
     * integers render without decimal point, floating-point values use at most
     * 13 significant digits (to eliminate floating-point noise beyond double
     * precision), trailing zeros are stripped, and exponential notation uses
     * a lowercase {@code e}.
     */
    static String numberToString(double v) {
        if (Double.isInfinite(v) || Double.isNaN(v)) return String.valueOf(v);
        // Whole numbers
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            if (Math.abs(v) < 1e15) return String.valueOf((long) v);
            // JavaScript: values < 1e21 use plain decimal, >= 1e21 use scientific notation
            if (Math.abs(v) < 1e21) return new java.math.BigDecimal(v).toBigInteger().toString();
            // >= 1e21: use scientific notation (match JavaScript Number.toString())
            java.math.BigDecimal bd = new java.math.BigDecimal(v)
                    .round(new java.math.MathContext(15, java.math.RoundingMode.HALF_UP));
            String s = bd.toString().replace('E', 'e');
            s = s.replaceAll("\\.0+e", "e").replaceAll("e(\\d)", "e+$1");
            return s;
        }
        // Fractional: round to 15 significant figures to match JSONata / JavaScript behavior
        java.math.BigDecimal bd = new java.math.BigDecimal(v)
                .round(new java.math.MathContext(15, java.math.RoundingMode.HALF_UP))
                .stripTrailingZeros();
        String s = bd.toPlainString();
        // Very small numbers (< 0.001) → use scientific notation
        if (s.startsWith("0.0000") || s.startsWith("-0.0000")) {
            s = bd.toString(); // scientific notation from BigDecimal (uses E)
            s = s.replace('E', 'e');
            s = s.replaceAll("\\.0+e", "e").replaceAll("e(\\d)", "e+$1");
        }
        return s;
    }

    /** Converts an Object (JsonNode or RangeHolder) to a String representation. */
    static String toText(Object o) throws RuntimeEvaluationException {
        if (o instanceof JsonNode jn) return toText(jn);
        if (o instanceof RangeHolder rh) return rh.from() + ".." + rh.to();
        return String.valueOf(o);
    }

    /** Handles negative indices (count from end). */
    static int clampIndex(int i, int len) {
        if (i < 0) i = Math.max(0, len + i);
        return Math.min(i, len);
    }

    /**
     * Adds {@code val} to {@code acc}, flattening one level of arrays
     * (JSONata sequence flattening rule).
     */
    static void appendToSequence(ArrayNode acc, JsonNode val) {
        if (val.isArray()) val.forEach(acc::add);
        else if (!val.isMissingNode()) acc.add(val);
    }

    /**
     * Returns the single element if the array has exactly one item, otherwise
     * returns the array as-is. Empty arrays return {@link #MISSING}.
     */
    static JsonNode unwrap(ArrayNode arr) {
        return switch (arr.size()) {
            case 0 -> MISSING;
            case 1 -> arr.get(0);
            default -> arr;
        };
    }

    public static JsonNode unwrap(JsonNode node) {
        if (node == null || node.isMissingNode()) return MISSING;
        if (!node.isArray()) return node;
        return unwrap((ArrayNode) node);
    }
}
