package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ValueNode;

import java.io.IOException;

/**
 * A JSONata function value, carried directly as a {@link JsonNode}.
 *
 * <p>Function values have to flow through the same plumbing as data — stored in variables, put in
 * arrays and objects, passed to and returned from other functions. Earlier versions encoded them as
 * a {@code TextNode} with a {@code "__λ:"} prefix and resolved that key against a registry, which
 * meant a <em>string in the input document</em> that happened to start with the prefix was treated
 * as a function: {@code $type} answered {@code "function"}, {@code $string} answered {@code ""}, and
 * {@code ~>} tried to compose it. It also gave function values a lifetime — the registry entry —
 * that had to be managed separately from the value itself.
 *
 * <p>Holding the {@link JsonataLambda} in a node of its own removes both problems: no user data can
 * be mistaken for a function, and a function value stays callable exactly as long as something
 * references it.
 *
 * <p>Instances are immutable and safe to share across threads, provided the captured closure is
 * (every closure the translator emits is).
 */
public final class LambdaNode extends ValueNode {

    /** Reported by {@link #arity()} when the parameter count is unknown. */
    public static final int UNKNOWN_ARITY = -1;

    private final JsonataLambda fn;
    private final int arity;

    LambdaNode(JsonataLambda fn, int arity) {
        this.fn = fn;
        this.arity = arity;
    }

    /** The callable behind this function value. */
    public JsonataLambda function() {
        return fn;
    }

    /**
     * The number of parameters the function declares, or {@link #UNKNOWN_ARITY}.
     *
     * <p>Built-in higher-order functions use this to decide how much to pass a callback that is not
     * a literal lambda at the call site — {@code [value, index, array]} for a multi-parameter
     * callback, the element alone otherwise — and {@code $sort} uses it to tell a comparator from a
     * key function.
     */
    public int arity() {
        return arity;
    }

    // -------------------------------------------------------------------------
    // JsonNode contract
    // -------------------------------------------------------------------------

    @Override
    public JsonNodeType getNodeType() {
        return JsonNodeType.POJO;
    }

    @Override
    public JsonToken asToken() {
        return JsonToken.VALUE_EMBEDDED_OBJECT;
    }

    /** JSONata renders a function as the empty string; see {@code $string}. */
    @Override
    public String asText() {
        return "";
    }

    @Override
    public void serialize(JsonGenerator gen, SerializerProvider provider) throws IOException {
        // A function has no JSON form. Emitting the empty string keeps any document that happens to
        // carry one serialisable, and matches what $string() produces.
        gen.writeString("");
    }

    @Override
    public void serializeWithType(JsonGenerator gen, SerializerProvider provider,
                                  TypeSerializer typeSer) throws IOException {
        serialize(gen, provider);
    }

    /** Function values are compared by identity: two closures are the same only if they are one. */
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "\"\"";
    }
}
