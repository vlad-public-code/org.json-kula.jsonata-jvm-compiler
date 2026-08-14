package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a JSONata <em>definition expression</em> exports: functions as {@link JsonataBoundFunction}s,
 * and everything else as plain {@link com.fasterxml.jackson.databind.JsonNode} constants.
 *
 * <p>A definition expression binds names and returns the ones to export. It is ordinary JSONata —
 * evaluated in any JSONata engine it simply yields that list:
 *
 * <pre>{@code
 * (
 *   $pi := 3.1415926535897932384626;
 *   $product := function($a, $b) { $a * $b };
 *   $factorial := function($n) { $n = 0 ? 1 : $reduce([1..$n], $product) };
 *   $sin := function($x){ $cos($x - $pi/2) };
 *   $cos := function($x){ ... };
 *
 *   ["sin", "cos", "pi"]
 * )
 * }</pre>
 *
 * <p>Each exported name lands in {@link #getFunctions()} or {@link #getConstants()} according to
 * what it evaluated to — the definition does not have to say which is which. Names it binds but does
 * not export ({@code $product}, {@code $factorial}) stay internal, while remaining reachable from
 * the exported closures; mutual recursion between exported functions works.
 *
 * <pre>{@code
 * JsonataLibrary trig = factory.compileLibrary(definition);
 *
 * JsonataExpression report = factory.compile("angles.$sin($) * $pi");
 * trig.getFunctions().forEach(report::registerFunction);
 * trig.getConstants().forEach(report::assign);
 * }</pre>
 *
 * <h2>Semantics worth knowing</h2>
 * <ul>
 *   <li><b>The definition is self-contained.</b> Every name it uses must be one it binds, a JSONata
 *       built-in, or one supplied through {@link JsonataLibraryOptions#bindings} — anything else is
 *       rejected when the library is compiled, rather than resolved against whatever happens to be
 *       bound where an exported function is called.</li>
 *   <li><b>The caller's evaluation is reused.</b> Called from inside an expression, an exported
 *       function shares that evaluation's recursion budget (100 nested calls) and timeout.</li>
 *   <li><b>Thread-safe.</b> The definition runs exactly once, at build time; afterwards the closure
 *       graph is read-only and the exported functions may be called concurrently.</li>
 *   <li><b>Functions returned at call time are not durable.</b> If an exported function returns a
 *       <em>new</em> function, that one lives only for the evaluation that produced it — the same
 *       rule as any lambda created mid-expression.</li>
 * </ul>
 *
 * <p>A library owns one generated class; build it once and keep it. {@link #close()} retires the
 * exported functions for callers that want the lifetime to be explicit; otherwise simply dropping
 * every reference to them releases everything.
 */
public final class JsonataLibrary implements AutoCloseable {

    private final String sourceJsonata;
    private final AbstractJsonataExpression definition;
    private final JsonataBindings definitionBindings;
    private volatile boolean closed;
    private final Map<String, JsonataBoundFunction> functions = new LinkedHashMap<>();
    private final Map<String, JsonNode> constants = new LinkedHashMap<>();
    private final Map<String, JsonataBoundFunction> functionsView =
            Collections.unmodifiableMap(functions);
    private final Map<String, JsonNode> constantsView =
            Collections.unmodifiableMap(constants);

    JsonataLibrary(String sourceJsonata, AbstractJsonataExpression definition,
                   JsonataBindings definitionBindings) {
        this.sourceJsonata = sourceJsonata;
        this.definition = definition;
        this.definitionBindings = definitionBindings;
    }

    /** Called only from {@link JsonataExpressionFactory} while building this library. */
    void exportFunction(String name, JsonataBoundFunction fn) {
        functions.put(name, fn);
    }

    /** Called only from {@link JsonataExpressionFactory} while building this library. */
    void exportConstant(String name, JsonNode value) {
        constants.put(name, value);
    }

    /**
     * Returns the exported functions, keyed by name <b>without</b> the leading {@code $}, in the
     * order the definition listed them. Immutable, and ready for
     * {@link JsonataBindings#bindFunctions} or {@link JsonataExpression#registerFunction}.
     */
    public Map<String, JsonataBoundFunction> getFunctions() {
        return functionsView;
    }

    /**
     * Returns the exported values — every exported name that did not evaluate to a function — keyed
     * the same way. Immutable, and ready for {@link JsonataBindings#bindValue} or
     * {@link JsonataExpression#assign}.
     *
     * <p>They are values, not expressions: the definition ran once, at compile time, so a constant
     * is whatever it evaluated to then.
     */
    public Map<String, JsonNode> getConstants() {
        return constantsView;
    }

    /** Returns the definition expression this library was compiled from. */
    public String getSourceJsonata() {
        return sourceJsonata;
    }

    /** Returns {@code true} until {@link #close()} is called. */
    public boolean isOpen() {
        return !closed;
    }

    /**
     * Retires the exported functions: calling one afterwards throws a
     * {@link JsonataEvaluationException}. Constants already handed out keep working — they are
     * ordinary nodes. Idempotent, and never required: a library that simply becomes unreachable is
     * collected like any other object.
     */
    @Override
    public void close() {
        closed = true;
    }

    /** Throws if this library has been closed; called before every exported invocation. */
    void checkOpen(String functionName) throws JsonataEvaluationException {
        if (closed)
            throw new JsonataEvaluationException(null,
                    "Error calling exported function $" + functionName
                            + ": its library has been closed");
    }

    /**
     * Opens an evaluation frame for a call made from plain Java, outside any expression. The frame
     * carries the library's definition-time bindings so that a function whose body references a
     * value the definition did not bind still resolves it.
     */
    void beginStandaloneFrame() {
        JsonataRuntime.beginEvaluation(definition.permanentBindingSet(), definitionBindings,
                definition.instanceRegexes(), 0);
    }

    @Override
    public String toString() {
        return "JsonataLibrary[functions=" + functions.keySet() + ", constants=" + constants.keySet() + "]";
    }
}
