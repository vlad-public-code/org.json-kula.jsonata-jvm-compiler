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
 * and bound functions are called as {@code $name(args...)}. A bound function is also usable as a
 * value, and a bound value that is a function is callable; see {@link JsonataBoundFunction}.
 *
 * <p>To apply everything a {@link JsonataLibrary} exports in one call, use {@link #useLibrary}.
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
     * Binds every entry of {@code fns}, keyed by function name without the leading {@code $}.
     * Convenient for {@link JsonataLibrary#getFunctions()}.
     *
     * @return {@code this} for chaining
     */
    public JsonataBindings bindFunctions(Map<String, JsonataBoundFunction> fns) {
        functions.putAll(fns);
        return this;
    }

    /**
     * Binds everything {@code library} exports: its functions as function bindings, its constants as
     * value bindings.
     *
     * <p>Which of the two a name belongs to is the library's business — the definition decides it by
     * what each name evaluates to — so taking both maps together is almost always what a caller
     * wants:
     *
     * <pre>{@code
     * JsonataBindings b = new JsonataBindings()
     *         .useLibrary(billing)
     *         .bindValue("today", today);
     * }</pre>
     *
     * <p>For the lifetime of one expression instead of one evaluation,
     * {@link JsonataExpression#useLibrary} does the same thing permanently.
     *
     * <p>Applying two libraries that export the same name leaves the later call's binding in place,
     * as re-binding a name always does. A library still open when this is called can be closed
     * later; its exported functions then refuse to run, whether they were bound through here or not.
     *
     * @param library the library to apply; its exports are copied, so later changes to it — short of
     *                {@link JsonataLibrary#close()} — do not affect these bindings
     * @return {@code this} for chaining
     */
    public JsonataBindings useLibrary(JsonataLibrary library) {
        functions.putAll(library.getFunctions());
        values.putAll(library.getConstants());
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

    /** Returns {@code true} if nothing at all is bound. */
    public boolean isEmpty() {
        return values.isEmpty() && functions.isEmpty();
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
