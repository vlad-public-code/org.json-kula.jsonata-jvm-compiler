package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataBoundFunction;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;

import java.util.*;

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
        JsonNode eval(String expr, JsonNode context) throws JsonataEvaluationException;
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
            ArrayNode result = NF.arrayNode();
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
            for (JsonNode elem : node) appendToSequence(result, wildcard(elem));
            return unwrap(result);
        }
        if (node.isObject()) {
            ArrayNode result = NF.arrayNode();
            node.fields().forEachRemaining(e -> result.add(e.getValue()));
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
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                acc.add(e.getValue());
                collectDescendants(e.getValue(), acc);
            });
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
            throws JsonataEvaluationException {
        if (seq == null || seq.isMissingNode()) return MISSING;
        if (!seq.isArray()) {
            return isTruthy(predicate.apply(seq)) ? seq : MISSING;
        }
        ArrayNode result = NF.arrayNode();
        for (JsonNode elem : seq) {
            if (isTruthy(predicate.apply(elem))) result.add(elem);
        }
        return unwrap(result);
    }

    /**
     * Returns the element at {@code index} (zero-based, negatives count from end).
     * If {@code index} is a non-integer JsonNode, coerces to int.
     */
    public static JsonNode subscript(JsonNode seq, JsonNode index)
            throws JsonataEvaluationException {
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
            throws JsonataEvaluationException {
        if (seq == null || seq.isMissingNode()) return MISSING;
        if (!seq.isArray()) return MISSING;
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
            throws JsonataEvaluationException {
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
            throws JsonataEvaluationException {
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
     * Unlike {@link #mapStep}, this does NOT add each result directly - it keeps
     * the result as-is, allowing array constructors to produce nested arrays.
     */
    public static JsonNode mapConstructorStep(JsonNode node, JsonataLambda fn)
            throws JsonataEvaluationException {
        if (node == null || node.isMissingNode()) return MISSING;
        if (node.isArray()) {
            ArrayNode result = NF.arrayNode();
            for (JsonNode elem : node) {
                JsonNode val = fn.apply(elem);
                if (!val.isMissingNode()) {
                    // Don't flatten - keep the result as-is (especially important for array constructors)
                    if (val.isArray()) {
                        // For array constructors like [address], the inner result is already an array
                        // that we want to keep as a single element, not flatten
                        result.add(val);
                    } else {
                        result.add(val);
                    }
                }
            }
            return unwrap(result);
        }
        return fn.apply(node);
    }

    // =========================================================================
    // Arithmetic
    // =========================================================================

    public static JsonNode add(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return MISSING;
        if (!a.isNumber()) throw new JsonataEvaluationException(
                "The left side of the + operator must evaluate to a number");
        if (!b.isNumber()) throw new JsonataEvaluationException(
                "The right side of the + operator must evaluate to a number");
        return numNode(a.doubleValue() + b.doubleValue());
    }

    public static JsonNode subtract(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return MISSING;
        if (!a.isNumber()) throw new JsonataEvaluationException(
                "The left side of the - operator must evaluate to a number");
        if (!b.isNumber()) throw new JsonataEvaluationException(
                "The right side of the - operator must evaluate to a number");
        return numNode(a.doubleValue() - b.doubleValue());
    }

    public static JsonNode multiply(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return MISSING;
        if (!a.isNumber()) throw new JsonataEvaluationException(
                "The left side of the * operator must evaluate to a number");
        if (!b.isNumber()) throw new JsonataEvaluationException(
                "The right side of the * operator must evaluate to a number");
        return numNode(a.doubleValue() * b.doubleValue());
    }

    public static JsonNode divide(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return MISSING;
        if (!a.isNumber()) throw new JsonataEvaluationException(
                "The left side of the / operator must evaluate to a number");
        if (!b.isNumber()) throw new JsonataEvaluationException(
                "The right side of the / operator must evaluate to a number");
        double denom = b.doubleValue();
        if (denom == 0) throw new JsonataEvaluationException("Division by zero");
        return numNode(a.doubleValue() / denom);
    }

    public static JsonNode modulo(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return MISSING;
        if (!a.isNumber()) throw new JsonataEvaluationException(
                "The left side of the % operator must evaluate to a number");
        if (!b.isNumber()) throw new JsonataEvaluationException(
                "The right side of the % operator must evaluate to a number");
        double denom = b.doubleValue();
        if (denom == 0) throw new JsonataEvaluationException("Modulo by zero");
        return numNode(a.doubleValue() % denom);
    }

    public static JsonNode negate(JsonNode a) throws JsonataEvaluationException {
        if (missing(a)) return MISSING;
        if (!a.isNumber()) throw new JsonataEvaluationException(
                "The operand of the - operator must evaluate to a number");
        return numNode(-a.doubleValue());
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

    public static JsonNode concat(JsonNode a, JsonNode b) throws JsonataEvaluationException {
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
        if (a.isNumber() && b.isNumber()) return bool(a.doubleValue() == b.doubleValue());
        if (a.isTextual() && b.isTextual()) return bool(a.textValue().equals(b.textValue()));
        if (a.isBoolean() && b.isBoolean()) return bool(a.booleanValue() == b.booleanValue());
        if (a.isNull() && b.isNull()) return bool(true);
        return bool(a.equals(b));
    }

    public static JsonNode ne(JsonNode a, JsonNode b) {
        if (missing(a) || missing(b)) return bool(false);
        return bool(!eq(a, b).booleanValue());
    }

    public static JsonNode lt(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return bool(false);
        if (a.isNumber() && b.isNumber()) return bool(a.doubleValue() < b.doubleValue());
        if (a.isTextual() && b.isTextual()) return bool(a.textValue().compareTo(b.textValue()) < 0);
        throw new JsonataEvaluationException("Cannot compare " + a.getNodeType() + " with " + b.getNodeType());
    }

    public static JsonNode le(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return bool(false);
        if (a.isNumber() && b.isNumber()) return bool(a.doubleValue() <= b.doubleValue());
        if (a.isTextual() && b.isTextual()) return bool(a.textValue().compareTo(b.textValue()) <= 0);
        throw new JsonataEvaluationException("Cannot compare " + a.getNodeType() + " with " + b.getNodeType());
    }

    public static JsonNode gt(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return bool(false);
        if (a.isNumber() && b.isNumber()) return bool(a.doubleValue() > b.doubleValue());
        if (a.isTextual() && b.isTextual()) return bool(a.textValue().compareTo(b.textValue()) > 0);
        throw new JsonataEvaluationException("Cannot compare " + a.getNodeType() + " with " + b.getNodeType());
    }

    public static JsonNode ge(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return bool(false);
        if (a.isNumber() && b.isNumber()) return bool(a.doubleValue() >= b.doubleValue());
        if (a.isTextual() && b.isTextual()) return bool(a.textValue().compareTo(b.textValue()) >= 0);
        throw new JsonataEvaluationException("Cannot compare " + a.getNodeType() + " with " + b.getNodeType());
    }

    // =========================================================================
    // Boolean logic
    // =========================================================================

    public static JsonNode and_(JsonNode a, JsonNode b) {
        return bool(isTruthy(a) && isTruthy(b));
    }

    public static JsonNode or_(JsonNode a, JsonNode b) {
        return bool(isTruthy(a) || isTruthy(b));
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

    /** Marker to indicate an array element should be flattened (from range expression). */
    public record RangeHolder(int from, int to) {}

    /**
     * Creates a JSON array from the given elements, handling RangeHolder markers
     * to flatten range expressions while preserving nested literal arrays.
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
                if (!missing(jn)) result.add(jn);
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
    public static JsonNode object(Object... keyValuePairs) throws JsonataEvaluationException {
        if (keyValuePairs.length % 2 != 0) {
            throw new JsonataEvaluationException("object() requires an even number of arguments");
        }
        ObjectNode result = NF.objectNode();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            JsonNode key = (JsonNode) keyValuePairs[i];
            JsonNode val = (JsonNode) keyValuePairs[i + 1];
            if (!missing(key) && !missing(val)) {
                result.set(toText(key), val);
            }
        }
        return result;
    }

    /** Creates an integer range array {@code [from, from+1, ..., to]}. */
    public static JsonNode range(JsonNode from, JsonNode to) throws JsonataEvaluationException {
        if (missing(from) || missing(to)) return MISSING;
        if (!from.isNumber()) throw new JsonataEvaluationException(
                "The left side of the range operator (..) must evaluate to an integer");
        if (!to.isNumber()) throw new JsonataEvaluationException(
                "The right side of the range operator (..) must evaluate to an integer");
        double fd = from.doubleValue();
        double td = to.doubleValue();
        if (fd != Math.floor(fd)) throw new JsonataEvaluationException(
                "The left side of the range operator (..) must be an integer");
        if (td != Math.floor(td)) throw new JsonataEvaluationException(
                "The right side of the range operator (..) must be an integer");
        int f = (int) fd;
        int t = (int) td;
        ArrayNode result = NF.arrayNode();
        for (int i = f; i <= t; i++) result.add(i);
        return result;
    }

    // =========================================================================
    // Built-in functions — type coercion
    // =========================================================================

    public static JsonNode fn_string(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.textNode(toText(arg));
    }

    public static JsonNode fn_string(JsonNode arg, JsonNode prettify) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        if (missing(prettify) || !isTruthy(prettify)) return fn_string(arg);
        return StringBuiltins.fn_string_prettify(arg);
    }

    public static JsonNode fn_number(JsonNode arg) throws JsonataEvaluationException {
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

    public static JsonNode fn_floor(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.numberNode((long) Math.floor(toNumber(arg)));
    }

    public static JsonNode fn_ceil(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.numberNode((long) Math.ceil(toNumber(arg)));
    }

    public static JsonNode fn_round(JsonNode arg) throws JsonataEvaluationException {
        return NumericBuiltins.fn_round(arg, MISSING);
    }

    public static JsonNode fn_round(JsonNode arg, JsonNode precision) throws JsonataEvaluationException {
        return NumericBuiltins.fn_round(arg, precision);
    }

    public static JsonNode fn_abs(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        return numNode(Math.abs(toNumber(arg)));
    }

    public static JsonNode fn_sqrt(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        double v = toNumber(arg);
        if (v < 0) throw new JsonataEvaluationException("$sqrt: argument must be non-negative");
        return numNode(Math.sqrt(v));
    }

    public static JsonNode fn_power(JsonNode base, JsonNode exp) throws JsonataEvaluationException {
        if (missing(base) || missing(exp)) return MISSING;
        double result = Math.pow(toNumber(base), toNumber(exp));
        if (Double.isInfinite(result) || Double.isNaN(result))
            throw new JsonataEvaluationException(
                    "$power: the result of the power function is out of range");
        return numNode(result);
    }

    public static JsonNode fn_random() {
        return NumericBuiltins.fn_random();
    }

    public static JsonNode fn_formatBase(JsonNode number, JsonNode radix)
            throws JsonataEvaluationException {
        return NumericBuiltins.fn_formatBase(number, radix);
    }

    public static JsonNode fn_formatNumber(JsonNode number, JsonNode picture)
            throws JsonataEvaluationException {
        return NumericBuiltins.fn_formatNumber(number, picture, MISSING);
    }

    public static JsonNode fn_formatNumber(JsonNode number, JsonNode picture, JsonNode options)
            throws JsonataEvaluationException {
        return NumericBuiltins.fn_formatNumber(number, picture, options);
    }

    public static JsonNode fn_formatInteger(JsonNode number, JsonNode picture)
            throws JsonataEvaluationException {
        return NumericBuiltins.fn_formatInteger(number, picture);
    }

    public static JsonNode fn_parseInteger(JsonNode string, JsonNode picture)
            throws JsonataEvaluationException {
        return NumericBuiltins.fn_parseInteger(string, picture);
    }

    // =========================================================================
    // Built-in functions — string (thin delegations to StringBuiltins)
    // =========================================================================

    public static JsonNode fn_uppercase(JsonNode arg) throws JsonataEvaluationException {
        return StringBuiltins.fn_uppercase(arg);
    }

    public static JsonNode fn_lowercase(JsonNode arg) throws JsonataEvaluationException {
        return StringBuiltins.fn_lowercase(arg);
    }

    public static JsonNode fn_trim(JsonNode arg) throws JsonataEvaluationException {
        return StringBuiltins.fn_trim(arg);
    }

    public static JsonNode fn_length(JsonNode arg) throws JsonataEvaluationException {
        return StringBuiltins.fn_length(arg);
    }

    public static JsonNode fn_substring(JsonNode str, JsonNode start)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_substring(str, start);
    }

    public static JsonNode fn_substring(JsonNode str, JsonNode start, JsonNode length)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_substring(str, start, length);
    }

    public static JsonNode fn_substringBefore(JsonNode str, JsonNode chars)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_substringBefore(str, chars);
    }

    public static JsonNode fn_substringAfter(JsonNode str, JsonNode chars)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_substringAfter(str, chars);
    }

    public static JsonNode fn_contains(JsonNode str, JsonNode search)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_contains(str, search);
    }

    public static JsonNode fn_split(JsonNode str, JsonNode separator)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_split(str, separator);
    }

    public static JsonNode fn_split(JsonNode str, JsonNode separator, JsonNode limit)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_split(str, separator, limit);
    }

    public static JsonNode fn_match(JsonNode str, JsonNode pattern)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_match(str, pattern);
    }

    public static JsonNode fn_match(JsonNode str, JsonNode pattern, JsonNode limit)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_match(str, pattern, limit);
    }

    public static JsonNode fn_replace(JsonNode str, JsonNode pattern, JsonNode replacement)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_replace(str, pattern, replacement);
    }

    public static JsonNode fn_replace(JsonNode str, JsonNode pattern,
                                       JsonNode replacement, JsonNode limit)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_replace(str, pattern, replacement, limit);
    }

    public static JsonNode fn_join(JsonNode arr, JsonNode separator)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_join(arr, separator);
    }

    public static JsonNode fn_pad(JsonNode str, JsonNode width) throws JsonataEvaluationException {
        return StringBuiltins.fn_pad(str, width, MISSING);
    }

    public static JsonNode fn_pad(JsonNode str, JsonNode width, JsonNode padChar)
            throws JsonataEvaluationException {
        return StringBuiltins.fn_pad(str, width, padChar);
    }

    public static JsonNode fn_eval(JsonNode expr) throws JsonataEvaluationException {
        return StringBuiltins.fn_eval(expr, MISSING);
    }

    public static JsonNode fn_eval(JsonNode expr, JsonNode context) throws JsonataEvaluationException {
        return StringBuiltins.fn_eval(expr, context);
    }

    public static JsonNode fn_base64encode(JsonNode str) throws JsonataEvaluationException {
        return StringBuiltins.fn_base64encode(str);
    }

    public static JsonNode fn_base64decode(JsonNode str) throws JsonataEvaluationException {
        return StringBuiltins.fn_base64decode(str);
    }

    public static JsonNode fn_encodeUrlComponent(JsonNode str) throws JsonataEvaluationException {
        return StringBuiltins.fn_encodeUrlComponent(str);
    }

    public static JsonNode fn_decodeUrlComponent(JsonNode str) throws JsonataEvaluationException {
        return StringBuiltins.fn_decodeUrlComponent(str);
    }

    public static JsonNode fn_encodeUrl(JsonNode str) throws JsonataEvaluationException {
        return StringBuiltins.fn_encodeUrl(str);
    }

    public static JsonNode fn_decodeUrl(JsonNode str) throws JsonataEvaluationException {
        return StringBuiltins.fn_decodeUrl(str);
    }

    // =========================================================================
    // Built-in functions — array / sequence
    // =========================================================================

    public static JsonNode fn_count(JsonNode arg) {
        if (missing(arg)) return NF.numberNode(0);
        return NF.numberNode(arg.isArray() ? arg.size() : 1);
    }

    public static JsonNode fn_sum(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return NF.numberNode(0);
        if (!arg.isArray()) {
            requireNumericArg(arg, "$sum");
            return numNode(arg.doubleValue());
        }
        double sum = 0;
        for (JsonNode elem : arg) { requireNumericArg(elem, "$sum"); sum += elem.doubleValue(); }
        return numNode(sum);
    }

    public static JsonNode fn_max(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) { requireNumericArg(arg, "$max"); return numNode(arg.doubleValue()); }
        if (arg.size() == 0) return MISSING;
        double max = Double.NEGATIVE_INFINITY;
        for (JsonNode elem : arg) { requireNumericArg(elem, "$max"); double v = elem.doubleValue(); if (v > max) max = v; }
        return numNode(max);
    }

    public static JsonNode fn_min(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) { requireNumericArg(arg, "$min"); return numNode(arg.doubleValue()); }
        if (arg.size() == 0) return MISSING;
        double min = Double.POSITIVE_INFINITY;
        for (JsonNode elem : arg) { requireNumericArg(elem, "$min"); double v = elem.doubleValue(); if (v < min) min = v; }
        return numNode(min);
    }

    public static JsonNode fn_average(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) { requireNumericArg(arg, "$average"); return numNode(arg.doubleValue()); }
        if (arg.size() == 0) return MISSING;
        double sum = 0;
        for (JsonNode elem : arg) { requireNumericArg(elem, "$average"); sum += elem.doubleValue(); }
        return numNode(sum / arg.size());
    }

    private static void requireNumericArg(JsonNode n, String fnName) throws JsonataEvaluationException {
        if (!n.isNumber())
            throw new JsonataEvaluationException(
                    fnName + " requires an array of numbers, but found " + n.getNodeType());
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
        List<JsonNode> seen = new ArrayList<>();
        for (JsonNode elem : arg) {
            boolean dup = seen.stream().anyMatch(s -> s.equals(elem));
            if (!dup) seen.add(elem);
        }
        ArrayNode result = NF.arrayNode();
        seen.forEach(result::add);
        return result;
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
        int minLen = Integer.MAX_VALUE;
        for (JsonNode arr : arrays) {
            if (missing(arr)) return MISSING;
            if (!arr.isArray()) return MISSING;
            minLen = Math.min(minLen, arr.size());
        }
        if (minLen == Integer.MAX_VALUE) minLen = 0;
        ArrayNode result = NF.arrayNode();
        for (int i = 0; i < minLen; i++) {
            ArrayNode tuple = NF.arrayNode();
            for (JsonNode arr : arrays) tuple.add(arr.get(i));
            result.add(tuple);
        }
        return result;
    }

    // =========================================================================
    // Built-in functions — higher-order sequence (thin delegations to SequenceBuiltins)
    // =========================================================================

    public static JsonNode fn_sort(JsonNode arg) throws JsonataEvaluationException {
        return SequenceBuiltins.fn_sort(arg);
    }

    public static JsonNode fn_sort(JsonNode arg, JsonataLambda keyFn)
            throws JsonataEvaluationException {
        return SequenceBuiltins.fn_sort(arg, keyFn);
    }

    public static JsonNode fn_map(JsonNode arr, JsonataLambda fn)
            throws JsonataEvaluationException {
        return SequenceBuiltins.fn_map(arr, fn);
    }

    public static JsonNode fn_filter(JsonNode arr, JsonataLambda predicate)
            throws JsonataEvaluationException {
        return SequenceBuiltins.fn_filter(arr, predicate);
    }

    public static JsonNode fn_reduce(JsonNode arr, JsonataLambda fn, JsonNode init)
            throws JsonataEvaluationException {
        return SequenceBuiltins.fn_reduce(arr, fn, init);
    }

    /**
     * Variant of {@link #fn_map} for multi-param lambdas.
     * Passes {@code [value, index, array]} to the lambda so that the
     * {@code $i} and {@code $a} parameters are available.
     */
    public static JsonNode fn_map_indexed(JsonNode arr, JsonataLambda fn)
            throws JsonataEvaluationException {
        return SequenceBuiltins.fn_map_indexed(arr, fn);
    }

    /**
     * Variant of {@link #fn_filter} for multi-param lambdas.
     * Passes {@code [value, index, array]} to the predicate.
     */
    public static JsonNode fn_filter_indexed(JsonNode arr, JsonataLambda predicate)
            throws JsonataEvaluationException {
        return SequenceBuiltins.fn_filter_indexed(arr, predicate);
    }

    /**
     * Maps {@code fn} over each element of {@code seq}, passing {@code [element, index]}
     * to the lambda. Results are collected (non-missing) and flattened one level.
     * Used by the positional-binding operator ({@code #$i}).
     */
    public static JsonNode eachIndexed(JsonNode seq, JsonataLambda fn)
            throws JsonataEvaluationException {
        if (seq == null || seq.isMissingNode()) return MISSING;
        List<JsonNode> items = new ArrayList<>();
        if (seq.isArray()) seq.forEach(items::add); else items.add(seq);
        ArrayNode result = NF.arrayNode();
        for (int i = 0; i < items.size(); i++) {
            JsonNode pair = NF.arrayNode().add(items.get(i)).add(NF.numberNode(i));
            JsonNode val = fn.apply(pair);
            if (!val.isMissingNode()) appendToSequence(result, val);
        }
        return unwrap(result);
    }

    /**
     * Returns the single element of {@code arr} for which {@code predicate}
     * returns truthy. Throws if zero or more than one element matches.
     */
    public static JsonNode fn_single(JsonNode arr, JsonataLambda predicate)
            throws JsonataEvaluationException {
        return SequenceBuiltins.fn_single(arr, predicate);
    }

    /**
     * Returns an object containing only the key/value pairs of {@code obj}
     * for which {@code fn} returns truthy.
     * Passes {@code [value, key, object]} to the lambda so both
     * {@code $v} and {@code $k} parameters are available.
     */
    public static JsonNode fn_sift(JsonNode obj, JsonataLambda fn)
            throws JsonataEvaluationException {
        return SequenceBuiltins.fn_sift(obj, fn);
    }

    // =========================================================================
    // Built-in functions — object
    // =========================================================================

    public static JsonNode fn_keys(JsonNode obj) {
        if (missing(obj) || !obj.isObject()) return MISSING;
        ArrayNode result = NF.arrayNode();
        obj.fieldNames().forEachRemaining(result::add);
        return result.isEmpty() ? MISSING : result;
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
            throws JsonataEvaluationException {
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
                if (!missing(update) && update.isObject()) {
                    update.fields().forEachRemaining(e -> targetObj.set(e.getKey(), e.getValue()));
                }
                // Delete fields.
                if (!missing(deleteFields)) {
                    if (deleteFields.isTextual()) {
                        targetObj.remove(deleteFields.textValue());
                    } else if (deleteFields.isArray()) {
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
        if (!obj.isObject()) return MISSING;
        ArrayNode result = NF.arrayNode();
        obj.fields().forEachRemaining(e -> {
            ObjectNode single = NF.objectNode();
            single.set(e.getKey(), e.getValue());
            result.add(single);
        });
        return result;
    }

    /**
     * Throws a {@link JsonataEvaluationException} with {@code message} if
     * {@code condition} is falsy; otherwise returns MISSING (undefined).
     */
    public static JsonNode fn_assert(JsonNode condition, JsonNode message)
            throws JsonataEvaluationException {
        if (!isTruthy(condition)) {
            String m = missing(message) ? "$assert() statement failed" : toText(message);
            throw new JsonataEvaluationException(m);
        }
        return MISSING;
    }

    public static JsonNode fn_each(JsonNode obj, JsonataLambda fn)
            throws JsonataEvaluationException {
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
    public static JsonNode fn_now(JsonNode picture) throws JsonataEvaluationException {
        if (missing(picture)) return fn_now();
        return NF.textNode(DateTimeUtils.millisToPicture(
                EvaluationContext.evaluationMillis(), toText(picture), null));
    }

    /**
     * {@code $now(picture, timezone)} — returns the evaluation-start timestamp formatted
     * with an XPath/XQuery picture string in the given {@code ±HHMM} timezone.
     */
    public static JsonNode fn_now(JsonNode picture, JsonNode timezone)
            throws JsonataEvaluationException {
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
    public static JsonNode fn_fromMillis(JsonNode millis) throws JsonataEvaluationException {
        if (missing(millis)) return MISSING;
        return NF.textNode(DateTimeUtils.millisToIso((long) toNumber(millis)));
    }

    /**
     * {@code $fromMillis(number, picture)} — converts milliseconds since the Unix epoch
     * to a string formatted with an XPath/XQuery picture string (UTC timezone).
     */
    public static JsonNode fn_fromMillis(JsonNode millis, JsonNode picture)
            throws JsonataEvaluationException {
        if (missing(millis)) return MISSING;
        if (missing(picture)) return fn_fromMillis(millis);
        return NF.textNode(DateTimeUtils.millisToPicture(
                (long) toNumber(millis), toText(picture), null));
    }

    /**
     * {@code $fromMillis(number, picture, timezone)} — converts milliseconds since the
     * Unix epoch to a string formatted with an XPath/XQuery picture string in the given
     * {@code ±HHMM} timezone.
     */
    public static JsonNode fn_fromMillis(JsonNode millis, JsonNode picture, JsonNode timezone)
            throws JsonataEvaluationException {
        if (missing(millis)) return MISSING;
        if (missing(picture)) return fn_fromMillis(millis);
        String tz = missing(timezone) ? null : toText(timezone);
        return NF.textNode(DateTimeUtils.millisToPicture(
                (long) toNumber(millis), toText(picture), tz));
    }

    /**
     * {@code $toMillis(timestamp)} — parses an ISO 8601 timestamp string and returns
     * milliseconds since the Unix epoch.
     */
    public static JsonNode fn_toMillis(JsonNode timestamp) throws JsonataEvaluationException {
        if (missing(timestamp)) return MISSING;
        return NF.numberNode(DateTimeUtils.isoToMillis(toText(timestamp)));
    }

    /**
     * {@code $toMillis(timestamp, picture)} — parses a timestamp string using an
     * XPath/XQuery picture string and returns milliseconds since the Unix epoch.
     */
    public static JsonNode fn_toMillis(JsonNode timestamp, JsonNode picture)
            throws JsonataEvaluationException {
        if (missing(timestamp)) return MISSING;
        if (missing(picture)) return fn_toMillis(timestamp);
        return NF.numberNode(DateTimeUtils.pictureToMillis(toText(timestamp), toText(picture)));
    }

    // =========================================================================
    // Error
    // =========================================================================

    public static JsonNode fn_error(JsonNode msg) throws JsonataEvaluationException {
        String m = missing(msg) ? "Error thrown from expression" : toText(msg);
        throw new JsonataEvaluationException(m);
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
            throws JsonataEvaluationException {
        return LambdaRegistry.fn_pipe(arg, fn);
    }

    /**
     * Applies {@code fn} to {@code arg} — used when calling a user-defined
     * lambda stored in a local variable.
     * {@code fn} must be a lambda token produced by {@link #lambdaNode}.
     */
    public static JsonNode fn_apply(JsonNode fn, JsonNode arg)
            throws JsonataEvaluationException {
        return LambdaRegistry.fn_apply(fn, arg);
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
            throws JsonataEvaluationException {
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
     */
    public static void beginEvaluation(Map<String, JsonNode> permanentValues,
                                       Map<String, JsonataBoundFunction> permanentFunctions,
                                       JsonataBindings perEval) {
        EvaluationContext.beginEvaluation(permanentValues, permanentFunctions, perEval);
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
     * @throws JsonataEvaluationException if the function throws
     */
    public static JsonNode callBoundFunction(String name, JsonNode[] args)
            throws JsonataEvaluationException {
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

    /**
     * Converts a {@link JsonNode} to a Java {@code double}.
     * Coerces string representations of numbers; throws for other types.
     */
    public static double toNumber(JsonNode n) throws JsonataEvaluationException {
        if (n.isNumber()) return n.doubleValue();
        if (n.isTextual()) {
            try { return Double.parseDouble(n.textValue()); }
            catch (NumberFormatException e) {
                throw new JsonataEvaluationException("Cannot coerce string to number: " + n.textValue());
            }
        }
        if (n.isBoolean()) return n.booleanValue() ? 1 : 0;
        throw new JsonataEvaluationException("Cannot coerce " + n.getNodeType() + " to number");
    }

    /** Converts a {@link JsonNode} to a String representation. */
    static String toText(JsonNode n) throws JsonataEvaluationException {
        if (n.isTextual()) return n.textValue();
        if (n.isNumber()) {
            double v = n.doubleValue();
            return v == Math.floor(v) && !Double.isInfinite(v)
                    ? String.valueOf((long) v)
                    : String.valueOf(v);
        }
        if (n.isBoolean()) return String.valueOf(n.booleanValue());
        if (n.isNull()) return "null";
        return n.toString();  // array/object: JSON representation
    }

    /** Converts an Object (JsonNode or RangeHolder) to a String representation. */
    static String toText(Object o) throws JsonataEvaluationException {
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
}
