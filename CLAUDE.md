This project is a library written in Java 21.
It translates a given JSONata expression to Java 21 class containing a method to run the expression for given string.

Stack:
- Java 21
- Jackson to operate with JSON.

# Project structure
- Parser which consumes input string containing JSONata expression and generates AST. Implemented in package `org.json_kula.jsonata_jvm.parser`:
  - `Parser` — recursive-descent parser; entry point is `Parser.parse(String expression)`.
    Several JSONata rules are *positional* and can only be decided here, because the optimizer sees
    a tree in which the position is gone: which node a `[...]` lands on (folded onto a path's last
    step as a per-element stage, versus applied to a whole value — `PredicateExpr.stage` records
    which), whether a trailing `[]` has a sequence to keep (`producesSequence`), whether a group-by
    or a postfix operator sits on a path (`isPathLike`), and whether a literal is being used as a
    path step (`isLiteralStep`). The parentheses in `(a.b)[]` and `nums.([1,2])` carry meaning for
    the same reason, so the optimizer preserves the ones that do.
  - `ParseException` — checked exception carrying the source position of the error.
  - `org.json_kula.jsonata_jvm.parser.lexer.Lexer` — hand-written tokenizer; entry point is `Lexer.tokenize(String source)`.
  - `org.json_kula.jsonata_jvm.parser.lexer.Token` — record(type, value, position).
  - `org.json_kula.jsonata_jvm.parser.lexer.TokenType` — enum of all JSONata token kinds.
  - `org.json_kula.jsonata_jvm.parser.ast.AstNode` — sealed interface with all AST node types as nested records plus a two-parameter `Visitor<R,C>` interface and a default `accept(Visitor, C)` dispatch method.
- Optimizer to optimize AST. Implemented in package `org.json_kula.jsonata_jvm.optimizer`:
  - `Optimizer` — single-pass, bottom-up tree rewriter; entry point is `Optimizer.optimize(AstNode)`.
  - Rewrites applied: constant folding (arithmetic, string concatenation, comparisons, boolean logic), arithmetic identity/absorption rules (`x+0`, `x*1`, `x*0`, etc.), string identity (`x & ""`), boolean short-circuit identities, conditional folding on literal conditions, unary-minus elimination (including double negation), block unwrapping (single-expression blocks), and `PathExpr` flattening.
  - The `Parenthesized` wrapper is stripped, *except* around an array constructor or a path headed
    by one. There the parentheses are load-bearing: only a bare `[...]` step is a constructor value,
    so `nums.([1,2])` is `[1,2,1,2,1,2]` where `nums.[1,2]` is three arrays, and `([]).x` is not the
    head short-circuit that `[].x` is.
- Translator which generates Java 21 code by AST. Implemented in package `org.json_kula.jsonata_jvm.translator`:
  - `Translator` — visitor-based code generator; entry point is `Translator.translate(AstNode, String pkg, String className)` returning a complete Java source string.
  - `PathCodeGen` — path expressions: step chains, predicates, context (`@$v`) and positional (`#$i`) bindings, parent (`%`) tracking and cross-joins. Split out of `Translator` because the code emitted for one step depends on what later steps do, and that reasoning belongs in one place.
  - `FunctionCallCodeGen`, `BlockCodeGen`, `ScopeAnalyzer` — function calls and lambdas, blocks and variable bindings, and the free-variable/holder analysis they share.
  - `SequenceScanFusion` — collapses the several operations a block performs over one sequence
    (`$sum($e.salary)`, `$max($e.salary)`, `$count($e[level = "senior"])`, `$e[level = "lead"]`, …)
    into a single pass that reads each distinct field once per element rather than once per
    operation. Planned per block before any statement is compiled; absorbed operations are
    redirected to the scan's result slots through a node-identity memo on `GenState`. The fused loop
    is emitted as its own `private static` helper — inlining it into the block method measured
    *slower* than not fusing, because the block method grows past what C2 compiles well. Predicates
    are pattern-matched rather than compiled as callbacks (`=`, `!=`, `and`, `or` anywhere in the
    tree; `<`, `<=`, `>`, `>=` as a whole predicate), because sharing the reads is the optimisation
    and an opaque callback re-reads inside itself what the loop just shared. An operation that can
    fail records its first offending value and carries on; the throw happens in `aggRead` /
    `cmpRead` at the statement that reads the result, so the error is ordered exactly against the
    statements the scan did *not* absorb as well as against the ones it did.
  - Literal nodes and object-constructor key arrays are hoisted to `private static final` fields of the generated class (`GenState.constant` / `GenState.keyArray`), so a predicate does not rebuild them per element per evaluation.
  - All AST node types are handled: literals, field/path/wildcard/descendant navigation, predicates, subscripts, all binary and unary operators, conditionals, function calls (built-ins + user-defined), lambdas, variable bindings, blocks, array/object constructors, range, sort, group-by, chain (`~>`), transform.
  - Blocks with variable bindings are emitted as private helper methods; lambdas are either inlined or also emitted as helper methods.
  - Generated classes import `static org.json_kula.jsonata_jvm.runtime.JsonataRuntime.*` and use the runtime for all JSONata operations.
