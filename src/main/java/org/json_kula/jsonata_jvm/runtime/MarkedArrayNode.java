package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * An array carrying one of the two marks JSONata puts on a value rather than on an expression.
 *
 * <p><b>cons</b> — the array a {@code [...]} written as a path step built. It is a <em>value</em>
 * that happens to be an array, so it neither flattens into the sequence around it nor collapses
 * when it is the only thing in one: {@code nums.[1,2]} is {@code [[1,2],[1,2],[1,2]]}, not six
 * numbers, and {@code a.[1]} is {@code [1]}, not {@code 1}.
 *
 * <p><b>keepSingleton</b> — what {@code []} leaves behind. It does not stop flattening, only
 * collapsing, and it outlives further steps and a sort: {@code a.b[]^($)} is {@code [1]}.
 *
 * <p>The reference checks them in three places — the flatten loop in {@code evaluateStep}, the
 * last-step passthrough, and the promotion {@code []} performs on a cons array — and it has to be
 * the <em>value</em> that carries them. The parser flags every bare {@code [...]} path step,
 * wherever it sits, and the mark then travels with the array through later steps, a {@code []} and
 * a sort: {@code a.[1].$[]} is {@code [[1]]}. Rules keyed on the AST shape are each an
 * approximation of that dynamic fact and there is always another shape — the sibling ports measured
 * a static implementation stalling at 285 divergences on a path sweep where the marker reached 0.
 *
 * <p>A subclass of {@code ArrayNode} is the cheap representation: it serialises, compares and
 * navigates as the array it is, so nothing outside those checks needs to know. {@code equals} stays
 * Jackson's, comparing contents — a marked array and a plain one holding the same elements are
 * equal, which is what a caller comparing JSON should see.
 */
final class MarkedArrayNode extends ArrayNode {

    private final boolean cons;

    private MarkedArrayNode(JsonNodeFactory nf, int capacity, boolean cons) {
        super(nf, capacity);
        this.cons = cons;
    }

    /** An empty array to fill as a constructor's own value. */
    static MarkedArrayNode consArray(JsonNodeFactory nf, int capacity) {
        return new MarkedArrayNode(nf, Math.max(1, capacity), true);
    }

    /** A copy of {@code source} that will not collapse — what {@code []} leaves behind. */
    static MarkedArrayNode keepSingleton(JsonNodeFactory nf, JsonNode source) {
        MarkedArrayNode kept = new MarkedArrayNode(nf, Math.max(1, source.size()), false);
        for (JsonNode e : source) kept.add(e);
        return kept;
    }

    /** A copy of {@code source} carrying whatever mark {@code like} had, if any. */
    static JsonNode sameMark(JsonNodeFactory nf, JsonNode like, ArrayNode source) {
        if (!(like instanceof MarkedArrayNode m)) return source;
        MarkedArrayNode copy = new MarkedArrayNode(nf, Math.max(1, source.size()), m.cons);
        for (JsonNode e : source) copy.add(e);
        return copy;
    }

    /** Whether {@code node} is a constructor's own array, and so does not flatten into a sequence. */
    static boolean isCons(JsonNode node) {
        return node instanceof MarkedArrayNode m && m.cons;
    }

    /** Whether {@code node} carries either mark, both of which stop the singleton collapse. */
    static boolean noCollapse(JsonNode node) {
        return node instanceof MarkedArrayNode;
    }
}
