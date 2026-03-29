---
name: jackson-jsonata-expert
description: >
  Expert guidance for senior Java developers working on the JSonata2Java project — a compiler that translates JSONata expressions into Java 21 classes backed by Jackson JsonNode. Use this skill whenever the user is working inside the JSonata2Java project and the task involves: Jackson API usage (JsonNode hierarchy, ObjectMapper, NullNode vs MissingNode, ArrayNode/ObjectNode manipulation, streaming, annotations); JSONata language semantics (sequence mapping, context binding, path navigation, predicates, built-in functions, lambda expressions, operators, group-by, transform, chain); runtime behaviour of JsonataRuntime; translator code generation patterns; optimizer rewrites; or any cross-cutting design decisions in this codebase. Also trigger when the user asks to implement a new JSONata feature, fix a runtime bug, review generated code correctness, or understand how JSONata semantics map to Java/Jackson constructs. If the user is touching any of the packages — parser, optimizer, translator, runtime, loader — use this skill.
---

# Jackson + JSONata Expert — JSonata2Java

You are acting as a senior Java 21 developer with deep expertise in both FasterXML Jackson and the JSONata language. Your primary context is the **JSonata2Java** project: a compiler that parses JSONata expressions, optimises the AST, translates it to Java 21 source, and executes it via Jackson `JsonNode`.

---

## Project at a glance

```
JSONata string
  → Lexer/Parser   → AstNode (sealed interface, 24 node types)
  → Optimizer      → AstNode (constant-folded, simplified)
  → Translator     → Java 21 source string
  → Loader         → JsonataExpression instance (in-memory javac)
  → evaluate(json) → JsonNode result
```

Key packages:
- `org.json_kula.jsonata_jvm.parser`       — recursive-descent parser + hand-written lexer
- `org.json_kula.jsonata_jvm.optimizer`    — single-pass bottom-up AST rewriter
- `org.json_kula.jsonata_jvm.translator`   — visitor-based code generator
- `org.json_kula.jsonata_jvm.runtime`      — `JsonataRuntime` static helpers + `JsonataLambda`
- `org.json_kula.jsonata_jvm.loader`       — in-memory javax.tools compilation

---

## Jackson fundamentals for this project

### The JsonNode type hierarchy you'll use every day

```
JsonNode (abstract)
├── ValueNode
│   ├── TextNode         — string values
│   ├── IntNode / LongNode / DoubleNode / BigDecimalNode — numbers
│   ├── BooleanNode      — true / false singletons
│   ├── NullNode         — singleton: NullNode.getInstance()
│   └── MissingNode      — singleton: MissingNode.getInstance()
├── ContainerNode
│   ├── ArrayNode        — ordered, mutable list of JsonNode
│   └── ObjectNode       — mutable string→JsonNode map
```

### Creating nodes — always use the factory

```java
// Prefer JsonNodeFactory (aliased as NF in JsonataRuntime):
private static final JsonNodeFactory NF = JsonNodeFactory.instance;

NF.textNode("hello")          // TextNode
NF.numberNode(42L)            // LongNode
NF.booleanNode(true)          // BooleanNode
NF.nullNode()                 // NullNode (same as NullNode.getInstance())
NF.arrayNode()                // empty mutable ArrayNode
NF.objectNode()               // empty mutable ObjectNode
```

Do NOT use `new TextNode(...)` directly — always go through the factory or the runtime helpers `text()`, `number()`, `bool()`.

### NullNode vs MissingNode — the most important distinction in this project

| | `NullNode` | `MissingNode` |
|---|---|---|
| Meaning | Explicit JSON `null` | Absent / undefined (JSONata "nothing") |
| `node.isNull()` | `true` | `false` |
| `node.isMissingNode()` | `false` | `true` |
| `node.asText()` | `"null"` | `""` |
| Serialised | `null` in JSON | omitted from JSON |
| In this project | `JsonataRuntime.NULL` | `JsonataRuntime.MISSING` |

**Rule:** Inside the runtime and translator, propagate `MISSING` when a value is absent. Only convert MISSING→NULL at the `evaluate()` boundary (the generated class does this automatically).

### Mutating ArrayNode and ObjectNode

```java
ArrayNode arr = NF.arrayNode();
arr.add(someNode);              // append
arr.set(0, anotherNode);        // replace by index
arr.remove(0);                  // remove by index

ObjectNode obj = NF.objectNode();
obj.set("key", someNode);       // put/replace
obj.remove("key");
obj.has("key");                 // boolean
obj.get("key");                 // returns MissingNode if absent — NOT null
```

**Pitfall:** `obj.get("missing")` returns `MissingNode`, not Java `null`. Always check `node.isMissingNode()` rather than `node == null`.

### Iterating containers

```java
// Array — both work:
for (JsonNode elem : arrayNode) { ... }
arrayNode.forEach(elem -> ...);

// Object — fields:
Iterator<Map.Entry<String, JsonNode>> it = objectNode.fields();
while (it.hasNext()) {
    Map.Entry<String, JsonNode> e = it.next();
    String key = e.getKey();
    JsonNode val = e.getValue();
}
// Or:
objectNode.fieldNames()   // Iterator<String>
objectNode.elements()     // Iterator<JsonNode> (values only)
```

### ObjectMapper — parsing and serialising

```java
// In generated classes — one static instance, thread-safe:
private static final ObjectMapper __MAPPER = new ObjectMapper();

JsonNode root = __MAPPER.readTree(jsonString);   // parse
String json   = __MAPPER.writeValueAsString(node); // serialise
```

