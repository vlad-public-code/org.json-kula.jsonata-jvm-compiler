package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * The argument tuple of a multi-argument call to a function <em>value</em>.
 *
 * <p>A {@link JsonataLambda} takes exactly one node, so a call with several arguments has to
 * pack them. Packing them into a plain {@code ArrayNode} left the callee unable to tell
 * {@code $f(1, 2)} from {@code $f([1, 2])} — both arrive as a two-element array — so a function
 * whose first parameter is an array was silently mis-unpacked:
 * {@code ( $f := function($arr, $sep){ $join($arr, $sep) }; $f(["a","b"]) )} bound
 * {@code $arr = "a"} and {@code $sep = "b"} and returned {@code "a"} where the reference
 * returns {@code "ab"}.
 *
 * <p>This type is the marker that removes the ambiguity: it is created only by
 * {@link JsonataRuntime#packArgs}, and only a value of this type is spread positionally
 * ({@link JsonataRuntime#arg}). Anything else — including an ordinary array the caller passed
 * as the single argument — binds whole to the first parameter.
 *
 * <p>It is an {@code ArrayNode} so that the internal tuples the runtime builds for higher-order
 * callbacks ({@code $reduce}'s {@code [acc, elem, index, array]}, a comparator's {@code [a, b]})
 * keep working wherever they are read by index rather than unpacked.
 */
public final class PackedArgs extends ArrayNode {

    PackedArgs(JsonNodeFactory nf, int capacity) {
        super(nf, capacity);
    }

    /** Packs {@code elements} as a call tuple; {@code null} entries become MISSING. */
    static PackedArgs of(JsonNodeFactory nf, JsonNode... elements) {
        PackedArgs packed = new PackedArgs(nf, elements.length);
        for (JsonNode e : elements) packed.add(e != null ? e : JsonataRuntime.MISSING);
        return packed;
    }
}
