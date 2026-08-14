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
 * Internal marker: "this value is already a sequence element — do not flatten it".
 *
 * <p>A nested array constructor ({@code [[1,2]]}, {@code Email.[address]}) must contribute one
 * element rather than having its contents merged into the enclosing sequence. The translator wraps
 * such a value on the way out and {@link JsonataRuntime#arrayOf} unwraps it on the way in.
 *
 * <p>Earlier versions used an {@code ObjectNode} carrying a {@code "__PRESERVE__"} key, which meant
 * an input document with a field of that name was silently unwrapped. A node type of its own cannot
 * collide with anything a document can express. Instances never escape to the caller: every path
 * that can produce one unwraps it in the same step.
 */
final class PreservedNode extends ValueNode {

    private final JsonNode value;

    PreservedNode(JsonNode value) {
        this.value = value;
    }

    JsonNode value() {
        return value;
    }

    @Override
    public JsonNodeType getNodeType() {
        return JsonNodeType.POJO;
    }

    @Override
    public JsonToken asToken() {
        return JsonToken.VALUE_EMBEDDED_OBJECT;
    }

    @Override
    public String asText() {
        return value.asText();
    }

    @Override
    public void serialize(JsonGenerator gen, SerializerProvider provider) throws IOException {
        // Should never be reached — a preserved value is unwrapped before it can be returned — but
        // serialising the payload is the harmless answer if one ever leaks.
        value.serialize(gen, provider);
    }

    @Override
    public void serializeWithType(JsonGenerator gen, SerializerProvider provider,
                                  TypeSerializer typeSer) throws IOException {
        serialize(gen, provider);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PreservedNode other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
