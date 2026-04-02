package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;

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

    static JsonNode fn_sort(JsonNode arg) throws JsonataEvaluationException {
        return fn_sort(arg, null);
    }

    static JsonNode fn_sort(JsonNode arg, JsonataLambda keyFn) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
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

    static JsonNode fn_map(JsonNode arr, JsonataLambda fn) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        ArrayNode result = NF.arrayNode();
        if (arr.isArray()) {
            for (JsonNode elem : arr) result.add(fn.apply(elem));
        } else {
            result.add(fn.apply(arr));
        }
        return result;
    }

    static JsonNode fn_filter(JsonNode arr, JsonataLambda predicate)
            throws JsonataEvaluationException {
        return JsonataRuntime.filter(arr, predicate);
    }

    static JsonNode fn_reduce(JsonNode arr, JsonataLambda fn, JsonNode init)
            throws JsonataEvaluationException {
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
        for (int i = start; i < items.size(); i++) {
            acc = fn.apply(NF.arrayNode().add(acc).add(items.get(i)));
        }
        return acc;
    }

    /**
     * Variant of {@link #fn_map} for multi-param lambdas.
     * Passes {@code [value, index, array]} to the lambda so that the
     * {@code $i} and {@code $a} parameters are available.
     */
    static JsonNode fn_map_indexed(JsonNode arr, JsonataLambda fn) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        List<JsonNode> items = new ArrayList<>();
        if (arr.isArray()) arr.forEach(items::add); else items.add(arr);
        ArrayNode result = NF.arrayNode();
        for (int i = 0; i < items.size(); i++) {
            result.add(fn.apply(NF.arrayNode().add(items.get(i)).add(NF.numberNode(i)).add(arr)));
        }
        return result;
    }

    /**
     * Variant of {@link #fn_filter} for multi-param lambdas.
     * Passes {@code [value, index, array]} to the predicate.
     */
    static JsonNode fn_filter_indexed(JsonNode arr, JsonataLambda predicate)
            throws JsonataEvaluationException {
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
            throws JsonataEvaluationException {
        if (JsonataRuntime.missing(arr))
            throw new JsonataEvaluationException("$single: no match found");
        List<JsonNode> items = new ArrayList<>();
        if (arr.isArray()) arr.forEach(items::add); else items.add(arr);
        JsonNode found = null;
        for (JsonNode item : items) {
            if (JsonataRuntime.isTruthy(predicate.apply(item))) {
                if (found != null)
                    throw new JsonataEvaluationException("$single: more than one match found");
                found = item;
            }
        }
        if (found == null)
            throw new JsonataEvaluationException("$single: no match found");
        return found;
    }

    /**
     * Returns an object containing only the key/value pairs of {@code obj}
     * for which {@code fn} returns truthy.
     * Passes {@code [value, key, object]} to the lambda so both
     * {@code $v} and {@code $k} parameters are available.
     */
    static JsonNode fn_sift(JsonNode obj, JsonataLambda fn) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(obj) || !obj.isObject()) return JsonataRuntime.MISSING;
        ObjectNode result = NF.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode triple = NF.arrayNode().add(e.getValue()).add(NF.textNode(e.getKey())).add(obj);
            if (JsonataRuntime.isTruthy(fn.apply(triple))) result.set(e.getKey(), e.getValue());
        }
        return result;
    }
}