- Runtime support library implemented in package `org.json_kula.jsonata_jvm.runtime`:
  - `JsonataRuntime` — all static helper methods used by generated classes: field navigation with sequence mapping, filter/subscript, arithmetic, string concat, comparisons, boolean logic, array/object constructors, range, sort/reverse/distinct/flatten/map/filter/reduce/each, string functions, numeric functions, date/time, error, and chain-operator support via a lambda registry.
  - `ConstructedObjectMap` — backing store for objects built by a constructor with literal keys.
    The translator proves the keys distinct at compile time and emits `objectOfDistinct`, so the
    object is filled into two parallel arrays with no hashing and no duplicate check, rather than
    through a `LinkedHashMap`. Reads scan below 8 fields and build a hash index on the first read
    above it — on the read, not the write. Duplicated literal keys keep the checking `objectOf`,
    which still raises D1009.
  - `LambdaNode` / `RegexNode` — function and regex values, carried directly as `JsonNode`s. They are
    node types rather than specially-prefixed strings, so no input document can be mistaken for one,
    and a function value stays callable for as long as something references it (there is no registry
    and no expiry). `PreservedNode` is the internal "do not flatten this element" marker used by
    nested array constructors. `BoundFunctionValue` adapts a `JsonataBoundFunction` to a function
    value and back — the mirror of `ExportedJsonataFunction`, which adapts a function value to a
    `JsonataBoundFunction`.
  - `MarkedArrayNode` — an `ArrayNode` carrying one of the two marks JSONata puts on a value rather
    than on an expression. **cons**: the array a bare `[...]` path step built. It is a value that
    happens to be an array, so it neither flattens into the sequence around it nor collapses when
    alone in one — `nums.[1,2]` is three arrays, `a.[1]` is `[1]`. **keepSingleton**: what `[]`
    leaves behind, which stops the collapse (but not the flattening) and outlives further steps and
    a sort. The marks have to live on the *value*: every bare `[...]` step is flagged wherever it
    sits, and the mark then travels with the array — `a.[1].$[]` is `[[1]]`. Read in exactly three
    places, matching the reference: the step flatten loop (`appendStepResult`), the singleton
    collapse (`unwrap`), and the promotion `[]` performs (`forceArray`). Static rules on the AST
    shape are each an approximation of a dynamic fact and always need another case; a sibling port
    measured that approach stalling at 285 divergences on a path sweep where the marker reached 0.
  - Path steps that look like no-ops but are not: `contextStep` (`$`) and `wildcardStep` (a `*`
    after another step) exist because the reference runs *every* step once per input item. A `$`
    step flattens what it walks and collapses what it collects (`[1].$` is 1); a `*` after a step
    sees one element at a time and a scalar contributes nothing (`nums.*` is undefined), while a
    `*` at the head of a path enumerates its context (`*` over `[1,2,3]` is `[1,2,3]`).
  - `JsonataLambda` — single-argument functional interface (`JsonNode apply(JsonNode) throws JsonataEvaluationException`) used for predicates, map/filter callbacks, and inline lambdas.
  - `org.json_kula.jsonata_jvm.runtime.datetime` — date/time subsystem (`IsoConverter`, `TimezoneUtils`, `RomanNumerals`, `WordNumbers`, `PictureFormatter`, `PictureParser`); `DateTimeUtils` in `runtime` is a thin facade over this package. See [docs/datetime.md](docs/datetime.md) for the full reference.
  - `org.json_kula.jsonata_jvm.runtime.numeric` — numeric built-ins (`NumericBuiltins`, `DecimalPicture`, `IntegerPicture`, `EnglishWords`); `JsonataRuntime` delegates to `NumericBuiltins` for all `$number`, `$round`, `$random`, `$formatBase`, `$formatNumber`, `$formatInteger`, `$parseInteger` calls. See [docs/numeric.md](docs/numeric.md) for the full reference.
  - `org.json_kula.jsonata_jvm.runtime.string` — string built-ins (`StringBuiltins`, `RegexOps`, `UrlCodec`); `JsonataRuntime` delegates to `StringBuiltins` for all `$length`, `$substring*`, `$uppercase`, `$lowercase`, `$trim`, `$contains`, `$split`, `$join`, `$match`, `$replace`, `$pad`, `$eval`, `$base64encode`, `$base64decode`, `$encodeUrl*`, `$decodeUrl*` calls. See [docs/string.md](docs/string.md) for the full reference.
