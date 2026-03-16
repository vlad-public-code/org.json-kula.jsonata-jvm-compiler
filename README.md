# jsonata-jvm-compiler

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)



A Java 21 library that compiles [JSONata](https://jsonata.org) expressions into native Java classes at runtime. Each expression is parsed, optimised, and translated to Java source, which is then compiled in-memory and returned as a ready-to-call `JsonataExpression` instance.

⚠️ Notice

This library is currently under active development and has not been thoroughly tested. It is provided primarily for demonstration and experimentation purposes. The implementation may contain bugs, incomplete features, or breaking changes. Do not use this library in production environments or in systems where reliability is required. Use it at your own risk.

## Requirements

| Requirement | Version |
|---|---|
| Java | 21 (JDK — a JRE is not sufficient; the in-memory compiler needs `javac`) |
| Jackson Databind | 2.18+ |

## Getting started

### 1. Add the dependency

```xml
<dependency>
    <groupId>org.json_kula</groupId>
    <artifactId>jsonata-jvm-compiler</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2. Compile an expression

```java
JsonataExpressionFactory factory = new JsonataExpressionFactory();
JsonataExpression expr = factory.compile("Account.Order.Product.Price * 1.2");
```

`compile()` runs the full pipeline once and returns a reusable, **thread-safe** object. Compile expressions at startup and reuse them for every request — do not call `compile()` on the hot path.

### 3. Evaluate against JSON

```java
String json = """
    {
      "Account": {
        "Order": {
          "Product": { "Price": 50.0 }
        }
      }
    }
    """;

JsonNode result = expr.evaluate(json);  // → 60.0
```

`evaluate()` accepts any JSON document as a string and returns a Jackson `JsonNode`. The same `JsonataExpression` instance can be evaluated concurrently from multiple threads.

## Exception types

| Exception | When thrown |
|---|---|
| `JsonataCompilationException` | `compile()` — the expression is syntactically invalid or (rarely) the generated code fails to compile |
| `JsonataEvaluationException` | `evaluate()` — the input is not valid JSON, or the expression cannot be applied to it (type mismatch, division by zero, etc.) |

```java
try {
    JsonataExpression expr = factory.compile(expression);
    JsonNode result = expr.evaluate(json);
} catch (JsonataCompilationException e) {
    // bad expression — e.getCause() is a ParseException with source position
} catch (JsonataEvaluationException e) {
    // bad input JSON or runtime error
}
```

## JSONata language features

The library implements all JSONata features except external variable binding and functional binding (coming soon!)

## Advanced usage

### Inspecting the source expression

```java
JsonataExpression expr = factory.compile("$sum(items.price)");
System.out.println(expr.getSourceJsonata());  // → "$sum(items.price)"
```

### Accessing the generated Java source

Use the lower-level API to obtain the generated source before compilation:

```java
import parser.org.json_kula.jsonata_jvm.Parser;
import optimizer.org.json_kula.jsonata_jvm.Optimizer;
import translator.org.json_kula.jsonata_jvm.Translator;
import ast.parser.org.json_kula.jsonata_jvm.AstNode;

AstNode ast = Optimizer.optimize(Parser.parse("price * qty"));
String javaSource = Translator.translate(ast, "com.example.gen", "PriceExpression", "price * qty");
System.out.println(javaSource);
```

### Loading a pre-generated Java class

If you have previously generated and saved a Java source string, compile it directly without re-parsing:

```java
import loader.org.json_kula.jsonata_jvm.JsonataExpressionLoader;

JsonataExpressionLoader loader = new JsonataExpressionLoader();
JsonataExpression expr = loader.load(javaSource);
```

## Performance

jsonata-jvm-compiler compiles expressions to native JVM bytecode, so repeated evaluation is significantly faster than interpreter-based alternatives.

### Benchmark: jsonata-jvm-compiler vs JSONata4Java

The benchmark compiles one expression once, then runs 100 000 evaluations against the same JSON document (with a 1 000-evaluation JVM warmup before timing). The expression is a realistic analytical query covering variable bindings, nested field navigation, array filtering, aggregation functions (`$sum`, `$count`, `$average`, `$max`, `$min`, `$distinct`), string operations, arithmetic, and a conditional.

Measured on OpenJDK 21 (Temurin 21.0.10), Windows 11:

| Metric | jsonata-jvm-compiler | JSONata4Java |
|---|---|---|
| Compilation | 1 641 ms | 284 ms |
| 100 000 evaluations | 5 223 ms | 44 767 ms |
| Throughput | **~19 000 eval/s** | ~2 200 eval/s |
| **Speedup** | **8.6× faster** | baseline |

> Compilation is a one-time cost paid at startup. For any workload that reuses an expression more than a handful of times the throughput advantage dominates.

The benchmark is reproducible via:

```
mvn test -Dtest=PerformanceComparisonTest#benchmark_comparison_sideBy_side
```

## Thread safety

A `JsonataExpressionFactory` instance and all `JsonataExpression` instances it produces are fully thread-safe. `evaluate()` is stateless — each call parses the input JSON independently and returns a new `JsonNode` without modifying any shared state.

```java
// Compile once at startup
JsonataExpression totalPrice = factory.compile("$sum(items.(price * qty))");

// Call concurrently from any number of threads
ExecutorService pool = Executors.newFixedThreadPool(16);
pool.submit(() -> totalPrice.evaluate(requestJson));
```

## Architecture overview

```
expression string
       │
       ▼
  Parser.parse()                   → AstNode (sealed interface hierarchy)
       │
       ▼
  Optimizer.optimize()             → AstNode (constant-folded, simplified)
       │
       ▼
  Translator.translate()           → Java 21 source string
       │
       ▼
  JsonataExpressionLoader.load()   → JsonataExpression (compiled, in-memory)
       │
       ▼
  expr.evaluate(json)              → JsonNode
```

`JsonataExpressionFactory.compile()` runs this entire pipeline in a single call.

## Package structure

| Package | Contents |
|---|---|
| `org.json_kula.jsonata_jvm` | Public API: `JsonataExpression`, `JsonataExpressionFactory`, `JsonataCompilationException`, `JsonataEvaluationException` |
| `org.json_kula.jsonata_jvm.parser` | `Parser`, `ParseException` |
| `org.json_kula.jsonata_jvm.parser.lexer` | `Lexer`, `Token`, `TokenType` |
| `org.json_kula.jsonata_jvm.parser.ast` | `AstNode` sealed interface with all node types and `Visitor` |
| `org.json_kula.jsonata_jvm.optimizer` | `Optimizer` |
| `org.json_kula.jsonata_jvm.translator` | `Translator` |
| `org.json_kula.jsonata_jvm.runtime` | `JsonataRuntime` (static helper methods), `JsonataLambda` |
| `org.json_kula.jsonata_jvm.loader` | `JsonataExpressionLoader`, `JsonataLoadException` |

## License

This project is licensed under the [Apache License 2.0](LICENSE).
