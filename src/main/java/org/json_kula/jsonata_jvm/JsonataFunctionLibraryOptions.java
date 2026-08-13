package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional settings for {@link JsonataExpressionFactory#compileFunctionLibrary}.
 *
 * <p>All settings have sensible defaults; a definition expression that only binds lambdas needs
 * none of them.
 *
 * <pre>{@code
 * JsonataFunctionLibrary lib = factory.compileFunctionLibrary(
 *         List.of("$netOf"), definition,
 *         new JsonataFunctionLibraryOptions()
 *                 .bindings(new JsonataBindings().bindValue("vatRate", rate))
 *                 .signature("netOf", "<n:n>"));
 * }</pre>
 */
public final class JsonataFunctionLibraryOptions {

    private JsonNode input = NullNode.instance;
    private JsonataBindings bindings;
    private final Map<String, String> signatures = new LinkedHashMap<>();

    /**
     * Sets the document the definition expression is evaluated against. Defaults to JSON
     * {@code null}; only needed when the definition reads from the input to build its functions.
     */
    public JsonataFunctionLibraryOptions input(JsonNode input) {
        this.input = input != null ? input : NullNode.instance;
        return this;
    }

    /**
     * Sets bindings visible while the definition runs. They are also installed when an exported
     * function is called directly from Java, outside any evaluation — inside an expression the
     * caller's own bindings apply instead.
     */
    public JsonataFunctionLibraryOptions bindings(JsonataBindings bindings) {
        this.bindings = bindings;
        return this;
    }

    /**
     * Overrides the reported signature of one exported function, tightening argument validation and
     * coercion at the Java boundary (e.g. {@code "<n:n>"} to coerce a numeric string to a number).
     * The leading {@code $} on the name is optional.
     *
     * <p>Without an override, a function declaring its own signature in JSONata
     * ({@code function($x)<n:n>{…}}) reports that signature, and any other function reports an
     * all-optional {@code <j?…:j>} — the same permissiveness a JSONata call site has.
     */
    public JsonataFunctionLibraryOptions signature(String functionName, String signature) {
        signatures.put(FunctionExportRewriter.normalize(functionName), signature);
        return this;
    }

    JsonNode getInput() {
        return input;
    }

    JsonataBindings getBindings() {
        return bindings;
    }

    String getSignatureOverride(String normalizedName) {
        return signatures.get(normalizedName);
    }
}
