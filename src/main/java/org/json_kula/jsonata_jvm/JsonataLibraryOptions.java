package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional settings for {@link JsonataExpressionFactory#compileLibrary(String, JsonataLibraryOptions)}.
 *
 * <p>All settings have sensible defaults; a definition expression that only binds lambdas needs
 * none of them.
 *
 * <pre>{@code
 * JsonataLibrary lib = factory.compileLibrary(definition,
 *         new JsonataLibraryOptions()
 *                 .bindings(new JsonataBindings().bindValue("vatRate", rate))
 *                 .signature("netOf", "<n:n>"));
 * }</pre>
 */
public final class JsonataLibraryOptions {

    private JsonNode input = NullNode.instance;
    private JsonataBindings bindings;
    private final Map<String, String> signatures = new LinkedHashMap<>();

    /**
     * Sets the document the definition expression is evaluated against. Defaults to JSON
     * {@code null}; only needed when the definition reads from the input to build its functions.
     */
    public JsonataLibraryOptions input(JsonNode input) {
        this.input = input != null ? input : NullNode.instance;
        return this;
    }

    /**
     * Sets bindings the definition may rely on: the way to parameterise a library.
     *
     * <p>A definition must be self-contained, so a name it neither binds nor gets from the JSONata
     * standard library has to be supplied here — otherwise compiling the library fails. These names
     * are in scope while the definition runs, are captured by the functions it exports, and are
     * installed again when an exported function is called directly from Java, outside any
     * evaluation.
     */
    public JsonataLibraryOptions bindings(JsonataBindings bindings) {
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
    public JsonataLibraryOptions signature(String functionName, String signature) {
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
