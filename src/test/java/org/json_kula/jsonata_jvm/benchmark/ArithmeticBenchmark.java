package org.json_kula.jsonata_jvm.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks covering the arithmetic-chain numeric-specialisation optimisation.
 *
 * <h2>Running via exec:java (development, no fork)</h2>
 * <pre>
 *   mvn test-compile exec:java -Dexec.args="-wi 3 -i 5 -f 0"
 * </pre>
 * The {@code -f 0} flag disables forking so the JMH runner stays in the same JVM as the
 * test classpath.  This is sufficient for relative comparisons and development cycles.
 *
 * <h2>Running with proper fork isolation (CI / release)</h2>
 * Build a fat JAR first then run it — forking works because all classes are bundled:
 * <pre>
 *   mvn package -Pbench
 *   java -jar target/benchmarks.jar -wi 5 -i 10 -f 2
 * </pre>
 * (The {@code bench} profile must be added to pom.xml if full fork isolation is needed.)
 *
 * <h2>Benchmarks</h2>
 * <ul>
 *   <li>{@code evalArithmeticChain} — {@code $sum(items.((price - discount) * qty * tax))}
 *       over 1 000 items.  Three arithmetic ops per element; this is the primary target
 *       of the TypedExpr numeric-specialisation: 2 intermediate {@code JsonNode} allocations
 *       per element eliminated.</li>
 *   <li>{@code evalScalarArithmetic} — {@code (a * b + c * d) / (e - f)}: a five-op scalar
 *       chain.  Measures overhead reduction on a single-evaluation arithmetic expression.</li>
 *   <li>{@code evalFullExpression} — the full benchmark expression (salary stats, product
 *       catalogue, order analytics).  Represents a realistic workload mixing arithmetic,
 *       path navigation, aggregation, and string ops.</li>
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-XX:+UseG1GC", "-Xmx512m"})
public class ArithmeticBenchmark {

    private JsonataExpression sumArithExpr;
    private JsonataExpression scalarArithExpr;
    private JsonataExpression fullExpr;

    private JsonNode arithInput;
    private JsonNode scalarInput;
    private JsonNode fullInput;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        JsonataExpressionFactory factory = new JsonataExpressionFactory();
        ObjectMapper mapper = new ObjectMapper();

        // ---- arithmetic chain over 1 000 items ----
        sumArithExpr = factory.compile("$sum(items.((price - discount) * qty * tax))");
        ArrayNode items = mapper.createArrayNode();
        Random rng = new Random(42);
        for (int i = 0; i < 1_000; i++) {
            ObjectNode item = mapper.createObjectNode();
            item.put("price",    50.0 + rng.nextDouble() * 450.0);
            item.put("qty",      1    + rng.nextInt(50));
            item.put("discount", rng.nextDouble() * 40.0);
            item.put("tax",      1.0  + rng.nextDouble() * 0.25);
            items.add(item);
        }
        arithInput = mapper.createObjectNode();
        ((ObjectNode) arithInput).set("items", items);

        // ---- scalar arithmetic chain ----
        scalarArithExpr = factory.compile("(a * b + c * d) / (e - f)");
        scalarInput = mapper.readTree(
                "{\"a\":12.5,\"b\":8.0,\"c\":3.14,\"d\":2.72,\"e\":100.0,\"f\":37.5}");

        // ---- full benchmark expression ----
        fullExpr  = factory.compile(resource("benchmark/benchmark_expression.jsonata"));
        fullInput = mapper.readTree(resource("benchmark/benchmark_input.json"));
    }

    @Benchmark
    public void evalArithmeticChain(Blackhole bh) throws Exception {
        bh.consume(sumArithExpr.evaluate(arithInput));
    }

    @Benchmark
    public void evalScalarArithmetic(Blackhole bh) throws Exception {
        bh.consume(scalarArithExpr.evaluate(scalarInput));
    }

    @Benchmark
    public void evalFullExpression(Blackhole bh) throws Exception {
        bh.consume(fullExpr.evaluate(fullInput));
    }

    public static void main(String[] args) throws Exception {
        // CommandLineOptions lets JMH CLI flags (e.g. -f 0) override @Fork and other annotations.
        var cmdOpts = new CommandLineOptions(args);
        var opt = new OptionsBuilder()
                .parent(cmdOpts)
                .include(ArithmeticBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

    private static String resource(String name) throws Exception {
        try (InputStream is = ArithmeticBenchmark.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) throw new RuntimeException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
