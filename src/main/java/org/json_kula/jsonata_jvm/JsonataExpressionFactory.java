package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.loader.JsonataExpressionLoader;
import org.json_kula.jsonata_jvm.loader.JsonataLoadException;
import org.json_kula.jsonata_jvm.optimizer.Optimizer;
import org.json_kula.jsonata_jvm.parser.ParseException;
import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;
import org.json_kula.jsonata_jvm.runtime.LambdaScope;
import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;
import org.json_kula.jsonata_jvm.translator.RuntimeTranslatorException;
import org.json_kula.jsonata_jvm.translator.Translator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private final JsonataExpressionLoader loader = new JsonataExpressionLoader();

    public JsonataExpressionFactory() {
        JsonataRuntime.registerEvalDelegate((expr, ctx) -> {
            try {
                JsonataExpression compiled = compile(expr);
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
            String src = translate(expression);
            return loader.load(src);
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
        for (int i = 0; i < expressions.size(); i++) {
            String expression = expressions.get(i);
            try {
                sources.add(translate(expression));
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
            return loader.loadAll(sources);
        } catch (JsonataLoadException e) {
            throw new JsonataCompilationException(
                    null, "Failed to load generated classes for batch: " + e.getMessage(), e);
        }
    }

    /**
     * Compiles a JSONata <em>definition expression</em> and returns the functions it exports, ready
     * to be registered on any expression.
     *
     * <p>A definition expression is ordinary JSONata: it binds named functions and <b>returns the
     * names of the ones to export</b> as an array of strings. It evaluates on its own in any JSONata
     * engine, where its result is simply that list.
     *
     * <pre>{@code
     * Map<String, JsonataBoundFunction> trig = factory.compileFunctions("""
     *         (
     *           $pi := 3.1415926535897932384626;
     *           $product := function($a, $b) { $a * $b };
     *           $factorial := function($n) { $n = 0 ? 1 : $reduce([1..$n], $product) };
     *           $sin := function($x){ $cos($x - $pi/2) };
     *           $cos := function($x){ ... };
     *
     *           ["sin", "cos"]
     *         )
     *         """);
     *
     * JsonataExpression expr = factory.compile("angles.$sin($)");
     * trig.forEach(expr::registerFunction);
     * }</pre>
     *
     * <p>The definition is compiled and evaluated once, here. Only the names it returns are
     * exported; everything else it binds ({@code $pi}, {@code $product}, {@code $factorial}) stays
     * internal but remains reachable from the exported functions. Names may be listed with or
     * without the leading {@code $}, and map keys never carry it.
     *
     * <p>The returned functions keep their {@link JsonataFunctionLibrary} — and its generated class
     * — alive for as long as they are referenced. Use {@link #compileFunctionLibrary(String)}
     * instead when the lifetime should be explicit.
     *
     * @param functionDefinition the JSONata expression that binds the functions and returns the
     *                           names to export
     * @return the exported functions keyed by name without {@code $}, in the order the definition
     *         named them
     * @throws JsonataCompilationException if the definition cannot be compiled, does not return a
     *         usable array of names, names something it does not bind at its top level, or names
     *         something that is not a function
     */
    public Map<String, JsonataBoundFunction> compileFunctions(String functionDefinition)
            throws JsonataCompilationException {
        return compileFunctionLibrary(functionDefinition).asMap();
    }

    /**
     * As {@link #compileFunctions}, but returns the owning {@link JsonataFunctionLibrary} so that
     * its lifetime can be ended explicitly with {@link JsonataFunctionLibrary#close()}.
     */
    public JsonataFunctionLibrary compileFunctionLibrary(String functionDefinition)
            throws JsonataCompilationException {
        return compileFunctionLibrary(functionDefinition, null);
    }

    /**
     * As {@link #compileFunctionLibrary(String)}, with control over the input document, the
     * definition-time bindings and the reported signatures.
     *
     * @param options extra settings, or {@code null} for the defaults
     */
    public JsonataFunctionLibrary compileFunctionLibrary(String functionDefinition,
                                                         JsonataFunctionLibraryOptions options)
            throws JsonataCompilationException {
        JsonataFunctionLibraryOptions opts =
                options != null ? options : new JsonataFunctionLibraryOptions();

        AstNode ast;
        try {
            ast = Parser.parse(functionDefinition);
        } catch (ParseException e) {
            throw new JsonataCompilationException(
                    e.getErrorCode(), "Invalid JSONata expression: " + e.getMessage(), e);
        }

        Map<String, AstNode> topLevelBindings = FunctionExportRewriter.topLevelBindings(ast);

        String source;
        try {
            AstNode rewritten = FunctionExportRewriter.rewrite(ast, topLevelBindings.keySet());
            String className = "CompiledFunctionLibrary" + CLASS_COUNTER.incrementAndGet();
            source = Translator.translate(
                    Optimizer.optimize(rewritten), GEN_PACKAGE, className, functionDefinition);
        } catch (RuntimeTranslatorException e) {
            throw new JsonataCompilationException(
                    e.getErrorCode(), "Failed to translate definition expression: " + e.getMessage(), e);
        }

        JsonataExpression compiled;
        try {
            compiled = loader.load(source);
        } catch (JsonataLoadException e) {
            throw new JsonataCompilationException(
                    null, "Failed to load generated class for definition expression: " + e.getMessage(), e);
        }
        if (!(compiled instanceof AbstractJsonataExpression definition)) {
            throw new JsonataCompilationException(
                    null, "Generated class does not extend AbstractJsonataExpression", null);
        }

        LambdaScope scope = LambdaScope.create();
        JsonataFunctionLibrary library = new JsonataFunctionLibrary(
                functionDefinition, definition, scope, opts.getBindings());

        try {
            JsonNode exported = definition.evaluateDefining(opts.getInput(), opts.getBindings(), scope);
            JsonNode values = exported != null
                    ? exported.get(FunctionExportRewriter.FUNCTIONS_FIELD) : null;
            JsonNode names = exported != null
                    ? exported.get(FunctionExportRewriter.NAMES_FIELD) : null;
            for (String name : FunctionExportRewriter.exportedNames(names)) {
                library.export(name, wrap(name, values, topLevelBindings, opts, library));
            }
        } catch (JsonataEvaluationException e) {
            scope.close();
            throw new JsonataCompilationException(
                    e.getErrorCode(), "Definition expression failed to evaluate: " + e.getMessage(), e);
        } catch (JsonataCompilationException | RuntimeException e) {
            scope.close();
            throw e;
        }
        return library;
    }

    /** Wraps one exported lambda token, reporting precisely why a name cannot be exported. */
    private static JsonataBoundFunction wrap(String name, JsonNode values,
                                             Map<String, AstNode> topLevelBindings,
                                             JsonataFunctionLibraryOptions opts,
                                             JsonataFunctionLibrary library)
            throws JsonataCompilationException {
        JsonNode token = values != null ? values.get(name) : null;
        if (token == null || token.isMissingNode()) {
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
        if (!JsonataRuntime.isLambdaToken(token)) {
            throw new JsonataCompilationException(null,
                    "$" + name + " is not a function; the definition expression bound it to "
                            + JsonataRuntime.fn_type(token).asText() + " "
                            + JsonataRuntime.sanitizeForString(token), null);
        }
        FunctionExportRewriter.ExportInfo info =
                FunctionExportRewriter.describe(topLevelBindings.get(name));
        String override = opts.getSignatureOverride(name);
        return new ExportedJsonataFunction(
                name, token, override != null ? override : info.signature(), info.arity(), library);
    }

    public String translate(String expression) throws JsonataCompilationException {
        try {
            AstNode ast = Optimizer.optimize(Parser.parse(expression));
            String className = "CompiledExpr" + CLASS_COUNTER.incrementAndGet();
            return Translator.translate(ast, GEN_PACKAGE, className, expression);
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
