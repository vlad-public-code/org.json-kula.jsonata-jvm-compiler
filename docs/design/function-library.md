# Design: building a `Map<String, JsonataBoundFunction>` from a JSONata definition expression

Status: implemented (v1.0.5) — see `JsonataExpressionFactory.compileLibrary`
Date: 2026-08-13
Affects: `org.json_kula.jsonata_jvm` (public API), `…runtime` (lambda registry, evaluation context)

---

## 1. Goal

Let a caller turn a JSONata *definition expression* — an expression that binds named lambdas and
returns the names of the ones to export — into Java-callable `JsonataBoundFunction` objects:

```java
JsonataExpressionFactory factory = new JsonataExpressionFactory();

Map<String, JsonataBoundFunction> trig = factory.compileLibrary("""
        (
          $pi := 3.1415926535897932384626;
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

// Drops straight into the existing bindings API:
JsonataExpression report = factory.compile("payload.angles.$sin($)");
trig.forEach(report::registerFunction);
```

**The definition is ordinary JSONata and nothing else.** Evaluated in any JSONata engine it returns
`["sin", "cos"]` — the export list is the expression's own result, not an out-of-band parameter and
not a dialect extension. This is the constraint that shapes the whole design: a definition file can
be linted, tested, and evaluated by tools that know nothing about this library.

Helper bindings (`$pi`, `$product`, `$factorial`) are *not* exported but must stay reachable from
the exported ones.

### Non-goals

* No new JSONata syntax. The definition expression is plain JSONata, parsed by the existing parser,
  and valid input to any other JSONata implementation.
* No sharing of *values* (`$pi`) as bindings. Only functions are exported.
* No cross-JVM / serializable function handles.

---

## 2. Background: how user-defined functions work today

Three mechanisms in the current codebase matter here.

**Lambdas are tokens, not nodes.** A `function(){…}` value is emitted by the translator as
`lambdaNode(<java lambda>)`, which stores a `JsonataLambda` in a map and returns a `TextNode`
carrying the sentinel key `"__λ:<n>"` (`LambdaRegistry.lambdaNode`). Calling it is
`fn_apply(token, arg)`, which looks the key back up.

**The calling convention is single-argument.** `JsonataLambda.apply(JsonNode)` takes one node.
Generated call sites pass `NULL` for a zero-arg call, the argument directly for a one-arg call, and
`packArgs(a, b, …)` — a non-flattening `ArrayNode` — for multi-arg calls
(`FunctionCallCodeGen`, lines 36–43). The lambda body unpacks that tuple positionally
(`buildInlineLambda`, lines 397–424).

**Lambda identity is scoped to a single `evaluate()` call.** `lambdaNode` writes into the
thread-local `EvalState.evalLambdas`, and `EvalState.end()` clears that map in the `finally` block of
`AbstractJsonataExpression.evaluate`. The static `LAMBDA_REGISTRY` is only a fallback used when no
evaluation is active, and it is a bounded (100-entry) LRU.

Co-recursion between `$sin` and `$cos` already works *inside* one evaluation: `BlockCodeGen`
pre-declares a `JsonNode[] $cosRef = {MISSING}` holder for every forward-referenced or
self-referential binding, and the `$sin` closure captures the holder array
(`ScopeAnalyzer.computeHolderNeeded`). So the closure graph we want to export is built correctly
already — we only need to get it *out* and keep it *alive*.

---

## 3. Problem statement

Three gaps separate "the definition expression evaluates fine" from "here is a
`Map<String, JsonataBoundFunction>`".

**G1 — no export path.** The value of the block is its last expression, and that expression is the
export *list* — the strings `["sin", "cos"]`, not the functions. The lambdas themselves are Java
locals of a generated helper method; nothing in the API exposes "the value of local `$name` after
evaluation". Worse, which locals to look at is only known *after* the expression has run, so the
naive answer — compile once to read the names, then compile again to fetch those values — costs a
second `javac` round-trip per library. §4.1 gets both in one pass.

**G2 — token lifetime.** Even with a token in hand, it dies with the defining evaluation. Measured
against the current build (see Appendix A):

