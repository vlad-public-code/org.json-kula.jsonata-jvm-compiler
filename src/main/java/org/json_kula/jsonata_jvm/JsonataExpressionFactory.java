package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.loader.JsonataExpressionLoader;
import org.json_kula.jsonata_jvm.loader.JsonataLoadException;
import org.json_kula.jsonata_jvm.optimizer.Optimizer;
import org.json_kula.jsonata_jvm.parser.ParseException;
import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;
import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;
import org.json_kula.jsonata_jvm.translator.RuntimeTranslatorException;
import org.json_kula.jsonata_jvm.translator.Translator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry-point factory for the JSONata-JVM pipeline.
 *
 * <p>Converts a JSONata expression string into a compiled, ready-to-evaluate
 * {@link JsonataExpression} by running the full pipeline:
 * <ol>
 *   <li>Parse the expression string into an AST ({@link Parser})</li>
 *   <li>Optimise the AST ({@link Optimizer})</li>
 *   <li>Translate the AST to Java 21 source ({@link Translator})</li>
 *   <li>Compile and load the generated class in-memory ({@link JsonataExpressionLoader})</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>{@code
 * JsonataExpressionFactory factory = new JsonataExpressionFactory();
 * JsonataExpression expr = factory.compile("Account.Order.Product.Price * 1.2");
 * JsonNode result = expr.evaluate(json);
 * }</pre>
 *
 * <p>Instances are thread-safe and may be reused for many {@link #compile} calls.
 * Each call produces a distinct, independent {@link JsonataExpression}.
 */
public class JsonataExpressionFactory {

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();
    private static final String GEN_PACKAGE = "org.json_kula.jsonata_jvm.gen";

    /**
     * Compiled expressions for {@code $eval}, keyed by expression text.
     *
     * <p>{@code $eval} compiles at evaluation time, and compilation costs ~85 ms — orders of
     * magnitude more than evaluating the result. Almost every real use evaluates the same handful of
     * expression strings repeatedly (often the same one per element of a sequence), so caching turns
     * all but the first into a map lookup. Bounded because the key comes from the data and could
     * otherwise grow without limit; the eviction order is arbitrary, which only costs a recompile.
     */
    private static final int EVAL_CACHE_LIMIT = 256;
    private final Map<String, JsonataExpression> evalCache = new ConcurrentHashMap<>();

    private final JsonataExpressionLoader loader = new JsonataExpressionLoader();

    /** This factory pipeline, as used by {@code $eval} inside expressions it compiled. */
    private final JsonataRuntime.EvalDelegate evalDelegate;

    public JsonataExpressionFactory() {
        this.evalDelegate = ((expr, ctx) -> {
            try {
                JsonataExpression compiled = evalCache.get(expr);
                if (compiled == null) {
                    compiled = compile(expr);
                    if (evalCache.size() >= EVAL_CACHE_LIMIT) evalCache.clear();
                    evalCache.put(expr, compiled);
                }
                if (ctx != null && !ctx.isMissingNode()) {
                    return compiled.evaluate(ctx);
                }
                return compiled.evaluate(com.fasterxml.jackson.databind.node.NullNode.instance);
            } catch (JsonataCompilationException e) {
                if ("T1005".equals(e.getErrorCode())) {
                    throw new RuntimeEvaluationException("D3121", "The expression cannot be evaluated");
                }
                throw new RuntimeEvaluationException("D3120", "The expression cannot be parsed");
            } catch (JsonataEvaluationException e) {
                throw new RuntimeEvaluationException("D3121", "The expression cannot be evaluated");
            }
        });
        // Also the process-wide fallback, for hand-written expression classes that open an
        // evaluation frame without a delegate of their own.
        JsonataRuntime.registerEvalDelegate(evalDelegate);
    }

    /**
     * Attaches this factory to a freshly loaded instance, so that {@code $eval} inside it runs
     * through this pipeline for the rest of the instance lifetime.
     */
    private JsonataExpression attach(JsonataExpression expression) {
        if (expression instanceof AbstractJsonataExpression generated) {
            generated.setEvalDelegate(evalDelegate);
        }
        return expression;
    }

    /**
     * Compiles {@code expression} and returns a ready-to-evaluate
     * {@link JsonataExpression}.
     *
     * <p>The returned instance's {@link JsonataExpression#getSourceJsonata()}
     * method returns the original {@code expression} string unchanged.
     *
     * @param expression the JSONata expression to compile; must not be {@code null}
     * @return a compiled, reusable {@link JsonataExpression}
     * @throws JsonataCompilationException if {@code expression} is syntactically
     *         invalid or if the generated Java code cannot be compiled
     */
    public JsonataExpression compile(String expression) throws JsonataCompilationException {
        try {
            String className = nextClassName();
            String src = translate(expression, className);
            return attach(loader.load(className, src));
        } catch (JsonataLoadException e) {
            throw new JsonataCompilationException(
                    null, "Failed to load generated class for expression: " + e.getMessage(), e);
        }
    }

    /**
     * Compiles a batch of expressions and returns one {@link JsonataExpression} per input, in the
     * same order.
     *
     * <p>Functionally equivalent to calling {@link #compile} on each expression, but the expensive
     * {@code javac} step runs <b>once</b> for the whole batch instead of once per expression. Since
     * that step's cost is dominated by a fixed per-invocation overhead (compiler bootstrap, platform
     * symbol loading, classpath indexing) that a single small generated class barely adds to,
     * batching many expressions is markedly faster than compiling them one by one — the typical case
     * when a model registers all of its derivations, constraints, and effects at creation time.
     *
     * <p>Parsing and translation still happen per expression (they are cheap and let a syntactically
     * invalid expression be pinpointed by its index); only the compile-and-load step is batched. Any
     * expression that fails to parse, translate, compile, or instantiate aborts the whole batch.
     *
     * <p>Each returned instance's {@link JsonataExpression#getSourceJsonata()} returns its own
     * original expression string unchanged.
     *
     * @param expressions the JSONata expressions to compile; must not be {@code null} and must
     *                    contain no {@code null} elements
     * @return one compiled, reusable {@link JsonataExpression} per input, in order; empty if the
     *         input is empty
     * @throws JsonataCompilationException if any expression is syntactically invalid or if the
     *         generated Java code cannot be compiled; the message identifies the offending
     *         expression
     */
    public List<JsonataExpression> compileAll(List<String> expressions) throws JsonataCompilationException {
        if (expressions.isEmpty()) {
            return List.of();
        }

        List<String> sources = new ArrayList<>(expressions.size());
        List<String> classNames = new ArrayList<>(expressions.size());
        for (int i = 0; i < expressions.size(); i++) {
            String expression = expressions.get(i);
            try {
                String className = nextClassName();
                classNames.add(className);
                sources.add(translate(expression, className));
            } catch (JsonataCompilationException e) {
                // Re-attribute to the failing element while preserving the original error code and
                // root cause (e.g. the ParseException), so batch failures are as diagnosable as
                // single-expression ones.
                throw new JsonataCompilationException(
                        e.getErrorCode(),
                        "Failed to compile expression [" + i + "] (" + expression + "): " + e.getMessage(),
                        e.getCause() != null ? e.getCause() : e);
            }
        }

        try {
            List<JsonataExpression> compiled = loader.loadAll(classNames, sources);
            compiled.forEach(this::attach);
            return compiled;
        } catch (JsonataLoadException e) {
            throw new JsonataCompilationException(
                    null, "Failed to load generated classes for batch: " + e.getMessage(), e);
        }
    }

    /**
     * Compiles a JSONata <em>library</em>: a definition expression, and everything it exports.
     *
     * <p>A definition expression is ordinary JSONata. It binds names — functions, values, or both —
     * and <b>returns the names to export</b> as an array of strings. It evaluates on its own in any
     * JSONata engine, where its result is simply that list.
     *
     * <pre>{@code
     * JsonataLibrary trig = factory.compileLibrary("""
     *         (
     *           $pi := 3.1415926535897932384626;
     *           $product := function($a, $b) { $a * $b };
     *           $factorial := function($n) { $n = 0 ? 1 : $reduce([1..$n], $product) };
     *           $sin := function($x){ $cos($x - $pi/2) };
     *           $cos := function($x){ ... };
     *
     *           ["sin", "cos", "pi"]
     *         )
     *         """);
     *
     * JsonataExpression expr = factory.compile("angles.$sin($) * $pi");
     * expr.useLibrary(trig);
     * }</pre>
     *
     * <p>The definition is compiled and evaluated once, here. Each exported name goes to
     * {@link JsonataLibrary#getFunctions()} or {@link JsonataLibrary#getConstants()} according to
     * what it evaluated to. Names the definition binds but does not export ({@code $product},
     * {@code $factorial}) stay internal, while remaining reachable from the exported functions.
     * Names may be listed with or without the leading {@code $}; map keys never carry it.
     *
     * @param definition the JSONata expression that binds the names and returns the ones to export
     * @return the library, holding the exported functions and constants in export-list order
     * @throws JsonataCompilationException if the definition cannot be compiled, does not return a
     *         usable array of names, or names something it does not bind at its top level
     */
    public JsonataLibrary compileLibrary(String definition) throws JsonataCompilationException {
        return compileLibrary(definition, null);
    }

    /**
     * As {@link #compileLibrary(String)}, with control over the input document, the definition-time
     * bindings and the reported function signatures.
     *
     * @param options extra settings, or {@code null} for the defaults
     */
    public JsonataLibrary compileLibrary(String definition, JsonataLibraryOptions options)
            throws JsonataCompilationException {
        JsonataLibraryOptions opts = options != null ? options : new JsonataLibraryOptions();

        AstNode ast;
        try {
            ast = Parser.parse(definition);
        } catch (ParseException e) {
            throw new JsonataCompilationException(
                    e.getErrorCode(), "Invalid JSONata expression: " + e.getMessage(), e);
        }

        Map<String, AstNode> topLevelBindings = FunctionExportRewriter.topLevelBindings(ast);
        FunctionExportRewriter.requireSelfContained(ast, providedNames(opts));

        String source;
        String libraryClassName;
        try {
            AstNode rewritten = FunctionExportRewriter.rewrite(ast, topLevelBindings.keySet());
            libraryClassName = GEN_PACKAGE + ".CompiledLibrary" + CLASS_COUNTER.incrementAndGet();
            source = Translator.translate(Optimizer.optimize(rewritten), GEN_PACKAGE,
                    simpleName(libraryClassName), definition);
        } catch (RuntimeTranslatorException e) {
            throw new JsonataCompilationException(
                    e.getErrorCode(), "Failed to translate definition expression: " + e.getMessage(), e);
        }

        JsonataExpression compiled;
        try {
            compiled = attach(loader.load(libraryClassName, source));
        } catch (JsonataLoadException e) {
            throw new JsonataCompilationException(
                    null, "Failed to load generated class for definition expression: " + e.getMessage(), e);
        }
        if (!(compiled instanceof AbstractJsonataExpression definitionExpression)) {
            throw new JsonataCompilationException(
                    null, "Generated class does not extend AbstractJsonataExpression", null);
        }

        JsonataLibrary library = new JsonataLibrary(
                definition, definitionExpression, opts.getBindings());

        try {
            // The definition runs exactly once. Its function values are ordinary nodes, so the
            // exported functions stay callable for as long as the library is referenced.
            JsonNode exported = definitionExpression.evaluate(opts.getInput(), opts.getBindings());
            JsonNode values = exported != null
                    ? exported.get(FunctionExportRewriter.FUNCTIONS_FIELD) : null;
            JsonNode names = exported != null
                    ? exported.get(FunctionExportRewriter.NAMES_FIELD) : null;
            for (String name : FunctionExportRewriter.exportedNames(names)) {
                JsonNode value = exportedValue(name, values, topLevelBindings);
                if (JsonataRuntime.isLambdaToken(value)) {
                    library.exportFunction(name, wrap(name, value, topLevelBindings, opts, library));
                } else {
                    library.exportConstant(name, value);
                }
            }
        } catch (JsonataEvaluationException e) {
            throw new JsonataCompilationException(
                    e.getErrorCode(), "Definition expression failed to evaluate: " + e.getMessage(), e);
        }
        return library;
    }

    /** The names the caller supplies at build time, which a definition may therefore rely on. */
    private static java.util.Set<String> providedNames(JsonataLibraryOptions options) {
        JsonataBindings bindings = options.getBindings();
        if (bindings == null) return java.util.Set.of();
        java.util.Set<String> names = new java.util.HashSet<>(bindings.getValues().keySet());
        names.addAll(bindings.getFunctions().keySet());
        return names;
    }

    /**
     * Returns what {@code name} evaluated to, or explains precisely why it cannot be exported: the
     * definition never bound it, or bound it to nothing.
     */
    private static JsonNode exportedValue(String name, JsonNode values,
                                          Map<String, AstNode> topLevelBindings)
            throws JsonataCompilationException {
        JsonNode value = values != null ? values.get(name) : null;
        if (value != null && !value.isMissingNode()) return value;
        throw new JsonataCompilationException(null,
                topLevelBindings.containsKey(name)
                        ? "$" + name + " is exported but evaluated to nothing in the definition"
                                + " expression"
                        : "$" + name + " is exported but not defined at the top level of the"
                                + " definition expression"
                                + (topLevelBindings.isEmpty()
                                        ? " (it binds no variables at all)"
                                        : " (it binds: $"
                                                + String.join(", $", topLevelBindings.keySet()) + ")"),
                null);
    }

    /** Wraps one exported function value, with the arity and signature recovered from the AST. */
    private static JsonataBoundFunction wrap(String name, JsonNode value,
                                             Map<String, AstNode> topLevelBindings,
                                             JsonataLibraryOptions opts,
                                             JsonataLibrary library) {
        FunctionExportRewriter.ExportInfo info =
                FunctionExportRewriter.describe(topLevelBindings.get(name));
        String override = opts.getSignatureOverride(name);
        return new ExportedJsonataFunction(
                name, value, override != null ? override : info.signature(), info.arity(), library);
    }

    /** Mints the fully-qualified name of the next generated class. */
    private static String nextClassName() {
        return GEN_PACKAGE + ".CompiledExpr" + CLASS_COUNTER.incrementAndGet();
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    public String translate(String expression) throws JsonataCompilationException {
        return translate(expression, nextClassName());
    }

    /** Translates {@code expression} into a class with the given fully-qualified name. */
    private String translate(String expression, String className) throws JsonataCompilationException {
        try {
            AstNode ast = Optimizer.optimize(Parser.parse(expression));
            return Translator.translate(ast, GEN_PACKAGE, simpleName(className), expression);
        } catch (ParseException e) {
            throw new JsonataCompilationException(
                    e.getErrorCode(), "Invalid JSONata expression: " + e.getMessage(), e);
        }
        catch (RuntimeTranslatorException e) {
            throw new JsonataCompilationException(
                    e.getErrorCode(), "Failed to translate expression: " + e.getMessage(), e);
        }
    }
}