`ObjectMapper` is thread-safe for `readTree` and `writeValueAsString` after construction. Never reconfigure it after sharing.

---

## JSONata semantics — how they map to this codebase

### Sequence / array auto-mapping

JSONata automatically maps operations over arrays. The runtime implements this via `field()`:

```java
// If node is an array, map field() over each element and collect results:
if (node.isArray()) {
    ArrayNode result = NF.arrayNode();
    for (JsonNode elem : node) {
        JsonNode val = field(elem, name);
        if (!val.isMissingNode()) appendToSequence(result, val);
    }
    return unwrap(result);
}
```

`appendToSequence` flattens **one level**: if `val` is itself an array its elements are added individually (prevents double-nesting). This matches JSONata's "sequences are flat" rule.

`unwrap` applies singleton collapsing:
- empty array → `MISSING`
- one element → that element (unwrapped)
- many elements → the array

### Context and root variables

Every generated method receives two context variables:

| Variable | Meaning |
|---|---|
| `__root` | The full input document — never changes |
| `__ctx` | Current context — rebinds at each path step |

When the translator emits a predicate or lambda it introduces a fresh element variable (`__el0`, `__el1`, …) so nested predicates don't shadow each other.

### Truthy coercion

`isTruthy(node)` returns `false` for: `false`, `null`, MISSING, `0`, `""`, empty array, empty object. Everything else is truthy. Use this whenever you need to convert a `JsonNode` to a boolean decision.

### Missing propagation through arithmetic

Any arithmetic or string operation with a MISSING operand returns MISSING immediately — it does not throw. Comparisons return `false` (not MISSING) when either operand is MISSING. This mirrors JSONata's "undefined silently propagates" contract.

### Null vs missing in comparisons

`null = null` is `true` in JSONata. `MISSING = anything` is `false`. The runtime handles this in `eq()` by testing `missing()` before doing the actual equality check.

### Built-in functions — naming convention in the runtime

JSONata's `$functionName` maps to a static method named `fn_functionName` in `JsonataRuntime`:

```
$string($x)    → fn_string(x)
$number($x)    → fn_number(x)
$length($x)    → fn_length(x)
$sum($x)       → fn_sum(x)
$map($a, $fn)  → fn_map(a, fn)
$filter($a,$fn)→ fn_filter(a, fn)
$reduce($a,$fn,$init) → fn_reduce(a, fn, init)
$each($o, $fn) → fn_each(o, fn)
$sort($a, $fn) → fn_sort(a, fn)
```

### The chain operator `~>`

`a ~> $f ~> $g` is equivalent to `$g($f(a))`. The runtime supports passing lambdas as values by registering them in `LAMBDA_REGISTRY` and wrapping the key in a `TextNode` with the sentinel prefix `"__λ:"`. When `fn_apply` receives a node it checks `isLambdaToken(fn)` to distinguish a real string value from a lambda token.

---

## Translator code generation patterns

### How an expression is emitted

The translator walks the AST and produces a Java expression string (not a statement). Each node returns a `String` that, when evaluated, produces a `JsonNode`. The context is threaded via `GenCtx` (immutable, carries `ctxVar`, `rootVar`, plus a mutable `state` for helpers and unique counters).

### Blocks and variable bindings → helper methods

When a `Block` contains `VariableBinding` nodes, the translator emits a private `__blockN(JsonNode __root, JsonNode __ctx)` method and calls it from the main expression. This keeps the main `evaluate()` body as a single expression.

### Lambdas

- **Single-param lambdas** used as predicates or map/filter callbacks are emitted inline as Java lambda expressions: `__el -> body_expr`.
- **Multi-param lambdas** (e.g. `$reduce` accumulators) are emitted as private `__lambdaN` methods and referenced via `this::__lambdaN`.

### Group-by

Emitted as a private `__groupByN` method. It iterates the source sequence, computes keys, and builds an `ObjectNode`. If multiple items share the same key their values are merged into an `ArrayNode`.

---

## Common implementation patterns

### Adding a new runtime function

1. Add a `public static JsonNode fn_yourFunction(JsonNode ...)` method to `JsonataRuntime`.
2. Guard for MISSING/null inputs at the top and return MISSING or throw `JsonataEvaluationException` as appropriate.
3. Add the call-site mapping in `Translator` (inside `visitFunctionCall`, matching the JSONata name string to the runtime method name).
4. Add a test in the integration test suite.

### Adding a new AST node

1. Add a `record YourNode(...) implements AstNode` inside `AstNode`.
2. Add `YourNode` to the `permits` clause of the sealed interface.
3. Add `visitYourNode` to the `Visitor` interface.
4. Update the `accept` switch expression.
5. Handle in `Parser`, `Optimizer`, and `Translator`.

### Debugging generated code

The generated source string is available at runtime via `__SOURCE` (a private static field in the generated class). You can also call `Translator.translate(ast, pkg, className)` directly and print the result to inspect what code is being produced before compilation.

---

## Key things to keep in mind

- **MISSING ≠ null** — always distinguish them; accidentally returning Java `null` from a runtime method will cause NPEs in generated code.
- **appendToSequence, not arr.add** — use `appendToSequence` when building result arrays to maintain flat-sequence semantics.
- **unwrap results** — path navigation results should be `unwrap`-ed before returning so callers get single-value collapsing.
- **Thread safety** — generated classes are stateless; the lambda registry uses `ConcurrentHashMap` + `AtomicLong`; all new runtime state must follow the same pattern.
- **Generated code is expression-based** — avoid emitting statements; if you need to, emit a helper method instead.