```
export result: {"sin":"__λ:7","cos":"__λ:8"}
post-eval fn_apply(sin, 1.0) → RuntimeEvaluationException: Lambda expired or not found: 7
```

This is the central problem. Note the contrast with regex tokens: `RegexRegistry` keys by
`pattern + "\0" + flags`, so a regex token is *content-addressed* and re-resolves in any context.
Lambda tokens are *identity-addressed* against a map that is cleared per evaluation.

**G3 — no signature or arity.** `JsonataBoundFunction` must report `getFunctionSignature()`, and the
adapter must know whether to pack arguments into a tuple. Neither is recoverable from a token; both
are trivially recoverable from the AST.

---

## 4. Design overview

Build-time pipeline, run once per library:

```
definition string
  → Parser.parse                                (existing)
  → FunctionExportRewriter.topLevelBindings     (new)  — every $name the definition binds
  → FunctionExportRewriter.rewrite              (new)  — capture the result, add the values
  → Optimizer.optimize                          (existing)
  → Translator.translate                        (existing, unchanged)
  → JsonataExpressionLoader.load                (existing, unchanged)
  → evaluate once in "library-defining" mode    (new mode flag on the evaluation frame)
      · every lambdaNode() call is written into a durable LambdaScope instead of EvalState
  → read {"names": […], "functions": {name → token}}
  → wrap the named tokens in ExportedJsonataFunction implements JsonataBoundFunction
```

Nothing in the translator changes. The two new runtime concepts are the **export rewrite** (§4.1)
and the **durable lambda scope** (§4.2).

### 4.1 Export rewrite

The definition's last expression *is* the export list, so it must be evaluated and kept — and the
values must be collected in the same pass, before it is known which of them are wanted. The rewrite
therefore binds the trailing expression to a synthetic variable and appends one object constructor
that carries both halves:

```
(                                          (
  $pi := 3.14159;                            $pi := 3.14159;
  $product := function($a, $b){ … };         $product := function($a, $b){ … };
  $sin := function($x){ … };          →      $sin := function($x){ … };
  $cos := function($x){ … };                 $cos := function($x){ … };
                                             $__exportNames := ["sin", "cos"];
  ["sin", "cos"]                             {"names": $__exportNames,
)                                             "functions": {"pi": $pi, "product": $product,
                                                            "sin": $sin, "cos": $cos}}
                                           )
```

* Collecting **every** top-level binding is what makes one compilation enough. The values are cheap
  — object fields referencing nodes and lambda tokens that already exist — and the export list is
  applied to them afterwards, in Java.
* Building the object **inside** the block is required: top-level bindings become Java locals of the
  generated `__blockN` method, so only an expression in the same block can read them. It also gives
  the right failure mode for a name bound in a nested block — it is simply not among the collected
  bindings, which §6 turns into a precise error.
* The trailing expression is *moved*, not copied, so it is evaluated exactly once even if it is not
  a literal array (`$withDec ? $exports : $exports[0]` is a valid export list).
* The synthetic name is `__exportNames`, or `__exportNames2`, `…3` if the definition already binds
  it — checked against the collected bindings.
* The rewrite runs **before** `Optimizer.optimize`, so block-unwrapping and constant folding see the
  final shape.

Object construction skips absent values, so a name bound to nothing is missing from `functions` and
is reported as such rather than silently exporting a broken function.

### 4.2 Durable lambda scope

Introduce a `LambdaScope`: an id plus a `Map<String, JsonataLambda>`, owned by the library object
and alive as long as the library is.

**Token keys become scope-qualified.** `LambdaRegistry.lambdaNode` currently produces
`"__λ:" + counter`. When the active frame has a defining scope, it produces
`"__λ:" + scopeId + "/" + counter` and writes into that scope's map. `lookupLambda` (and the inlined
lookups in `fn_apply` / `fn_apply_tco`) gain one branch:

```java
int slash = key.indexOf('/');
if (slash > 0) {
    LambdaScope scope = LambdaScope.lookup(key.substring(0, slash));
    if (scope != null) return scope.get(key);      // durable: any thread, any evaluation, or none
}
// … existing per-evaluation map, then static LRU fallback
```

