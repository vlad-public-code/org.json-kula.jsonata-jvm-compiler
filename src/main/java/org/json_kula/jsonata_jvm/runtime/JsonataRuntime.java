package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
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

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    /** The JSONata {@code null} literal. */
    public static final JsonNode NULL    = NullNode.getInstance();

    /** The JSONata "undefined" sentinel (a value that is absent). */
    public static final JsonNode MISSING = MissingNode.getInstance();

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
     * Applies {@code fn} with the element as the new context. Used by the
     * translator for complex path steps where context must be rebound.
     */
    public static JsonNode applyStep(JsonNode node, JsonataLambda fn)
            throws JsonataEvaluationException {
        if (node == null || node.isMissingNode()) return MISSING;
        return fn.apply(node);
    }

    // =========================================================================
    // Arithmetic
    // =========================================================================

    public static JsonNode add(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return MISSING;
        return NF.numberNode(toNumber(a) + toNumber(b));
    }

    public static JsonNode subtract(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return MISSING;
        return NF.numberNode(toNumber(a) - toNumber(b));
    }

    public static JsonNode multiply(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return MISSING;
        return NF.numberNode(toNumber(a) * toNumber(b));
    }

    public static JsonNode divide(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return MISSING;
        double denom = toNumber(b);
        if (denom == 0) throw new JsonataEvaluationException("Division by zero");
        return NF.numberNode(toNumber(a) / denom);
    }

    public static JsonNode modulo(JsonNode a, JsonNode b) throws JsonataEvaluationException {
        if (missing(a) || missing(b)) return MISSING;
        double denom = toNumber(b);
        if (denom == 0) throw new JsonataEvaluationException("Modulo by zero");
        return NF.numberNode(toNumber(a) % denom);
    }

    public static JsonNode negate(JsonNode a) throws JsonataEvaluationException {
        if (missing(a)) return MISSING;
        return NF.numberNode(-toNumber(a));
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
        JsonNode result = eq(a, b);
        return bool(!result.booleanValue());
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
     *   <li>empty array / empty object → false</li>
     *   <li>everything else → true</li>
     * </ul>
     */
    public static boolean isTruthy(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return false;
        if (n.isBoolean()) return n.booleanValue();
        if (n.isNumber()) return n.doubleValue() != 0;
        if (n.isTextual()) return !n.textValue().isEmpty();
        if (n.isArray() || n.isObject()) return n.size() > 0;
        return true;
    }

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Creates a JSON array from the given elements, skipping missing values. */
    public static JsonNode array(JsonNode... elements) {
        ArrayNode result = NF.arrayNode();
        for (JsonNode e : elements) if (!missing(e)) result.add(e);
        return result;
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
        int f = (int) toNumber(from);
        int t = (int) toNumber(to);
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

    public static JsonNode fn_number(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.numberNode(toNumber(arg));
    }

    public static JsonNode fn_boolean(JsonNode arg) {
        return bool(isTruthy(arg));
    }

    public static JsonNode fn_not(JsonNode arg) {
        return bool(!isTruthy(arg));
    }

    public static JsonNode fn_type(JsonNode arg) {
        if (missing(arg)) return NF.textNode("undefined");
        if (arg.isNull())    return NF.textNode("null");
        if (arg.isNumber())  return NF.textNode("number");
        if (arg.isTextual()) return NF.textNode("string");
        if (arg.isBoolean()) return NF.textNode("boolean");
        if (arg.isArray())   return NF.textNode("array");
        if (arg.isObject())  return NF.textNode("object");
        return NF.textNode("undefined");
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
        if (missing(arg)) return MISSING;
        return NF.numberNode(Math.round(toNumber(arg)));
    }

    public static JsonNode fn_abs(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.numberNode(Math.abs(toNumber(arg)));
    }

    public static JsonNode fn_sqrt(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        double v = toNumber(arg);
        if (v < 0) throw new JsonataEvaluationException("$sqrt: argument must be non-negative");
        return NF.numberNode(Math.sqrt(v));
    }

    public static JsonNode fn_power(JsonNode base, JsonNode exp) throws JsonataEvaluationException {
        if (missing(base) || missing(exp)) return MISSING;
        return NF.numberNode(Math.pow(toNumber(base), toNumber(exp)));
    }

    // =========================================================================
    // Built-in functions — string
    // =========================================================================

    public static JsonNode fn_uppercase(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.textNode(toText(arg).toUpperCase());
    }

    public static JsonNode fn_lowercase(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.textNode(toText(arg).toLowerCase());
    }

    public static JsonNode fn_trim(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.textNode(toText(arg).trim());
    }

    public static JsonNode fn_length(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        return NF.numberNode(toText(arg).length());
    }

    public static JsonNode fn_substring(JsonNode str, JsonNode start)
            throws JsonataEvaluationException {
        if (missing(str)) return MISSING;
        String s = toText(str);
        int begin = clampIndex((int) toNumber(start), s.length());
        return NF.textNode(s.substring(begin));
    }

    public static JsonNode fn_substring(JsonNode str, JsonNode start, JsonNode length)
            throws JsonataEvaluationException {
        if (missing(str)) return MISSING;
        String s = toText(str);
        int len  = s.length();
        int begin = clampIndex((int) toNumber(start), len);
        int end   = Math.min(begin + (int) toNumber(length), len);
        return NF.textNode(begin < end ? s.substring(begin, end) : "");
    }

    public static JsonNode fn_substringBefore(JsonNode str, JsonNode chars)
            throws JsonataEvaluationException {
        if (missing(str) || missing(chars)) return MISSING;
        String s = toText(str);
        String c = toText(chars);
        int idx = s.indexOf(c);
        return NF.textNode(idx < 0 ? s : s.substring(0, idx));
    }

    public static JsonNode fn_substringAfter(JsonNode str, JsonNode chars)
            throws JsonataEvaluationException {
        if (missing(str) || missing(chars)) return MISSING;
        String s = toText(str);
        String c = toText(chars);
        int idx = s.indexOf(c);
        return NF.textNode(idx < 0 ? "" : s.substring(idx + c.length()));
    }

    public static JsonNode fn_contains(JsonNode str, JsonNode search)
            throws JsonataEvaluationException {
        if (missing(str) || missing(search)) return MISSING;
        return bool(toText(str).contains(toText(search)));
    }

    public static JsonNode fn_split(JsonNode str, JsonNode separator)
            throws JsonataEvaluationException {
        if (missing(str) || missing(separator)) return MISSING;
        String[] parts = toText(str).split(toText(separator), -1);
        ArrayNode result = NF.arrayNode();
        for (String p : parts) result.add(p);
        return result;
    }

    public static JsonNode fn_join(JsonNode arr, JsonNode separator)
            throws JsonataEvaluationException {
        if (missing(arr)) return MISSING;
        String sep = missing(separator) ? "" : toText(separator);
        if (!arr.isArray()) return fn_string(arr);
        StringJoiner sj = new StringJoiner(sep);
        for (JsonNode elem : arr) sj.add(toText(elem));
        return NF.textNode(sj.toString());
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
        if (!arg.isArray()) return NF.numberNode(toNumber(arg));
        double sum = 0;
        for (JsonNode elem : arg) sum += toNumber(elem);
        return NF.numberNode(sum);
    }

    public static JsonNode fn_max(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) return NF.numberNode(toNumber(arg));
        if (arg.size() == 0) return MISSING;
        double max = Double.NEGATIVE_INFINITY;
        for (JsonNode elem : arg) { double v = toNumber(elem); if (v > max) max = v; }
        return NF.numberNode(max);
    }

    public static JsonNode fn_min(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) return NF.numberNode(toNumber(arg));
        if (arg.size() == 0) return MISSING;
        double min = Double.POSITIVE_INFINITY;
        for (JsonNode elem : arg) { double v = toNumber(elem); if (v < min) min = v; }
        return NF.numberNode(min);
    }

    public static JsonNode fn_average(JsonNode arg) throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) return NF.numberNode(toNumber(arg));
        if (arg.size() == 0) return MISSING;
        double sum = 0;
        for (JsonNode elem : arg) sum += toNumber(elem);
        return NF.numberNode(sum / arg.size());
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

    public static JsonNode fn_sort(JsonNode arg) throws JsonataEvaluationException {
        return fn_sort(arg, null);
    }

    public static JsonNode fn_sort(JsonNode arg, JsonataLambda keyFn)
            throws JsonataEvaluationException {
        if (missing(arg)) return MISSING;
        if (!arg.isArray()) return arg;
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode e : arg) list.add(e);
        Comparator<JsonNode> cmp = (a, b) -> {
            try {
                JsonNode ka = keyFn != null ? keyFn.apply(a) : a;
                JsonNode kb = keyFn != null ? keyFn.apply(b) : b;
                if (ka.isNumber() && kb.isNumber())
                    return Double.compare(ka.doubleValue(), kb.doubleValue());
                if (ka.isTextual() && kb.isTextual())
                    return ka.textValue().compareTo(kb.textValue());
                return 0;
            } catch (JsonataEvaluationException ex) {
                throw new RuntimeException(ex);
            }
        };
        try { list.sort(cmp); }
        catch (RuntimeException e) {
            if (e.getCause() instanceof JsonataEvaluationException jee) throw jee;
            throw e;
        }
        ArrayNode result = NF.arrayNode();
        list.forEach(result::add);
        return result;
    }

    public static JsonNode fn_map(JsonNode arr, JsonataLambda fn)
            throws JsonataEvaluationException {
        if (missing(arr)) return MISSING;
        ArrayNode result = NF.arrayNode();
        if (arr.isArray()) {
            for (JsonNode elem : arr) result.add(fn.apply(elem));
        } else {
            result.add(fn.apply(arr));
        }
        return result;
    }

    public static JsonNode fn_filter(JsonNode arr, JsonataLambda predicate)
            throws JsonataEvaluationException {
        return filter(arr, predicate);
    }

    public static JsonNode fn_reduce(JsonNode arr, JsonataLambda fn, JsonNode init)
            throws JsonataEvaluationException {
        if (missing(arr)) return init;
        // fn takes accumulated value via a pair array [acc, elem]
        JsonNode acc = init;
        Iterable<JsonNode> items = arr.isArray() ? arr : List.of(arr);
        for (JsonNode elem : items) {
            acc = fn.apply(NF.arrayNode().add(acc).add(elem));
        }
        return acc;
    }

    // =========================================================================
    // Built-in functions — object
    // =========================================================================

    public static JsonNode fn_keys(JsonNode obj) {
        if (missing(obj) || !obj.isObject()) return MISSING;
        ArrayNode result = NF.arrayNode();
        obj.fieldNames().forEachRemaining(result::add);
        return result;
    }

    public static JsonNode fn_values(JsonNode obj) {
        if (missing(obj) || !obj.isObject()) return MISSING;
        ArrayNode result = NF.arrayNode();
        obj.fields().forEachRemaining(e -> result.add(e.getValue()));
        return result;
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

    public static JsonNode fn_each(JsonNode obj, JsonataLambda fn)
            throws JsonataEvaluationException {
        if (missing(obj) || !obj.isObject()) return MISSING;
        ArrayNode result = NF.arrayNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            // fn receives [key, value] pair
            JsonNode pair = NF.arrayNode().add(e.getKey()).add(e.getValue());
            JsonNode r = fn.apply(pair);
            if (!missing(r)) result.add(r);
        }
        return unwrap(result);
    }

    // =========================================================================
    // Built-in functions — date/time
    // =========================================================================

    public static JsonNode fn_now() {
        return NF.textNode(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
    }

    public static JsonNode fn_millis() {
        return NF.numberNode(System.currentTimeMillis());
    }

    // =========================================================================
    // Error
    // =========================================================================

    public static JsonNode fn_error(JsonNode msg) throws JsonataEvaluationException {
        String m = missing(msg) ? "Error thrown from expression" : toText(msg);
        throw new JsonataEvaluationException(m);
    }

    // =========================================================================
    // Chain operator helper
    // =========================================================================

    /**
     * Applies {@code fn} to {@code arg} — used by the {@code ~>} chain operator.
     * {@code fn} must be a lambda token produced by {@link #lambdaNode}.
     */
    public static JsonNode fn_apply(JsonNode fn, JsonNode arg)
            throws JsonataEvaluationException {
        if (isLambdaToken(fn)) {
            return lookupLambda(fn).apply(arg);
        }
        throw new JsonataEvaluationException(
                "Right-hand side of ~> is not a function; got: " + fn);
    }

    /**
     * Registers {@code fn} in the lambda registry and returns a sentinel
     * {@link TextNode} that can be stored as a {@link JsonNode} value and
     * later resolved by {@link #fn_apply}.
     */
    public static JsonNode lambdaNode(JsonataLambda fn) {
        String key = String.valueOf(LAMBDA_COUNTER.incrementAndGet());
        LAMBDA_REGISTRY.put(key, fn);
        return NF.textNode(LAMBDA_PREFIX + key);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private static boolean missing(JsonNode n) {
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
    private static String toText(JsonNode n) throws JsonataEvaluationException {
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

    /** Handles negative indices (count from end). */
    private static int clampIndex(int i, int len) {
        if (i < 0) i = Math.max(0, len + i);
        return Math.min(i, len);
    }

    /**
     * Adds {@code val} to {@code acc}, flattening one level of arrays
     * (JSONata sequence flattening rule).
     */
    private static void appendToSequence(ArrayNode acc, JsonNode val) {
        if (val.isArray()) val.forEach(acc::add);
        else if (!val.isMissingNode()) acc.add(val);
    }

    /**
     * Returns the single element if the array has exactly one item, otherwise
     * returns the array as-is. Empty arrays return {@link #MISSING}.
     */
    private static JsonNode unwrap(ArrayNode arr) {
        return switch (arr.size()) {
            case 0 -> MISSING;
            case 1 -> arr.get(0);
            default -> arr;
        };
    }

    // =========================================================================
    // Internal — lambda registry for the ~> chain operator
    // =========================================================================

    /**
     * Maps lambda token keys to their {@link JsonataLambda} implementations.
     * Lambdas are represented as plain {@link TextNode}s with the sentinel prefix
     * {@code "__\u03bb:"} followed by the registry key, so they flow through
     * the Jackson type system without requiring a custom {@code JsonNode} subclass.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, JsonataLambda>
            LAMBDA_REGISTRY = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String LAMBDA_PREFIX = "__\u03bb:";
    private static final java.util.concurrent.atomic.AtomicLong LAMBDA_COUNTER =
            new java.util.concurrent.atomic.AtomicLong();

    private static boolean isLambdaToken(JsonNode n) {
        return n != null && n.isTextual() && n.textValue().startsWith(LAMBDA_PREFIX);
    }

    private static JsonataLambda lookupLambda(JsonNode n) throws JsonataEvaluationException {
        String key = n.textValue().substring(LAMBDA_PREFIX.length());
        JsonataLambda fn = LAMBDA_REGISTRY.get(key);
        if (fn == null) throw new JsonataEvaluationException("Lambda expired or not found: " + key);
        return fn;
    }
}