- Class loader which consumes input string containing text of Java21 class implementing `org.json_kula.jsonata_jvm.JsonataExpression` and returns instance of `org.json_kula.jsonata_jvm.JsonataExpression`. Implemented in package `org.json_kula.jsonata_jvm.loader` (`JsonataExpressionLoader`, `JsonataLoadException`).

Generated classes:
- All classes generated by the translator implement `org.json_kula.jsonata_jvm.JsonataExpression`.
- `JsonataExpression.evaluate(JsonNode input)` takes a JsonNode and returns a Jackson `JsonNode`.
- Evaluation failures (invalid JSON input or runtime errors) are reported via `JsonataEvaluationException`.
- Instances of `org.json_kula.jsonata_jvm.JsonataExpression` should be thread-safe.

# Bindings
The library implements functionality of bindings to extend the API. A client code can bind:
1. A value. It's an immutable instance of JsonNode.
2. A function. In this library you cannot bind a JavaScript function, but you can bind a Java class implementing 
interface org.json_kula.jsonata_jvm.JsonataBoundFunction.

There are two options how to bind values and functions.
1. Per an execution of JsonataExpression.evaluate().
2. Permanently for an instance of JsonataExpression.

To bind values and functions per an execution use a method 
org.json_kula.jsonata_jvm.JsonataExpression.evaluate(JsonNode, JsonataBindings) and pass the bindings in the second argument.
JsonataBindings is a class containing named values and named functions.
Named values is a map where key is a string and value is JsonNode.
Named functions is a map where key is a string and value is instance of JsonataBoundFunction.

To bind a value permanently use a method JsonataExpression.assign(String name, JsonNode value).
To bind a function permanently use a method JsonataExpression.registerFunction(String name, JsonataBoundFunction fnc).

Functions cross the binding boundary in both directions, as call targets and as values:
- A bound function referenced as `$name` (no call) resolves to a function value — `EvaluationContext.resolveBinding`
  falls back to the functions map and wraps the entry via `BoundFunctionValue.of`. That is what lets a bound
  function, and therefore a JsonataLibrary export, be passed to `$map`, piped through `~>`, or handed to another
  bound function.
- A bound *value* that is a function (`JsonataRuntime.lambdaNode(...)` passed to `bindValue`) is callable as
  `$name(...)` — `EvaluationContext.callBoundFunction` falls back to the values map.
- Whichever map matches the position wins: values for `$name`, functions for `$name(...)`.
- The arity of a bound function used as a value comes from its signature's parameter count
  (`FunctionSignature.arityOf`); a signature that leaves it open — absent, unparseable or variadic — yields a
  one-argument function value, because a packed argument tuple is indistinguishable from a single array argument.

Interface JsonataBoundFunction contains two methods:
1. getFunctionSignature() which returns a string describing arguments types.
2. apply(JsonataFunctionArguments) which returns JsonNode representing a result of the function.