Consequences worth stating explicitly:

* Exported functions resolve **without any install/uninstall step at the call site** — including
  nested calls, since `$sin`'s closure reaches `$cos` through a holder array holding a
  scope-qualified token.
* They resolve on **any thread** and even with **no evaluation active**, which the identity-scoped
  scheme cannot do.
* The hot path for ordinary expressions gains one `indexOf('/')` on a short string per lambda
  lookup, and nothing else. Ordinary lambdas keep their unqualified keys.

**Setting the defining scope.** `EvalState` gets a `LambdaScope definingScope` field, set by a new
`beginEvaluation(..., LambdaScope)` overload and cleared in `end()`. It is reached from the library
builder through a package-private `AbstractJsonataExpression.evaluateDefining(JsonNode, JsonataBindings, LambdaScope)`
— a sibling of the existing `final evaluate(...)`, sharing its try/finally scaffold.

**Lifetime.** `LambdaScope` keeps a static `ConcurrentHashMap<String, LambdaScope>`. `JsonataLibrary`
holds the only strong reference and deregisters on `close()`; a `java.lang.ref.Cleaner` deregisters
scopes whose library became unreachable without being closed. A leaked scope costs one map entry
plus the closure graph — bounded by the definition expression's size, not by call volume.

Rejected alternative — *pinned scope stack*: keep unqualified keys, and have each exported call push
its scope onto a `List<Map<…>>` in `EvalState`, popping in a `finally`. It works (this is what the
prototype in Appendix B does, by merging the snapshot into the live map) but it adds per-call work,
needs re-entrancy care, and still fails when no evaluation is active. Scope-qualified keys make the
token self-describing, which is the property we actually want.

### 4.3 The adapter

```java
final class ExportedJsonataFunction implements JsonataBoundFunction {

    private final JsonNode token;        // "__λ:<scope>/<n>"
    private final String signature;      // §4.4
    private final int arity;             // declared parameter count
    private final JsonataLibrary owner;   // keeps the scope reachable

    @Override public String getFunctionSignature() { return signature; }

    @Override public JsonNode apply(JsonataFunctionArguments args) throws JsonataEvaluationException {
        JsonNode arg = switch (arity) {
            case 0  -> NullNode.instance;                                  // matches generated `NULL`
            case 1  -> args.size() == 0 ? MISSING : args.get(0);
            default -> packArgs(args.asList().toArray(new JsonNode[0]));   // non-flattening tuple
        };
        boolean ownFrame = !isEvaluationActive();
        if (ownFrame) beginEvaluation(owner.values(), owner.functions(), null, owner.regexes(), 0);
        try {
            return fn_apply(token, arg);          // fn_apply runs the TCO trampoline
        } catch (RuntimeEvaluationException e) {
            throw new JsonataEvaluationException(e.getErrorCode(), e.getMessage(), e);
        } finally {
            if (ownFrame) endEvaluation();
        }
    }
}
```

Packing is keyed on **declared** arity, not on `args.size()`, because `EvaluationContext.callBoundFunction`
already pads the argument list to the signature's length with `MissingNode`, and because
`buildInlineLambda`'s unpack code treats a `MissingNode` slot as an absent parameter.

`ownFrame` covers direct Java calls made outside any evaluation — needed for `$millis`-style
built-ins, the timeout deadline, and binding resolution inside the body. When called from inside a
consumer's evaluation we deliberately do **not** nest a frame: the body then observes the consumer's
bindings, depth counter, and timeout (§5).

### 4.4 Reading the export list

`{"names": …}` carries whatever the definition returned. It is accepted as:

* an array of strings — the normal form;
* a single string — JSONata collapses one-element sequences, so `"sin"` is a valid list of one;
* nothing else. A number, an object, or a lambda token yields
  `must return an array of function names, but one element is <type>`. The lambda-token case is the
  one users will actually hit — it is what a definition that forgets its export list and ends with
  its last `$name := function…` binding returns.

Names may carry the leading `$` or not; keys are normalised to the bare form. An empty array, an
empty name, or the same name twice is a build-time error (§6).

