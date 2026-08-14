package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ValueNode;

import java.io.IOException;

/**
 * A compiled JSONata regex literal ({@code /pattern/flags}), carried directly as a
 * {@link com.fasterxml.jackson.databind.JsonNode}.
 *
 * <p>Like {@link LambdaNode}, this replaces an earlier encoding as a prefixed {@code TextNode},
 * under which a string in the input document beginning with {@code "__rx:"} was mistaken for a
 * regex. The compiled {@link org.joni.Regex} is cached by pattern and flags (see
 * {@link RegexRegistry}); this node is the handle to it.
 */
public final class RegexNode extends ValueNode {

    private final org.joni.Regex regex;
    private final String pattern;
    private final String flags;

    RegexNode(org.joni.Regex regex, String pattern, String flags) {
        this.regex = regex;
        this.pattern = pattern;
        this.flags = flags;
    }

    /** The compiled matcher factory. */
    public org.joni.Regex regex() {
        return regex;
    }

    /** The literal's pattern text, without delimiters. */
    public String pattern() {
        return pattern;
    }

    /** The literal's flags, e.g. {@code "i"}, {@code "m"}, or {@code ""}. */
    public String flags() {
        return flags;
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

    /** A regex is a function value in JSONata, and renders as the empty string. */
    @Override
    public String asText() {
        return "";
    }

    @Override
    public void serialize(JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeString("");
    }

    @Override
    public void serializeWithType(JsonGenerator gen, SerializerProvider provider,
                                  TypeSerializer typeSer) throws IOException {
        serialize(gen, provider);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RegexNode other
                && pattern.equals(other.pattern) && flags.equals(other.flags);
    }

    @Override
    public int hashCode() {
        return pattern.hashCode() * 31 + flags.hashCode();
    }

    @Override
    public String toString() {
        return "\"\"";
    }
}
