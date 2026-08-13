---
title: jsonata-jvm-compiler
---

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/vlad-public-code/JSonata2Java/blob/main/LICENSE)

A Java 21 library that compiles [JSONata](https://jsonata.org) expressions into native Java classes at runtime. Each expression is parsed, optimised, and translated to Java source, which is then compiled in-memory and returned as a ready-to-call `JsonataExpression` instance.
Repeated evaluation of a `JsonataExpression` instance is significantly faster than interpreter-based alternatives — **over 40× faster** than [JSONata4Java](https://github.com/IBM/JSONata4Java) on a realistic analytical benchmark.

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
    <version>1.1.0</version>
</dependency>
```

### 2. Compile an expression

```java
JsonataExpressionFactory factory = new JsonataExpressionFactory();
JsonataExpression expr = factory.compile("Account.Order.Product.Price * 1.2");
```

`compile()` runs the full pipeline once and returns a reusable, **thread-safe** object. Compile expressions at startup and reuse them for every request — do not call `compile()` on the hot path.

#### Compiling many expressions at once

When you need to compile a group of expressions up front (e.g. every derivation, constraint, and effect of a model at registration time), use `compileAll` instead of calling `compile` in a loop:

```java
List<JsonataExpression> exprs = factory.compileAll(List.of(
        "Account.Order.Product.Price * 1.2",
        "$sum(items.price)",
        "status = \"active\""));
```

`compileAll` returns one `JsonataExpression` per input, in order, and each behaves exactly as if produced by `compile`. The difference is cost: the pipeline runs the expensive `javac` step **once for the whole batch** rather than once per expression. That step is dominated by a fixed per-invocation overhead (compiler bootstrap, platform symbol loading, classpath indexing) that a single small generated class barely adds to, so batching many expressions is dramatically faster than compiling them one at a time — on the order of **10–16× for 20 expressions** in the project's own benchmark. Parsing and translation still happen per expression, so a syntactically invalid entry is reported with its index; any failure aborts the whole batch with a `JsonataCompilationException`.

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

## Function libraries

A **function library** turns a JSONata *definition expression* into ready-to-use `JsonataBoundFunction`s. The definition binds named functions and **returns the names of the ones to export** — it is ordinary JSONata, and evaluating it in any JSONata engine simply yields that list.

```java
JsonataExpressionFactory factory = new JsonataExpressionFactory();

Map<String, JsonataBoundFunction> trig = factory.compileFunctions("""
        (
          $pi := 3.1415926535897932384626;

          /* Factorial is the product of the integers 1..n */
          $product := function($a, $b) { $a * $b };
          $factorial := function($n) { $n = 0 ? 1 : $reduce([1..$n], $product) };

          $sin := function($x){ $cos($x - $pi/2) };
          $cos := function($x){
            $x > $pi ? $cos($x - 2 * $pi) : $x < -$pi ? $cos($x + 2 * $pi) :
              $sum([0..12].($power(-1, $) * $power($x, 2*$) / $factorial(2*$)))
          };

          ["sin", "cos"]
        )
        """);

JsonataExpression expr = factory.compile("angles.$sin($)");
trig.forEach(expr::registerFunction);        // or: new JsonataBindings().bindFunctions(trig)

expr.evaluate(mapper.readTree("{\"angles\": [0, 1, 2]}"));
// → [0, 0.8414709848078965, 0.9092974268256817]
```

The definition is compiled and evaluated **once**, inside `compileFunctions`. What matters is what it bound and what it named:

- **Only the names in the export list are exported.** `$pi`, `$product` and `$factorial` stay internal, yet the exported functions still reach them — helpers do not have to be exported to be usable.
- **The export list lives with the functions**, not at the call site, so a definition file says what it provides and stays in step with itself when a function is renamed.
- **Mutual recursion works**: `$sin` calls `$cos`, which calls itself and `$factorial`.
- **Names may be written with or without the `$`.** Map keys never carry it, so the map drops straight into `registerFunction` or `bindFunction`. The map preserves the order of the export list.
- **Any lambda shape works**: multi-parameter, recursive, tail-recursive, `λ`, a function returned by another function (`$twice($add3)`), a `~>` chain (`$uppercase ~> $trim`), or a partial application (`$substring(?, 0, 5)`).
- **The export list is an expression** like any other — `["sin", "cos"]` is the usual form, a single `"sin"` works, and so does a list computed at definition time.

Exported functions can also be called straight from Java, with no expression involved:

```java
JsonataBoundFunction sin = trig.get("sin");
JsonNode result = sin.apply(new JsonataFunctionArguments(List.of(DoubleNode.valueOf(1.0))));
```

> A definition that ends on its last `$name := function…` binding returns *that function* rather than a list of names, and is rejected with `must return an array of function names`. Finish it with the export list.

### Signatures

Each exported function reports a JSONata signature:

| Definition | Reported signature |
|---|---|
| `$twice := function($x)<n:n>{ $x * 2 }` | `<n:n>` — the declared one |
| `$volume := function($l, $w, $h){ ... }` | `<j?j?j?:j>` — synthesised, all-optional |
| `$normalize := $uppercase ~> $trim` | none — arity known only at call time |

The synthesised form is deliberately permissive: JSONata lets a lambda be called with fewer arguments than it declares (the rest are *undefined*), and `j` applies no coercion — so an exported function accepts exactly what the same function accepts inside JSONata. Ask for something stricter with a signature override:

```java
JsonataFunctionLibrary lib = factory.compileFunctionLibrary(
        "($twice := function($x){ $x * 2 }; [\"twice\"])",
        new JsonataFunctionLibraryOptions().signature("$twice", "<n:n>"));

// "<n:n>" coerces at the boundary: $twice("21") → 42
```

### Lifetime and options

`compileFunctions` returns just the map; the underlying library stays alive as long as the functions are referenced. Use `compileFunctionLibrary` when the lifetime should be explicit — `JsonataFunctionLibrary` is `AutoCloseable`, and `close()` releases the compiled functions (calling them afterwards throws `JsonataEvaluationException`).

`JsonataFunctionLibraryOptions` also carries the document the definition is evaluated against (`input`) and the bindings visible while it runs (`bindings`).

Two semantics worth knowing:

- **Bound names are captured; free names are late-bound.** A name the definition binds (`$pi`) is baked into the closure. A name it never binds (`$vatRate`) resolves against the bindings active where the function is *called* — or against the library's own `bindings` option when it is called directly from Java.
- **The caller's evaluation is reused.** Called from inside an expression, an exported function shares that evaluation's recursion budget (100 nested calls) and its `setTimeout` deadline.

A library owns one generated class, so build it once at startup and keep it, exactly as with `compile()`. Exported functions are thread-safe and may be called concurrently.

---

## Advanced usage

### Evaluation timeout

Call `setTimeout(int timeoutMs)` on an expression instance to cap how long a single `evaluate()` call may run. If the deadline is exceeded, a `JsonataEvaluationException` with error code `U1001` is thrown.

```java
JsonataExpression expr = factory.compile("...");
expr.setTimeout(500);   // 500 ms wall-clock limit per evaluate() call

try {
    JsonNode result = expr.evaluate(input);
} catch (JsonataEvaluationException e) {
    if ("U1001".equals(e.getErrorCode())) {
        // evaluation exceeded 500 ms
    }
}
```

Pass `0` or a negative value to remove the timeout. The timeout applies to all future `evaluate()` calls on the instance; concurrent calls on the same instance each track their own independent deadline. Setting a timeout has no measurable overhead on evaluations that complete before the deadline.

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

### Benchmark: [jsonata-jvm-compiler](https://vlad-public-code.github.io/org.json-kula.jsonata-jvm-compiler/) vs [JSONata4Java](https://github.com/IBM/JSONata4Java)

The benchmark compiles one expression once, then runs 100,000 evaluations against the same JSON document (with a 1,000-evaluation JVM warmup before timing). The expression is a realistic analytical query covering variable bindings, nested field navigation, array filtering, aggregation functions (`$sum`, `$count`, `$average`, `$max`, `$min`, `$distinct`), string operations, arithmetic, and a conditional.

Measured on OpenJDK 21 (Temurin 21.0.10), Windows 11:

| Metric | [jsonata-jvm-compiler](https://vlad-public-code.github.io/org.json-kula.jsonata-jvm-compiler/) | [JSONata4Java](https://github.com/IBM/JSONata4Java) |
|---|---|---|
| Compilation | ~760 ms | ~145 ms |
| 100,000 evaluations | ~1,000 ms | ~41,600 ms |
| Throughput | **~100,000 eval/s** | ~2,400 eval/s |
| **Speedup** | **~41× faster** | baseline |

> Compilation is a one-time cost paid at startup. For any workload that reuses an expression more than a handful of times, the throughput advantage dominates. Compiling several expressions? Use `compileAll` — one `javac` invocation for the batch is around 35× faster than one per expression.

Where the speed comes from, beyond compiling to bytecode: literal values are hoisted to static fields rather than rebuilt inside every loop; object constructors with literal keys build an exactly-sized map in one pass; common aggregate shapes (`$count(x[field = "value"])`, `$sum(x.field)`) are fused into a single loop with no intermediate sequence; and the runtime hot type checks are single dispatches rather than chains of megamorphic calls.

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

## See also

- [tracked-json](https://vlad-public-code.github.io/org.json-kula.tracked-json/) — Jackson JsonNode wrapper that tracks each node's location (JsonPointer) and document root through every navigation — get, path, at, parent(), and JSONPath (RFC 9535). Includes JSON Patch (RFC 6902).
- [Valem](https://vlad-public-code.github.io/org.json-kula.valem/) — deterministic reactive computation runtime for AI-generated structured data models.
- [Valem Sandbox](https://valem.run/)
