package org.json_kula.jsonata_jvm;

import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A set of JSONata functions compiled from a <em>definition expression</em> and exposed to Java as
 * {@link JsonataBoundFunction}s.
 *
 * <p>A definition expression binds named functions and returns the names of the ones to export. It
 * is ordinary JSONata — evaluated in any JSONata engine it simply yields that list:
 *
 * <pre>{@code
 * (
 *   $pi := 3.1415926535897932384626;
 *   $product := function($a, $b) { $a * $b };
 *   $factorial := function($n) { $n = 0 ? 1 : $reduce([1..$n], $product) };
 *   $sin := function($x){ $cos($x - $pi/2) };
 *   $cos := function($x){ ... };
 *
 *   ["sin", "cos"]
 * )
 * }</pre>
 *
 * <p>The two named functions come back as bound functions that can be registered on any expression
 * or called directly from Java. Bindings they depend on ({@code $pi}, {@code $product},
 * {@code $factorial}) stay reachable through the exported closures without being exported
 * themselves, and mutual recursion between exported functions works.
 *
 * <pre>{@code
 * Map<String, JsonataBoundFunction> trig = factory.compileFunctions(definition);
 *
 * JsonataExpression report = factory.compile("angles.$sin($)");
 * trig.forEach(report::registerFunction);
 * }</pre>
 *
 * <h2>Semantics worth knowing</h2>
 * <ul>
 *   <li><b>Helper bindings are captured; free variables are not.</b> A name bound inside the
 *       definition is baked into the closure. A name the definition never binds (say {@code $rate})
 *       resolves against whatever bindings are active where the function is <em>called</em>, or
 *       against the library's own definition-time bindings when it is called from plain Java.</li>
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
public final class JsonataFunctionLibrary implements AutoCloseable {

    private final String sourceJsonata;
    private final AbstractJsonataExpression definition;
    private final JsonataBindings definitionBindings;
    private volatile boolean closed;
    private final Map<String, JsonataBoundFunction> functions = new LinkedHashMap<>();
    private final Map<String, JsonataBoundFunction> functionsView =
            Collections.unmodifiableMap(functions);

    JsonataFunctionLibrary(String sourceJsonata, AbstractJsonataExpression definition,
                           JsonataBindings definitionBindings) {
        this.sourceJsonata = sourceJsonata;
        this.definition = definition;
        this.definitionBindings = definitionBindings;
    }

    /** Called only from {@link JsonataExpressionFactory} while building this library. */
    void export(String name, JsonataBoundFunction fn) {
        functions.put(name, fn);
    }

    /**
     * Returns the exported functions keyed by name <b>without</b> the leading {@code $}, in the
     * order they were requested. The map is immutable and can be passed straight to
     * {@link JsonataBindings#bindFunctions} or iterated into
     * {@link JsonataExpression#registerFunction}.
     */
    public Map<String, JsonataBoundFunction> asMap() {
        return functionsView;
    }

    /**
     * Returns one exported function, or {@code null} if this library does not export it.
     * The leading {@code $} is optional.
     */
    public JsonataBoundFunction get(String name) {
        return functions.get(FunctionExportRewriter.normalize(name));
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
     * {@link JsonataEvaluationException}. Idempotent, and never required — a library that simply
     * becomes unreachable is collected like any other object.
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
                            + ": its function library has been closed");
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
        return "JsonataFunctionLibrary" + functions.keySet();
    }
}