Only after this list is read does Java pick the matching entries out of `{"functions": …}` — which
is why the rewrite has to collect all of them.

### 4.5 Signature derivation

Look each exported name up in the AST's top-level bindings; when the bound value is a `Lambda`:

* if the lambda declares a signature (`function($x)<n:n>{…}`), report it verbatim;
* otherwise synthesise one optional `j` per parameter — `$sin` → `<j?:j>`, `$product` → `<j?j?:j>`.

All-optional is the right default: JSONata lets a lambda be called with fewer arguments than it
declares, binding the rest to *undefined*. A required-argument signature (`<jj:j>`) would make
`callBoundFunction` throw where the same call inside JSONata succeeds. `j` applies no coercion, so
the boundary stays lossless.

A `signature(name, sig)` option lets a caller tighten this (e.g. `"sin" → "<n:n>"`) to get numeric
coercion and arity checking at the Java boundary. Nothing about a function's arity is recoverable
when the binding's value is computed (`$twice($add3)`, `$uppercase ~> $trim`,
`$substring(?, 0, 5)`): those report no signature, and the adapter packs according to how many
arguments the caller actually supplies.

### 4.6 API surface

```java
package org.json_kula.jsonata_jvm;

public class JsonataExpressionFactory {

    /** Compiles a definition expression and exports the functions it names. */
    public Map<String, JsonataBoundFunction> compileLibrary(String definition)
            throws JsonataCompilationException;

    /** As above, with control over the definition-time input, bindings and signatures. */
    public JsonataLibrary compileLibrary(String functionDefinition,
                                                         JsonataLibraryOptions options)
            throws JsonataCompilationException;
}

/** Owns the durable lambda scope and the generated class of one definition expression. */
public final class JsonataLibrary implements AutoCloseable {
    public Map<String, JsonataBoundFunction> asMap();      // immutable, in export-list order
    public JsonataBoundFunction get(String name);          // null if not exported; $ optional
    public String getSourceJsonata();
    @Override public void close();                         // drops the scope; exported fns stop resolving
}
```

The signature is one argument: the definition. Which functions come out is a property of the
definition, not of the call site — the same file yields the same library everywhere it is loaded,
and there is no way for a caller's name list to drift out of sync with the file it names.

`compileLibrary` is the 90 % case. It leaks no lifetime control — the library is kept alive by the
exported functions' strong reference to it, and released when the last one is dropped.

**Name normalisation.** Export lists may be written with or without the `$` (`"$sin"` and `"sin"`
are the same export). Map **keys are without `$`**, matching `JsonataExpression.registerFunction`
and `JsonataBindings.bindFunction`, so the map can be fed straight into either. Worth adding a
convenience while we are here:

```java
public JsonataBindings bindFunctions(Map<String, JsonataBoundFunction> fns);   // JsonataBindings
```

---

## 5. Semantics and caveats

These follow from the design and should be documented in the Javadoc, not "fixed".

**Free variables are late-bound to the caller.** A definition body that references an unbound `$rate`
compiles to `resolveBinding("rate")`, which reads the *consumer's* bindings at call time. Helper
bindings defined in the definition block (`$pi`) are Java locals and are captured — those are early
bound. If a caller wants definition-time values for free variables, they pass them in
`JsonataLibraryOptions.bindings`, and the adapter installs them **only** when it opens its
own frame; inside a consumer evaluation the consumer wins. This asymmetry is worth an explicit note.

**Recursion budget is shared.** `LambdaRegistry.MAX_CALL_DEPTH` is 100 per evaluation. An exported
recursive function called from a consumer expression spends the consumer's budget. TCO still applies
inside the body (`fn_apply` runs the trampoline).

**Timeout is the consumer's.** `setTimeout` on the consumer expression bounds exported calls made
during that evaluation. A standalone call gets no timeout unless one is set in the options.

**Thread safety.** After the single defining evaluation, the closure graph is read-only: holder
arrays are locals of that one `__blockN` invocation and are never written again. The scope map is
populated during the defining evaluation and published safely via the `ConcurrentHashMap`. Exported
functions are therefore as thread-safe as any `JsonataExpression`.

