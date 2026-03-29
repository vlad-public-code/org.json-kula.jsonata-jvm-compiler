package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A set of named values and named functions to be injected into a JSONata
 * expression at evaluation time.
 *
 * <p>Pass an instance to
 * {@link JsonataExpression#evaluate(String, JsonataBindings)} to supply
 * per-evaluation bindings, or use
 * {@link JsonataExpression#assign(String, JsonNode)} /
 * {@link JsonataExpression#registerFunction(String, JsonataBoundFunction)}
 * for bindings that persist for the lifetime of the expression instance.
 *
 * <p>Within a JSONata expression, bound values are referenced as {@code $name}
 * and bound functions are called as {@code $name(args...)}.
 *
 * <pre>{@code
 * JsonataBindings b = new JsonataBindings()
 *         .bindValue("taxRate", new ObjectMapper().convertValue(0.2, JsonNode.class))
 *         .bindFunction("round2", new Round2Function());
 *
 * JsonNode result = expr.evaluate(json, b);
 * }</pre>
 */
public final class JsonataBindings {

    private final Map<String, JsonNode> values = new LinkedHashMap<>();
    private final Map<String, JsonataBoundFunction> functions = new LinkedHashMap<>();

    /**
     * Binds {@code value} to {@code name}.
     *
     * @return {@code this} for chaining
     */
    public JsonataBindings bindValue(String name, JsonNode value) {
        values.put(name, value);
        return this;
    }

    /**
     * Binds {@code fn} to {@code name}.
     *
     * @return {@code this} for chaining
     */
    public JsonataBindings bindFunction(String name, JsonataBoundFunction fn) {
        functions.put(name, fn);
        return this;
    }

    /**
     * Returns the value bound to {@code name}, or {@code null} if not bound.
     */
    public JsonNode getValue(String name) {
        return values.get(name);
    }

    /**
     * Returns the function bound to {@code name}, or {@code null} if not bound.
     */
    public JsonataBoundFunction getFunction(String name) {
        return functions.get(name);
    }

    /** Returns an unmodifiable view of the value bindings. */
    public Map<String, JsonNode> getValues() {
        return Collections.unmodifiableMap(values);
    }

    /** Returns an unmodifiable view of the function bindings. */
    public Map<String, JsonataBoundFunction> getFunctions() {
        return Collections.unmodifiableMap(functions);
    }
}
