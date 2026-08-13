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
     * Compiles a JSONata <em>definition expression</em> and returns the named functions it binds,
     * ready to be registered on any expression.
     *
     * <pre>{@code
     * Map<String, JsonataBoundFunction> trig = factory.compileFunctions(
     *         List.of("$sin", "$cos"),
     *         """
     *         (
     *           $pi := 3.1415926535897932384626;
     *           $product := function($a, $b) { $a * $b };
     *           $factorial := function($n) { $n = 0 ? 1 : $reduce([1..$n], $product) };
     *           $sin := function($x){ $cos($x - $pi/2) };
     *           $cos := function($x){ ... };
     *         )
     *         """);
     *
     * JsonataExpression expr = factory.compile("angles.$sin($)");
     * trig.forEach(expr::registerFunction);
     * }</pre>
     *
     * <p>The definition expression is evaluated once, here; its own result is discarded. Names may
     * be written with or without the leading {@code $}, and map keys never carry it. Bindings that
     * the exported functions rely on need not be exported themselves — they are captured.
     *
     * <p>The returned functions keep their {@link JsonataFunctionLibrary} — and its generated class
     * — alive for as long as they are referenced. Use
     * {@link #compileFunctionLibrary(List, String)} instead when the lifetime should be explicit.
     *
     * @param functionNames    the functions to export; at least one, no duplicates
     * @param functionDefinition the JSONata expression that binds them
     * @return the exported functions keyed by name without {@code $}, in request order
     * @throws JsonataCompilationException if the definition cannot be compiled, if a requested name
     *         is not bound at the top level of the definition, or if a requested name turns out not
     *         to be a function
     */
    public Map<String, JsonataBoundFunction> compileFunctions(List<String> functionNames,
                                                              String functionDefinition)
            throws JsonataCompilationException {
        return compileFunctionLibrary(functionNames, functionDefinition).asMap();
    }

    /**
     * As {@link #compileFunctions}, but returns the owning {@link JsonataFunctionLibrary} so that
     * its lifetime can be ended explicitly with {@link JsonataFunctionLibrary#close()}.
     */
    public JsonataFunctionLibrary compileFunctionLibrary(List<String> functionNames,
                                                         String functionDefinition)
            throws JsonataCompilationException {
        return compileFunctionLibrary(functionNames, functionDefinition, null);
    }

    /**
     * As {@link #compileFunctionLibrary(List, String)}, with control over the input document, the
     * definition-time bindings and the reported signatures.
     *
     * @param options extra settings, or {@code null} for the defaults
     */
    public JsonataFunctionLibrary compileFunctionLibrary(List<String> functionNames,
                                                         String functionDefinition,
                                                         JsonataFunctionLibraryOptions options)
            throws JsonataCompilationException {
        List<String> names = FunctionExportRewriter.normalizeAll(functionNames);
        JsonataFunctionLibraryOptions opts =
                options != null ? options : new JsonataFunctionLibraryOptions();

        AstNode ast;
        try {
            ast = Parser.parse(functionDefinition);
        } catch (ParseException e) {
            throw new JsonataCompilationException(
                    e.getErrorCode(), "Invalid JSONata expression: " + e.getMessage(), e);
        }

        Map<String, FunctionExportRewriter.ExportInfo> infos =
                FunctionExportRewriter.analyze(ast, names);

        String source;
        try {
            AstNode optimized = Optimizer.optimize(FunctionExportRewriter.rewrite(ast, names));
            String className = "CompiledFunctionLibrary" + CLASS_COUNTER.incrementAndGet();
            source = Translator.translate(optimized, GEN_PACKAGE, className, functionDefinition);
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

        JsonNode exported;
        try {
            exported = definition.evaluateDefining(opts.getInput(), opts.getBindings(), scope);
        } catch (JsonataEvaluationException e) {
            scope.close();
            throw new JsonataCompilationException(
                    e.getErrorCode(), "Definition expression failed to evaluate: " + e.getMessage(), e);
        }

        for (String name : names) {
            JsonNode token = exported != null ? exported.get(name) : null;
            if (token == null || !JsonataRuntime.isLambdaToken(token)) {
                scope.close();
                throw new JsonataCompilationException(null,
                        "$" + name + " is not a function; the definition expression bound it to "
                                + describe(token), null);
            }
            FunctionExportRewriter.ExportInfo info = infos.get(name);
            String override = opts.getSignatureOverride(name);
            library.export(name, new ExportedJsonataFunction(
                    name, token, override != null ? override : info.signature(),
                    info.arity(), library));
        }
        return library;
    }

    /** Describes an exported value that turned out not to be a function, for the error message. */
    private static String describe(JsonNode value) {
        if (value == null || value.isMissingNode()) return "nothing";
        return JsonataRuntime.fn_type(value).asText() + " " + JsonataRuntime.sanitizeForString(value);
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