**Multi-parameter functions and array arguments.** The tuple convention is ambiguous for a
2-parameter function called with a single array argument — `buildInlineLambda` cannot distinguish
"tuple of two" from "one array". This is pre-existing (the same ambiguity exists for a JSONata call
site) and inherited, not introduced. Document: always pass exactly `arity` arguments.

**Returned functions are evaluation-scoped.** A higher-order export
(`$adder := function($n){ function($x){ $x + $n } }`) returns a token minted during the *consumer's*
evaluation, so it dies with it — same as any lambda produced mid-expression today. Only the
functions present at export time are durable.

**Cost.** One generated class per library (Metaspace) and one javac invocation. Libraries are meant
to be built once and cached; building one per request would reproduce the known
`javac`-per-call Metaspace pressure. If several libraries are built at startup, route them through
`JsonataExpressionLoader.loadAll` (§7, step 6) to amortise the compiler bootstrap.

---

## 6. Errors and validation

All raised as `JsonataCompilationException` at build time, before any function is handed out:

| Condition | Message / behaviour |
|---|---|
| Definition returns nothing | "must return an array of function names to export, but it returned nothing" |
| Definition returns a non-string element | "must return an array of function names, but one element is *type*: *value*" — covers the common slip of ending on the last `$name := function…` binding, which returns a *function* |
| Definition returns an empty array | "must name at least one function to export" |
| The same name twice | "the definition expression names `$sin` twice" — ambiguous map keys |
| An empty name | rejected |
| Name not bound at the top level | "`$tan` is exported but not defined at the top level of the definition expression (it binds: `$pi`, `$product`, …)" — the binding list makes typos and nested-block bindings obvious |
| Name bound to something absent at runtime | "`$foo` is exported but evaluated to nothing" |
| Name bound to a non-function (e.g. `$pi`) | "`$pi` is not a function; the definition expression bound it to number 3.14159" — checked on the exported node via `isLambdaToken`, so computed values are caught too |
| Definition fails to parse / translate | propagate the existing exception unchanged |
| Definition throws during the single defining evaluation | wrap: "Definition expression failed to evaluate: …" |

Any failure closes the scope before propagating, so a rejected library leaves nothing registered.

Calling an exported function after `close()` yields a `JsonataEvaluationException` naming the
function: `Error calling exported function $sin: Lambda expired or not found: s3/7`.

---

## 7. Implementation plan

| # | Change | Files |
|---|---|---|
| 1 | `LambdaScope` — scope id, lambda map, process-wide registry and `Cleaner` | new, `…runtime` |
| 2 | Scope-qualified keys in `lambdaNode`; a single `resolve()` used by `lookupLambda`, `fn_apply`, `fn_apply_tco` | `LambdaRegistry` |
| 3 | `definingScope` on `EvalState`; `beginEvaluation` overload; `isEvaluationActive()` accessor | `EvaluationContext`, `JsonataRuntime` |
| 4 | `evaluateDefining(...)` package-private sibling of `evaluate`, plus accessors for the permanent bindings and regex cache | `AbstractJsonataExpression` |
| 5 | `FunctionExportRewriter` — collect top-level bindings, rewrite the tail, read the export list, recover per-name arity/signature | new, `org.json_kula.jsonata_jvm` |
| 6 | `JsonataLibrary`, `JsonataLibraryOptions`, `ExportedJsonataFunction`, factory methods | new + `JsonataExpressionFactory` |
| 7 | `JsonataBindings.bindFunctions(Map)` convenience | `JsonataBindings` |
| 8 | Docs: a "Function libraries" section in `README.md` and `docs/index.md`, `llms.txt` entries, `CLAUDE.md` | docs |

Steps 1–4 are the only runtime-touching ones and are additive; nothing on the existing hot path
changes behaviour.

