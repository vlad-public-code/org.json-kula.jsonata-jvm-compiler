package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Higher-order sequence built-in functions for JSONata.
 *
 * <p>All methods are package-private static helpers delegated from
 * {@link JsonataRuntime}.
 */
final class SequenceBuiltins {

    private SequenceBuiltins() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    static JsonNode fn_sort(JsonNode arg) throws RuntimeEvaluationException {
        return fn_sort(arg, null);
    }

    static JsonNode fn_sort(JsonNode arg, JsonataLambda keyFn) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isArray()) {
            // Scalar: wrap in a 1-element array
            ArrayNode single = NF.arrayNode();
            single.add(arg);
            return single;
        }
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode e : arg) list.add(e);
        if (list.isEmpty()) return arg;
        // When no key function, check that elements are sortable primitives
        if (keyFn == null) {
            for (JsonNode elem : list) {
                if (elem.isObject() || elem.isArray()) {
                    throw new RuntimeEvaluationException("D3070", "$sort() cannot sort arrays of objects without a comparator function");
                }
            }
        }

        // Pre-compute all key values and validate types
        JsonNode[] keys = new JsonNode[list.size()];
        boolean hasNumber = false, hasString = false;
        for (int i = 0; i < list.size(); i++) {
            JsonNode k = keyFn != null ? keyFn.apply(list.get(i)) : list.get(i);
            if (k == null || k.isMissingNode()) {
                // No keyFn result / undefined: skip without error
                keys[i] = JsonataRuntime.MISSING;
                continue;
            }
            if (k.isNull()) {
                // null is not a valid sort key
                throw new RuntimeEvaluationException("T2008", "The key expression in the order-by clause must evaluate to a string or a number");
            }
            if (k.isNumber()) {
                hasNumber = true;
            } else if (k.isTextual()) {
                hasString = true;
            } else {
                // boolean, object, array, etc.
                throw new RuntimeEvaluationException("T2008", "The key expression in the order-by clause must evaluate to a string or a number");
            }
            keys[i] = k;
        }
        if (hasNumber && hasString) {
            throw new RuntimeEvaluationException("T2007", "The items in the order-by clause must evaluate to a single type, either all string or all number");
        }

        final boolean allNumbers = hasNumber;
        Comparator<Integer> cmp = (ia, ib) -> {
            JsonNode ka = keys[ia];
            JsonNode kb = keys[ib];
            if (ka.isMissingNode() || ka.isNull()) return kb.isMissingNode() || kb.isNull() ? 0 : 1;
            if (kb.isMissingNode() || kb.isNull()) return -1;
            if (allNumbers)
                return Double.compare(ka.doubleValue(), kb.doubleValue());
            return ka.textValue().compareTo(kb.textValue());
        };
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) indices.add(i);
        indices.sort(cmp);
        ArrayNode result = NF.arrayNode();
        for (int idx : indices) result.add(list.get(idx));
        return result;
    }

    /**
     * Sort using a 2-param comparator lambda: function($a, $b){...} returns true
     * when $a should come before $b.
     */
    static JsonNode fn_sort_comparator(JsonNode arg, JsonataLambda comparatorFn)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isArray()) return arg;
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode e : arg) list.add(e);
        Comparator<JsonNode> cmp = (a, b) -> {
            // comparatorFn(a, b) returns true if a should come AFTER b (ascending semantics)
            JsonNode resultAB = comparatorFn.apply(NF.arrayNode().add(a).add(b));
            if (JsonataRuntime.isTruthy(resultAB)) return 1;  // a > b
            // Check if b > a; if neither, they are equal → return 0 for stable ordering
            JsonNode resultBA = comparatorFn.apply(NF.arrayNode().add(b).add(a));
            return JsonataRuntime.isTruthy(resultBA) ? -1 : 0;
        };
        list.sort(cmp);
        ArrayNode result = NF.arrayNode();
        list.forEach(result::add);
        return result;
    }

    /**
     * Produces {@code [element, parent]} pairs without flattening.
     * For each element in {@code source}, applies {@code elemFn} to get child
     * elements; each child is paired with its parent source element.
     */
    static JsonNode fn_collect_pairs(JsonNode source, JsonataLambda elemFn)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(source)) return JsonataRuntime.MISSING;
        List<JsonNode> parents = new ArrayList<>();
        if (source.isArray()) { for (JsonNode e : source) parents.add(e); }
        else parents.add(source);
        ArrayNode pairs = NF.arrayNode();
        for (JsonNode parent : parents) {
            JsonNode elem = elemFn.apply(parent);
            if (JsonataRuntime.missing(elem)) continue;
            if (elem.isArray()) {
                for (JsonNode e : elem) {
                    if (!JsonataRuntime.missing(e)) pairs.add(NF.arrayNode().add(e).add(parent));
                }
            } else {
                pairs.add(NF.arrayNode().add(elem).add(parent));
            }
        }
        return pairs.isEmpty() ? JsonataRuntime.MISSING : pairs;
    }

    /**
     * Produces {@code [element, parent, grandparent]} triples without flattening.
     * For each grandparent in {@code grandparents}, applies {@code parentFn} to get
     * parents, then {@code elemFn} to get elements; each element is triple-packed with
     * its parent and grandparent.
     */
    static JsonNode fn_collect_triples(JsonNode grandparents, JsonataLambda parentFn, JsonataLambda elemFn)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(grandparents)) return JsonataRuntime.MISSING;
        List<JsonNode> gps = new ArrayList<>();
        if (grandparents.isArray()) { for (JsonNode e : grandparents) gps.add(e); }
        else gps.add(grandparents);
        ArrayNode triples = NF.arrayNode();
        for (JsonNode gp : gps) {
            JsonNode parents = parentFn.apply(gp);
            if (JsonataRuntime.missing(parents)) continue;
            List<JsonNode> plist = new ArrayList<>();
            if (parents.isArray()) { for (JsonNode p : parents) plist.add(p); }
            else plist.add(parents);
            for (JsonNode parent : plist) {
                JsonNode elem = elemFn.apply(parent);
                if (JsonataRuntime.missing(elem)) continue;
                if (elem.isArray()) {
                    for (JsonNode e : elem) {
                        if (!JsonataRuntime.missing(e)) triples.add(NF.arrayNode().add(e).add(parent).add(gp));
                    }
                } else {
                    triples.add(NF.arrayNode().add(elem).add(parent).add(gp));
                }
            }
        }
        return triples.isEmpty() ? JsonataRuntime.MISSING : triples;
    }

    static JsonNode fn_shuffle(JsonNode arg) {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isArray()) return arg;
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode e : arg) list.add(e);
        Collections.shuffle(list);
        ArrayNode result = NF.arrayNode();
        list.forEach(result::add);
        return result;
    }

    static JsonNode fn_map(JsonNode arr, JsonataLambda fn) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        ArrayNode result = NF.arrayNode();
        if (arr.isArray()) {
            for (JsonNode elem : arr) result.add(fn.apply(elem));
        } else {
            result.add(fn.apply(arr));
        }
        return JsonataRuntime.unwrap(result);
    }

    static JsonNode fn_filter(JsonNode arr, JsonataLambda predicate)
            throws RuntimeEvaluationException {
        return JsonataRuntime.filter(arr, predicate);
    }

    static JsonNode fn_reduce(JsonNode arr, JsonataLambda fn, JsonNode init)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arr)) return init;
        // fn receives a pair array [acc, elem]; the translator unpacks this for
        // multi-param lambdas via genUnpackLambda.
        List<JsonNode> items = new ArrayList<>();
        if (arr.isArray()) arr.forEach(items::add); else items.add(arr);
        // When no initial value is given (MISSING), use the first element as the
        // accumulator and start folding from the second element — matching JSONata
        // semantics for $reduce without initialValue.
        int start;
        JsonNode acc;
        if (JsonataRuntime.missing(init)) {
            if (items.isEmpty()) return JsonataRuntime.MISSING;
            acc   = items.get(0);
            start = 1;
        } else {
            acc   = init;
            start = 0;
        }
        // Build the full array node once for passing as the 4th argument
        ArrayNode arrNode = NF.arrayNode();
        items.forEach(arrNode::add);
        for (int i = start; i < items.size(); i++) {
            acc = fn.apply(NF.arrayNode().add(acc).add(items.get(i)).add(NF.numberNode(i)).add(arrNode));
        }
        return acc;
    }

    /**
     * Variant of {@link #fn_map} for multi-param lambdas.
     * Passes {@code [value, index, array]} to the lambda so that the
     * {@code $i} and {@code $a} parameters are available.
     */
    static JsonNode fn_map_indexed(JsonNode arr, JsonataLambda fn) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        List<JsonNode> items = new ArrayList<>();
        if (arr.isArray()) arr.forEach(items::add); else items.add(arr);
        ArrayNode result = NF.arrayNode();
        for (int i = 0; i < items.size(); i++) {
            JsonNode val = fn.apply(NF.arrayNode().add(items.get(i)).add(NF.numberNode(i)).add(arr));
            if (!JsonataRuntime.missing(val)) result.add(val);
        }
        return result;
    }

    /**
     * Variant of {@link #fn_filter} for multi-param lambdas.
     * Passes {@code [value, index, array]} to the predicate.
     */
    static JsonNode fn_filter_indexed(JsonNode arr, JsonataLambda predicate)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        List<JsonNode> items = new ArrayList<>();
        if (arr.isArray()) arr.forEach(items::add); else items.add(arr);
        ArrayNode result = NF.arrayNode();
        for (int i = 0; i < items.size(); i++) {
            if (JsonataRuntime.isTruthy(predicate.apply(
                    NF.arrayNode().add(items.get(i)).add(NF.numberNode(i)).add(arr))))
                result.add(items.get(i));
        }
        return JsonataRuntime.unwrap(result);
    }

    /**
     * Returns the single element of {@code arr} for which {@code predicate}
     * returns truthy. Throws if zero or more than one element matches.
     */
    static JsonNode fn_single(JsonNode arr, JsonataLambda predicate)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        List<JsonNode> items = new ArrayList<>();
        if (arr.isArray()) arr.forEach(items::add); else items.add(arr);
        JsonNode found = null;
        for (JsonNode item : items) {
            if (JsonataRuntime.isTruthy(predicate.apply(item))) {
                if (found != null)
                    throw new RuntimeEvaluationException("D3138", "$single: more than one match found");
                found = item;
            }
        }
        if (found == null)
            throw new RuntimeEvaluationException("D3139", "$single: no match found");
        return found;
    }

    /** 1-arg $single: returns element only if exactly one exists. */
    static JsonNode fn_single(JsonNode arr) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        List<JsonNode> items = new ArrayList<>();
        if (arr.isArray()) arr.forEach(items::add); else items.add(arr);
        if (items.isEmpty())
            throw new RuntimeEvaluationException("D3139", "$single: no match found");
        if (items.size() > 1)
            throw new RuntimeEvaluationException("D3138", "$single: more than one match found");
        return items.get(0);
    }

    /** Multi-param $single: passes [value, index, array] to the predicate. */
    static JsonNode fn_single_indexed(JsonNode arr, JsonataLambda predicate)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        List<JsonNode> items = new ArrayList<>();
        if (arr.isArray()) arr.forEach(items::add); else items.add(arr);
        com.fasterxml.jackson.databind.node.ArrayNode arrNode = NF.arrayNode();
        items.forEach(arrNode::add);
        JsonNode found = null;
        for (int i = 0; i < items.size(); i++) {
            com.fasterxml.jackson.databind.node.ArrayNode tuple = NF.arrayNode()
                    .add(items.get(i)).add(NF.numberNode(i)).add(arrNode);
            if (JsonataRuntime.isTruthy(predicate.apply(tuple))) {
                if (found != null)
                    throw new RuntimeEvaluationException("D3138", "$single: more than one match found");
                found = items.get(i);
            }
        }
        if (found == null)
            throw new RuntimeEvaluationException("D3139", "$single: no match found");
        return found;
    }

    /**
     * Returns an object containing only the key/value pairs of {@code obj}
     * for which {@code fn} returns truthy.
     * Passes {@code [value, key, object]} to the lambda so both
     * {@code $v} and {@code $k} parameters are available.
     */
    static JsonNode fn_sift(JsonNode obj, JsonataLambda fn) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(obj) || !obj.isObject()) return JsonataRuntime.MISSING;
        ObjectNode result = NF.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode triple = NF.arrayNode().add(e.getValue()).add(NF.textNode(e.getKey())).add(obj);
            if (JsonataRuntime.isTruthy(fn.apply(triple))) result.set(e.getKey(), e.getValue());
        }
        return result.isEmpty() ? JsonataRuntime.MISSING : result;
    }
}
