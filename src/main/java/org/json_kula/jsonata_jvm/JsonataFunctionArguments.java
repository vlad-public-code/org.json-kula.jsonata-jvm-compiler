package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.List;

/**
 * Represents the argument list passed to a {@link JsonataBoundFunction}.
 *
 * <p>Accessing an index beyond the actual argument count returns
 * {@link MissingNode#getInstance()} rather than throwing, consistent with
 * JSONata's "optional argument" semantics.
 */
public final class JsonataFunctionArguments {

    private final List<JsonNode> args;

    public JsonataFunctionArguments(List<JsonNode> args) {
        this.args = List.copyOf(args);
    }

    /**
     * Returns the argument at {@code index}, or {@code MissingNode} if the
     * index is out of range.
     */
    public JsonNode get(int index) {
        if (index < 0 || index >= args.size()) return MissingNode.getInstance();
        return args.get(index);
    }

    /** Returns the number of arguments supplied by the caller. */
    public int size() {
        return args.size();
    }

    /** Returns an immutable view of the argument list. */
    public List<JsonNode> asList() {
        return args;
    }
}