Function signature syntax
A function signature is a string of the form <params:return>. params is a sequence of type symbols, each one representing an input argument's type. return is a single type symbol representing the return value type.
Type symbols work as follows:
Simple types:
b - Boolean
n - number
s - string
l - null

Complex types:
a - array
o - object
f - function; an argument that is not a function value is rejected with T0410

Union types:
`(sao)` - string, array or object
`(o)` - same as `o`
`u` - equivalent to `(bnsl)` i.e. Boolean, number, string or null
`j` - any JSON type. Equivalent to `(bnsloa)` i.e. Boolean, number, string, null, object or array. The spec
excludes functions from `j`; this library does not enforce that — declare `f` where a function is required.
`x` - any type at all, equivalent to `(bnsloaf)`; accepted without any check

Parametrised types:
* `a<s>` - array of strings
* `a<x>` - array of values of any type
* `f<n:n>` - a function from number to number. The argument must be a function; its own parameter and
  return types are not checked (jsonata-js does not check them either).

Each type symbol may also have options applied.
`+` - one or more arguments of this type. E.g. $zip has signature `<a+>`; it accepts one array, or two arrays, or three arrays, or...
`?` - optional argument. E.g. $join has signature `<a<s>s?:s>`; it accepts an array of strings and an optional joiner string which defaults to the empty string. It returns a string.
`-` - if this argument is missing, use the context value ("focus"). E.g. $length has signature `<s-:n>`; it can be called as $length(OrderID) (one argument) but equivalently as OrderID.$length().

Class JsonataFunctionArguments represents a list of JsonNode.

# JSONata libraries
Besides implementing JsonataBoundFunction in Java, a bound function can be written in JSONata itself.
`JsonataExpressionFactory.compileLibrary(String definition)` takes a definition expression and returns
a `JsonataLibrary` with `getFunctions()` (`Map<String, JsonataBoundFunction>`) and `getConstants()`
(`Map<String, JsonNode>`), both keyed by name without the leading `$`. Each exported name lands in one
map or the other according to what it evaluated to. `useLibrary` applies both maps at once — either
permanently on the expression, `expr.useLibrary(lib)`, or per evaluation,
`new JsonataBindings().useLibrary(lib)`. 

A definition expression must be a valid JSONata expression that binds named functions and returns an
array of strings — the names of the functions to export. There is no name list on the Java side: the
definition is self-describing and evaluates in any JSONata engine, where its result is that array.
A single string is accepted as a one-element list, and names may carry the leading `$` or not.

Only the names in the export list are exported; other bindings in the definition (helper values and
helper functions) stay internal but remain reachable from the exported closures, and mutual recursion
between exported functions works. Constants are values, not expressions: the definition runs once, at
compile time.

A definition must be self-contained. Every name it uses must be one it binds, a JSONata built-in, or
one supplied through `JsonataLibraryOptions.bindings`; anything else fails the build
(`FunctionExportRewriter.requireSelfContained`, using `ScopeAnalyzer.freeVariables`). Late-binding a
free name to the calling expression would make a library depend on its caller and hide typos.

How it works:
1. `FunctionExportRewriter` binds the definition's last expression (the export list) to a synthetic
   `$__exportNames` variable and appends `{"names": $__exportNames, "functions": {...every top-level
   binding...}}` to the outermost block. Collecting all bindings is what keeps this to one
   compilation: which of them are wanted is only known once the export list has been evaluated.
   The rewriter also recovers each exported function's arity and signature from the AST.
2. The rewritten expression is optimised, translated and loaded exactly like any other expression.
3. It is evaluated once with a `LambdaScope` installed (`AbstractJsonataExpression.evaluateDefining`).
   While a scope is installed, `LambdaRegistry.lambdaNode` mints scope-qualified token keys
   (`__λ:<scopeId>/<n>`) into that durable scope instead of the per-evaluation map, so the functions
   stay callable after the defining evaluation ends — on any thread, inside any evaluation, or none.
4. Each exported function value is wrapped in an `ExportedJsonataFunction`, which applies the runtime calling
   convention (no arg → `null`, one arg → passed through, several → `packArgs` tuple).

`JsonataLibrary` owns the generated class; `close()` retires the exported functions. See
[docs/design/function-library.md](docs/design/function-library.md) for the full design and the
alternatives that were rejected.