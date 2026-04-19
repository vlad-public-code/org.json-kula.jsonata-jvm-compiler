---
title: jsonata-jvm-compiler
---

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/vlad-public-code/JSonata2Java/blob/main/LICENSE)

A Java 21 library that compiles [JSONata](https://jsonata.org) expressions into native Java classes at runtime. Each expression is parsed, optimised, and translated to Java source, which is then compiled in-memory and returned as a ready-to-call `JsonataExpression` instance.
Repeated evaluation of `JsonataExpression` instance is significantly faster than interpreter-based alternatives.

All test cases from the [official JSONata test suite](https://github.com/jsonata-js/jsonata/blob/master/test/test-suite/TESTSUITE.md) pass.

---

## Requirements

| Requirement | Version |
|---|---|
| Java | 21 (JDK — a JRE is not sufficient; the in-memory compiler needs `javac`) |
| [Jackson Databind](https://github.com/FasterXML/jackson-databind) | 2.18+ |
| [joni](https://github.com/jruby/joni) | 2.2+ (Oniguruma regex engine — used for `/pattern/flags` literals and the `$match`, `$replace`, `$split`, `$contains` functions) |

---

## Getting started

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.vlad-public-code</groupId>
    <artifactId>jsonata-jvm-compiler</artifactId>
    <version>1.0.0</version>
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
ObjectMapper mapper = new ObjectMapper();
JsonNode input = mapper.readTree("""
    {
      "Account": {
        "Order": {
          "Product": { "Price": 50.0 }
        }
      }
    }
    """);

JsonNode result = expr.evaluate(input);  // → 60.0
```

`evaluate()` accepts a Jackson `JsonNode` and returns a `JsonNode`. The same `JsonataExpression` instance can be evaluated concurrently from multiple threads.

---

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

---

## JSONata language features

The library implements all JSONata language features except:
- a function as an argument of a bound function
- a function as a bound value

---

## Bindings

Bindings let you inject named values and Java functions into an expression at runtime. Inside the expression they are referenced as `$name` (values) or called as `$name(args...)` (functions).

### Per-evaluation bindings

Pass a `JsonataBindings` instance as the second argument to `evaluate()` to supply values or functions for a single call:

```java
JsonataExpression expr = factory.compile("$taxRate * subtotal");

ObjectMapper mapper = new ObjectMapper();
JsonNode input = mapper.readTree("{\"subtotal\": 500}");
JsonNode taxRate = mapper.readTree("0.2");

JsonataBindings bindings = new JsonataBindings()
        .bindValue("taxRate", taxRate);

JsonNode result = expr.evaluate(input, bindings);  // → 100.0
```

Per-evaluation bindings are not stored on the expression instance and do not affect other calls.

### Permanent bindings

Use `assign()` and `registerFunction()` to attach bindings permanently to an expression instance. They apply to every subsequent `evaluate()` call.

```java
JsonataExpression expr = factory.compile("$round2($taxRate * subtotal)");

// Permanent value
ObjectMapper mapper = new ObjectMapper();
expr.assign("taxRate", mapper.readTree("0.2"));

// Permanent function
expr.registerFunction("round2", new JsonataBoundFunction() {
    @Override
    public String getFunctionSignature() { return "<n:n>"; }

    @Override
    public JsonNode apply(JsonataFunctionArguments args) {
        double v = args.get(0).doubleValue();
        return new DoubleNode(Math.round(v * 100.0) / 100.0);
    }
});

JsonNode r1 = expr.evaluate(mapper.readTree("{\"subtotal\": 100}"));  // → 20.0
JsonNode r2 = expr.evaluate(mapper.readTree("{\"subtotal\": 333}"));  // → 66.6
```

Permanent bindings are isolated per instance — assigning to one `JsonataExpression` does not affect any other.

### Precedence

When both a permanent binding and a per-evaluation binding exist for the same name, the **per-evaluation binding wins**.

### Implementing JsonataBoundFunction

`JsonataBoundFunction` has two methods:

| Method | Purpose |
|---|---|
| `String getFunctionSignature()` | Describes the expected argument types and return type (see signature syntax below) |
| `JsonNode apply(JsonataFunctionArguments args)` | Executes the function; may throw `JsonataEvaluationException` |

`JsonataFunctionArguments` wraps the argument list. Accessing an out-of-range index returns `MissingNode` rather than throwing.

### Function signature syntax

The signature has the form `<params:return>` where `params` is a sequence of type symbols and `return` is a single type symbol.

**Simple types**

| Symbol | Type |
|---|---|
| `b` | Boolean |
| `n` | number |
| `s` | string |
| `l` | null |

**Complex types**

| Symbol | Type |
|---|---|
| `a` | array |
| `o` | object |
| `j` | any JSON type — equivalent to `(bnsloa)` |
| `u` | Boolean, number, string, or null — equivalent to `(bnsl)` |
| `(sao)` | union: string, array, or object |

**Parametrised array types**: `a<s>` (array of strings), `a<x>` (array of any type).

**Option modifiers** appended to a type symbol:

| Modifier | Meaning |
|---|---|
| `+` | One or more arguments of this type (variadic) |
| `?` | Optional argument |
| `-` | Use the context value ("focus") if the argument is missing |

Example: `$length` has signature `<s-:n>` — accepts a string (using context as focus if omitted) and returns a number.

---

## Advanced usage

### Inspecting the source expression

```java
JsonataExpression expr = factory.compile("$sum(items.price)");
System.out.println(expr.getSourceJsonata());  // → "$sum(items.price)"
```

### Accessing the generated Java source

Use the lower-level API to obtain the generated source before compilation:

```java
import org.json_kula.jsonata_jvm.parser.Parser;
import org.json_kula.jsonata_jvm.optimizer.Optimizer;
import org.json_kula.jsonata_jvm.translator.Translator;
import org.json_kula.jsonata_jvm.parser.ast.AstNode;

AstNode ast = Optimizer.optimize(Parser.parse("price * qty"));
String javaSource = Translator.translate(ast, "com.example.gen", "PriceExpression", "price * qty");
System.out.println(javaSource);
```

### Loading a pre-generated Java class

If you have previously generated and saved a Java source string, compile it directly without re-parsing:

```java
import org.json_kula.jsonata_jvm.loader.JsonataExpressionLoader;

JsonataExpressionLoader loader = new JsonataExpressionLoader();
JsonataExpression expr = loader.load(javaSource);
```

---

## Performance

jsonata-jvm-compiler compiles expressions to native JVM bytecode, so repeated evaluation is significantly faster than interpreter-based alternatives.

### Benchmark: jsonata-jvm-compiler vs JSONata4Java

The benchmark compiles one expression once, then runs 100,000 evaluations against the same JSON document (with a 1,000-evaluation JVM warmup before timing). The expression is a realistic analytical query covering variable bindings, nested field navigation, array filtering, aggregation functions (`$sum`, `$count`, `$average`, `$max`, `$min`, `$distinct`), string operations, arithmetic, and a conditional.

Measured on OpenJDK 21 (Temurin 21.0.10), Windows 11:

| Metric | jsonata-jvm-compiler | JSONata4Java |
|---|---|---|
| Compilation | 1,519 ms | 292 ms |
| 100,000 evaluations | 4,514 ms | 84,246 ms |
| Throughput | **~22,150 eval/s** | ~1,190 eval/s |
| **Speedup** | **18.7× faster** | baseline |

> Compilation is a one-time cost paid at startup. For any workload that reuses an expression more than a handful of times, the throughput advantage dominates.

The benchmark is reproducible via:

```
mvn test -Dtest=PerformanceComparisonTest#benchmark_comparison_sideBy_side
```

---

## Thread safety

A `JsonataExpressionFactory` instance and all `JsonataExpression` instances it produces are fully thread-safe. `evaluate()` is stateless — each call processes the input JSON independently and returns a new `JsonNode` without modifying any shared state.

```java
// Compile once at startup
JsonataExpression totalPrice = factory.compile("$sum(items.(price * qty))");

// Call concurrently from any number of threads
ExecutorService pool = Executors.newFixedThreadPool(16);
pool.submit(() -> totalPrice.evaluate(requestJson));
```

---

## Architecture

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

### Package structure

| Package | Contents |
|---|---|
| `org.json_kula.jsonata_jvm` | Public API: `JsonataExpression`, `JsonataExpressionFactory`, `JsonataBindings`, `JsonataBoundFunction`, `JsonataFunctionArguments`, `JsonataCompilationException`, `JsonataEvaluationException` |
| `org.json_kula.jsonata_jvm.parser` | `Parser`, `ParseException` |
| `org.json_kula.jsonata_jvm.parser.lexer` | `Lexer`, `Token`, `TokenType` |
| `org.json_kula.jsonata_jvm.parser.ast` | `AstNode` sealed interface with all node types and `Visitor` |
| `org.json_kula.jsonata_jvm.optimizer` | `Optimizer` |
| `org.json_kula.jsonata_jvm.translator` | `Translator` |
| `org.json_kula.jsonata_jvm.runtime` | `JsonataRuntime` (static helper methods), `JsonataLambda` |
| `org.json_kula.jsonata_jvm.loader` | `JsonataExpressionLoader`, `JsonataLoadException` |

---

## License

This project is licensed under the [Apache License 2.0](https://github.com/vlad-public-code/JSonata2Java/blob/main/LICENSE).
