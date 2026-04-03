package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.loader.JsonataExpressionLoader;
import org.json_kula.jsonata_jvm.loader.JsonataLoadException;
import org.json_kula.jsonata_jvm.optimizer.Optimizer;
import org.json_kula.jsonata_jvm.parser.ParseException;
import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;
import org.json_kula.jsonata_jvm.translator.Translator;

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
                throw new JsonataEvaluationException("$eval: " + e.getMessage());
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
            AstNode ast = Optimizer.optimize(Parser.parse(expression));
            String className = "CompiledExpr" + CLASS_COUNTER.incrementAndGet();
            String src = Translator.translate(ast, GEN_PACKAGE, className, expression);
            return loader.load(src);
        } catch (ParseException e) {
            throw new JsonataCompilationException(
                    "Invalid JSONata expression: " + e.getMessage(), e);
        } catch (JsonataLoadException e) {
            throw new JsonataCompilationException(
                    "Failed to compile generated code for expression: " + e.getMessage(), e);
        }
    }
}