**As built** (v1.0.5): the entry point is `compileLibrary(definition)` returning a `JsonataLibrary`
with `getFunctions()` and `getConstants()` — an exported name that is not a function is a constant
rather than an error, so a definition can carry a whole set of bindings, not only callable ones.
The durable-scope design in §4.2 was superseded during the code review that
followed. Function values are now nodes that carry their closure directly ([`LambdaNode`](../../src/main/java/org/json_kula/jsonata_jvm/runtime/LambdaNode.java)),
so there is no scope to register, qualify keys against, or close: a library simply holds the nodes
the definition produced, and they stay callable while referenced. `JsonataLibrary` keeps
`close()` and `isOpen()` as an explicit-lifetime convenience. Everything else — the export rewrite,
the arity and signature derivation, the adapter — matches the design above.

---

## 8. Testing plan

Unit tests in `src/test/java/org/json_kula/jsonata_jvm/JsonataLibraryTest.java`. The
definitions are the lambda samples from `ProgrammingTest` / `HigherOrderFunctionsTest` with their
trailing invocation replaced by an export list — the invocation now comes from Java or from another
expression, which is the point.

1. **The motivating example.** Export `$sin`/`$cos`; assert `sin(1) == Math.sin(1)` to within 1e-12
   (measured `0.8414709848078965`, bit-identical to `Math.sin(1)`), `sin(0) ≈ 0`, `cos(0) == 1`.
2. **The definition is plain JSONata.** Compile the same string with `compile()` and assert it
   evaluates to `["sin","cos"]` — the property that motivates the whole design.
3. **Cross-expression use.** Register the exported map on an unrelated expression and evaluate
   `angles.$sin($)`.
4. **Helper reachability.** Export only `$sin`; `$cos`, `$factorial` and `$product` stay internal
   and still work.
5. **Every lambda shape.** Multi-parameter, closure over a block local, recursive, tail-recursive
   (trampoline), `λ`, higher-order result (`$twice($add3)`), `~>` chain, partial application, and
   `$map`/`$filter`/`$reduce` inside an exported body.
6. **The export list.** `$`-prefixed names; a single unwrapped string; a computed list; order
   preserved; a definition that itself binds `$__exportNames`.
7. **Signatures.** Declared `<n:n>` reported and coerced; synthesised `<j?j?j?:j>`; override;
   missing argument becomes undefined; an array argument is not flattened.
8. **Bindings.** Per-evaluation, permanent, definition-time bindings, definition input, and both
   sides of free-variable late binding.
9. **Lifetime.** 200 sequential evaluations, 8 threads × 50 concurrent calls, `close()`,
   idempotent close, two independent libraries.
10. **Errors.** Every row of §6.
11. **Regression.** Full existing suite, plus an explicit assertion that ordinary (non-library)
    lambda tokens keep their unqualified key format.

---

## 9. Alternatives considered

**A0. Pass the export list to Java: `compileLibrary(List.of("$sin", "$cos"), definition)`.**
The first cut of this design, and the reason the rest of it exists. It let the definition be *almost*
JSONata — it typically ended on a binding, so evaluating it elsewhere returned a stray function
value, and the set of exports lived in the calling code rather than in the file that defines them.
Two owners of one fact: rename a function in the definition and the Java call site silently stops
matching; ship the definition to a colleague and it does not say what it provides. Rejected in favour
of the definition returning its own export list, which costs the rewrite in §4.1 (the values must be
collected before the names are known) and buys a definition that is valid, self-describing JSONata
anywhere it is loaded.

**A. Re-evaluate the definition on every call.** Compile one expression per exported name —
`( <definitions>; $sin($__a0) )` — and implement the bound function as
`expr.evaluate(input, bindings{__a0: arg})`. Zero runtime changes, and closure lifetime becomes a
non-issue. Rejected: every call re-runs the whole definition prologue, and, more seriously, a nested
`evaluate()` on a thread that is already inside one **clobbers the outer evaluation's state** —
`EvalState` is a single non-reentrant thread-local. Measured on the current build (Appendix A):
after a nested evaluation, the outer expression's lambdas and bindings are gone. That makes this
strategy unusable inside a consumer expression, which is the primary use case.

**B. Generate one Java method per exported function.** A new translator mode emitting
`public JsonNode sin(JsonNode x)` on the generated class. Fastest calls, best signatures — but it
duplicates the block/holder/closure machinery in a second code path and cannot express a function
whose value is computed rather than literal. Rejected as disproportionate; the token path already
produces correct closures.

**C. Closure conversion (materialise lambdas as data).** Replace tokens with a real `JsonNode`
subclass carrying the `JsonataLambda`. Cleaner in principle and would fix serialisation edge cases,
but it touches every `isTextual()`-based type test in the runtime. Out of scope; the scope-qualified
key is the 5 % of that change that solves this problem.

---

## 10. Open questions

1. **Should `compileLibrary` also export values?** A `Map<String, JsonNode>` sibling for `$pi` is
   nearly free once the export object exists. Deferred until asked for.
2. ~~**Should the free-variable rule be strict?**~~ **Decided: yes.** A definition that references a
   name it neither binds nor receives through `JsonataLibraryOptions.bindings` — and that is not a
   JSONata built-in — is rejected when the library is compiled. Late-binding to the calling
   expression made a library's behaviour depend on its caller and made a typo indistinguishable from
   a deliberate hook; parameterisation goes through the options instead, where the dependency is
   explicit. Implemented as `FunctionExportRewriter.requireSelfContained` over
   `ScopeAnalyzer.freeVariables`, which treats lambda parameters, nested block bindings, sibling
   forward references and path bindings (`@$v`, `#$i`) as bound.
3. **`$eval` re-entrancy** (Appendix A, probe 2) is a pre-existing bug this design routes around
   rather than fixes. Making `EvalState` a proper frame stack would fix `$eval`, unblock
   alternative A, and simplify §4.3's `ownFrame` handling. Worth a separate issue.

---

## Appendix A — measured behaviour of the current build

Run against `target/classes` at commit `e1acd91`.

**Probe 1 — export rewrite and token lifetime.** The definition of §1 ending on its `$cos` binding,
then the same definition with `{"sin": $sin, "cos": $cos}` appended as the last expression of the
block (an early, simpler form of the rewrite in §4.1 — enough to measure whether an appended object
constructor can reach the block's bindings at all, and what the tokens are worth afterwards):

```
as-is result:  "__λ:4"                                  ← block value = last binding, $sin unreachable
export result: {"sin":"__λ:7","cos":"__λ:8"}            ← an in-block export can reach them (G1)
sin token isLambdaToken = true
post-eval fn_apply(sin, 1.0)  → RuntimeEvaluationException: Lambda expired or not found: 7   ← G2
consumer expression using it  → JsonataEvaluationException: Error calling bound function     ← G2
```

The first line is also why a definition must return its export list explicitly: ending on a binding
yields one function value, which is neither a useful result for a plain JSONata engine nor a way to
reach the other functions.

**Probe 2 — nested evaluation clobbers the outer frame** (motivates rejecting alternative A, and
open question 3):

```
( $g := function($a){$a*2}; $eval("1+1"); $g(3) )   → FAILED: Lambda expired or not found: 1
( $eval("1+1"); $v )    with $v assigned 42        → (empty)     ← binding lost
( $g := function($a){$a*2}; $g(3) )                → 6           ← control, no $eval
```

## Appendix B — prototype of the proposed mechanism

A stand-in for §4.2 (snapshot the defining evaluation's lambda map; make it visible again during a
later, unrelated evaluation) plus the §4.3 adapter, run against the same build:

```
captured tokens: {"sin":"__λ:3","cos":"__λ:4","product":"__λ:1"}   snapshot size = 4
consumer  [ $sin(1), $sin(0), $mul(3, 4) ]  → [0.8414709848078965, 4.253977785846482E-17, 12]
java      Math.sin(1)                       →  0.8414709848078965
direct Java call outside any evaluation     → FAILED: Lambda expired or not found: 3
```

The consumer results confirm the whole chain: co-recursive `$sin`/`$cos`, the non-exported helpers
`$factorial`/`$product` reached through holder arrays, the multi-argument `packArgs` convention, and
exact agreement with `Math.sin`. The last line is precisely why §4.2 prefers scope-qualified token
keys over a pinned scope installed into the active frame — the prototype's snapshot has nowhere to
install itself when no evaluation is running.
