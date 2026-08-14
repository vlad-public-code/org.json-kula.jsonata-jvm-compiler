# JSonata2Java — code review

Date: 2026-08-13 · Reviewed at branch `feature/jsonata-function-library` (`575fb4d`, v1.0.5-SNAPSHOT)
Dimensions: functionality coverage · code purity · CPU performance · memory performance

> **Status: every finding below has been fixed** (2026-08-14, same branch). See
> [§11 Outcome](#11-outcome) for what changed, what the fixes measured, and the one
> recommendation that was tried and deliberately reverted. The findings are kept in their original
> form — they document why the code looks the way it does now.

---

## 1. Method

Read: every file in `parser`, `optimizer`, `translator`, `runtime`, `loader` and the public API
package (53 files, 14 978 lines of main code; 12 621 lines of test code).

Measured: seven probe programs run against `target/classes` on OpenJDK 21, Windows 11 — every number
and every "actual" below is output from a real run, not an estimate. Findings are only listed when a
repro reproduced them.

Baseline health: `mvn test` → **2 616 tests, 0 failures**, 6 skipped (3 `@Disabled` manual
performance/leak suites). The official JSONata suite contributes 1 279 of those.

---

## 2. Summary

| # | Finding | Dimension | Severity |
|---|---|---|---|
| [F1](#f1) | Valid JSONata fails to compile: multi-param lambda with a block body in `$map`/`$filter`/`$sort`/`$sift`/`$each`/`$single` | Functionality | **Blocker** |
| [F2](#f2) | Higher-order built-ins silently drop arguments when the callback is a variable — wrong results, no error | Functionality | **High** |
| [F3](#f3) | Internal sentinels live in user data space (`__PRESERVE__`, `__λ:`, `__rx:`) — silent data corruption | Functionality | **High** |
| [C1](#c1) | Bindings are re-merged on every `evaluate()` — 3×–9× throughput loss when any binding is registered | CPU | **High** |
| [M1](#m1) | One classloader + retained class bytes per `compile()`; metaspace not promptly reclaimed | Memory | **High** |
| [C2](#c2) | `$eval` compiles a fresh class on every call (~84 ms each) and leaks one classloader per call | CPU + Memory | **High** |
| [F4](#f4) | `setTimeout` is not enforced inside built-in higher-order functions | Functionality | Medium |
| [F5](#f5) | `$eval` clobbers the enclosing evaluation's lambdas and bindings | Functionality | Medium |
| [P1](#p1) | Translator emits an orphan `__blockN` method — same block generated twice | Purity | Medium |
| [P2](#p2) | `JsonataExpressionFactory`'s constructor mutates global static state | Purity | Medium |
| [C3](#c3) | `$sort` with a comparator invokes it twice per comparison, 2 allocations each | CPU | Medium |
| [F6](#f6) | Test-suite runner silently excludes two files; both pass today | Functionality | Medium |
| [M2](#m2) | `toText`/`$string` deep-copies whole subtrees to strip sentinels | Memory | Medium |
| [F7](#f7) | `evaluate()` returns `MissingNode`, its Javadoc promises `NullNode` | Functionality | Low |
| [P3](#p3)–[P7](#p7) | God methods, arithmetic duplication, fragile class-name regex, dead test filter, bindings-dropping default method | Purity | Low |
| [C4](#c4)–[C5](#c5) | Allocation on empty path steps; `O(n²)` index dedup in `dynamicFilter` | CPU | Low |

**What is good** — worth stating, because the defects below are concentrated in two code paths and
should not be read as a verdict on the whole:

* Architecture is clean and genuinely layered: parse → optimize → translate → load, each stage
  independently testable, with the runtime as the only shared dependency of generated code.
* Test coverage is unusually strong for a compiler project: 1 279 official JSONata cases plus ~1 300
  hand-written ones, organised by language feature.
* Batch compilation (`compileAll`) is the right primitive and is measurably excellent (§C2).
* Thread-safety design is coherent: stateless generated classes, thread-local evaluation state,
  `ConcurrentHashMap` for shared registries, immutable AST records.
* TCO trampoline, recursion-depth cap, and error-code fidelity to the JSONata spec are all present
  and tested.
* Documentation (README, GitHub Pages, `CLAUDE.md`, subsystem docs) is thorough and current.

---

## 3. Functionality coverage

### <a id="f1"></a>F1 — Valid JSONata fails to compile (Blocker)

A multi-parameter lambda whose body is a block containing a variable binding produces Java that does
not compile. Every affected expression is legal JSONata that the reference implementation accepts.

```
$map([1,2], function($v,$i){ ($c := 1; $v + $i) })
  → JsonataCompilationException: Compilation failed …
    CompiledExpr1.java Line 37: cannot find symbol
    symbol:   variable __root
```

Measured matrix (probe 4):

| Expression shape | Result |
|---|---|
| `$map([1,2], function($v,$i){ ($c := 1; $v + $i) })` | **FAIL** |
| `$filter([1,2,3], function($v,$i){ ($c := 2; $v > $c) })` | **FAIL** |
| `$single([1,2], function($v,$i){ ($c := 1; $v = $c) })` | **FAIL** |
| `$sift({"a":1}, function($v,$k){ ($c := 1; $v >= $c) })` | **FAIL** |
| `$each({"a":1}, function($v,$k){ ($c := 1; $v) })` | **FAIL** |
| `$sort([3,1,2], function($a,$b){ ($c := 1; $a > $b) })` | **FAIL** |
| `$map([1,2], function($v,$i){ $i = 0 ? ($c := 10; $c) : $v })` (nested in a conditional) | **FAIL** |
| same shapes with a **single** parameter | OK |
| same shapes with **no binding** in the block | OK |
| `$reduce` with any arity | OK |
| a user-defined multi-param function called directly | OK |

**Root cause.** `FunctionCallCodeGen.genUnpackLambda` (`FunctionCallCodeGen.java:238`) emits

```java
private JsonNode __unpack2(JsonNode __el) throws RuntimeEvaluationException { … }
```

— a method with no `__root` parameter — but generates the body with `ctx.withCtx("__el")`, which
changes only the *context* variable and leaves `ctx.rootVar` as `"__root"`. When the body needs a
block helper, `BlockCodeGen.visitBlock` (`BlockCodeGen.java:153`) emits the call as
`__block3(ctx.rootVar, ctx.ctxVar, …)`, i.e. `__block3(__root, __el, …)` — and `__root` is not in
scope. Verbatim from the generated source:

```java
 34| private JsonNode __unpack2(JsonNode __el) throws RuntimeEvaluationException {
 35|     JsonNode $a = __el.get(0);
 36|     JsonNode $b = __el.get(1);
 37|     return __block3(__root, __el, $a, $b);     // ← __root undefined here
 38| }
```

`FunctionCallCodeGen.genLambdaMethod` (`:433`) has the identical signature shape and the same latent
defect, reachable through `inlineLambda`'s multi-param path (`:292`).

**Fix.** Give the generated method the root: emit
`private JsonNode __unpackN(JsonNode __root, JsonNode __el)` and reference it as
`(__el) -> __unpackN(__root, __el)` at the call site, or thread a `GenCtx` whose `rootVar` names an
actual parameter. Add regression cases for all six built-ins — the official suite does not cover this
shape, which is why 2 616 green tests did not catch it.

### <a id="f2"></a>F2 — Higher-order built-ins drop arguments when the callback is a variable (High)

When the function argument is a *literal lambda* the translator inspects its arity and wires the
extra tuple slots. When it is anything else — a variable holding a function, a partial application,
a chain — it falls back to `(__elem -> fn_apply(fnExpr, __elem))` (`FunctionCallCodeGen.java:96-101`,
`:122-127`, `:158-160`), passing **only the element**. The extra parameters silently become
undefined, and `$sort` additionally loses the comparator-vs-key-function distinction.

Measured (probe 5), all **without any error**:

| Expression | Actual | Expected |
|---|---|---|
| `( $f := function($v,$i){ $i }; $map([10,20,30], $f) )` | `[null,null,null]` | `[0,1,2]` |
| `$map([10,20,30], function($v,$i){ $i })` (literal — control) | `[0,1,2]` | `[0,1,2]` |
| `( $p := function($v,$i){ $i > 0 }; $filter([1,2,3], $p) )` | *nothing* | `[2,3]` |
| `( $c := function($a,$b){ $a > $b }; $sort([3,1,2], $c) )` | `[3,1,2]` (unsorted) | `[1,2,3]` |
| `( $s := function($v,$k){ $k = "a" }; $sift({"a":1,"b":2}, $s) )` | *nothing* | `{"a":1}` |
| `( $r := function($a,$b){ $a + $b }; $reduce([1,2,3], $r) )` | `6` | `6` |
| `( $f := $string; $map([1,2], $f) )` | `["1","2"]` | `["1","2"]` |

This is worse than F1: F1 fails loudly at compile time, F2 returns plausible-looking wrong data.
Composing library functions — exactly what the new function-library feature encourages — lands
squarely on this path.

**Fix.** The runtime already passes a tuple to `fn_reduce`; do the same for map/filter/single/sift/
each and let the callee unpack. Since arity is unknown for a non-literal callee, the runtime wrapper
should pass the full `[value, index, array]` tuple and have `fn_apply` bind as many as the lambda
declares — the lambda's own unpack code already tolerates missing slots. `$sort` needs the
comparator/key decision made at runtime from the resolved lambda's arity rather than at compile time
from the AST.

### <a id="f3"></a>F3 — Internal sentinels collide with user data (High)

Three internal markers are encoded as ordinary JSON values, so user data containing them is
misinterpreted. All confirmed (probe 1):

| Input | Expression | Actual | Expected |
|---|---|---|---|
| `{"__PRESERVE__": 5}` | `[$]` | `[5]` | `[{"__PRESERVE__":5}]` |
| `{"a":{"__PRESERVE__":[1,2]}}` | `[a]` | `[[1,2]]` | `[{"__PRESERVE__":[1,2]}]` |
| `{"s":"__λ:1"}` | `$type(s)` | `"function"` | `"string"` |
| `{"s":"__λ:1"}` | `$string(s)` | `""` | `"__λ:1"` |
| `{"s":"__λ:1"}` | `s ? "yes" : "no"` | `"no"` | `"yes"` |
| `{"s":"__λ:1"}` | `s ~> $uppercase` | `"__λ:2"` (composed a function!) | error or `"__Λ:1"` |
| `{"s":"__rx:a<NUL>"}` | `$string(s)` | `""` | the string itself |

Sources: `JsonataRuntime.preserveArray` (`:798`) wraps in `{"__PRESERVE__": …}` and `arrayOf`
(`:823`) unwraps any object carrying that key; `LambdaRegistry.LAMBDA_PREFIX = "__λ:"`;
`RegexRegistry.REGEX_PREFIX = "__rx:"`.

Severity judgement: the string prefixes are exotic enough that accidental collision is unlikely, but
`$string` returning `""` for real data is silent loss, and `__PRESERVE__` is reachable by any
document that uses that key. Adversarial input can deliberately trigger all three.

**Fix, in increasing order of cost.** (a) `preserveArray` needs no data encoding at all — the
translator knows statically which constructor sites preserve, so pass a `boolean` or use a distinct
Java carrier type instead of an `ObjectNode`; `arrayOf` already takes `Object...` and handles
`RangeHolder` the same way. (b) For lambdas/regexes, use dedicated `JsonNode` subclasses (a
`ValueNode` carrying the reference) instead of `TextNode` — `isTextual()` then returns false and
every type test falls out correctly. This is design note §9's "alternative C"; F3 is the concrete
cost of not doing it.

### <a id="f4"></a>F4 — `setTimeout` is not enforced in built-in higher-order functions (Medium)

`EvaluationContext.checkTimeout()` has exactly **one** call site in the runtime — the range operator
(`JsonataRuntime.java:907`) — plus a deadline check inside `fn_apply` (`LambdaRegistry.java:160`),
which only fires for calls to a function held in a *variable*. Built-in HOFs invoke inline lambdas
directly as `JsonataLambda`, bypassing both.

Measured (probe 2): 16 M iterations with a 50 ms deadline ran to completion in 259 ms —

```
$count($map([1..4000], function($i){ $count($map([1..4000], function($j){ $j })) }))
setTimeout(50) → COMPLETED after 259 ms (deadline ignored)
```

whereas `$string([1..3000000])` with the same deadline correctly threw `U1001` at 51 ms, because the
range loop does check. So the feature works only where the range operator or `fn_apply` happens to be
on the path. `JsonataExpression.setTimeout`'s Javadoc says the evaluation "is interrupted", which
overstates it.

**Fix.** Call `checkTimeout()` from the sequence-builtin loops (`fn_map`, `fn_filter`, `fn_reduce`,
`fn_each`, `fn_sift`, `fn_sort`, `descendant`, `wildcard`) on a masked counter as the range loop
does. Cost is one predictable branch per N iterations. Alternatively document the limit honestly.

### <a id="f5"></a>F5 — `$eval` clobbers the enclosing evaluation (Medium, known)

`EvalState` is a single non-reentrant thread-local; a nested `evaluate()` — which `$eval` performs
via the delegate — calls `begin()` over the live frame and `end()` clears it. Confirmed (probe 1):

```
( $g := function($a){$a*2}; $eval("1+1"); $g(3) )  → Lambda expired or not found: 3   (expected 6)
( $eval("1+1"); $v )   with $v assigned 42         → (empty)                          (binding lost)
( $g := function($a){$a*2}; $g(3) )                → 6                                (control)
```

Already documented in `docs/design/function-library.md` Appendix A and open question 3. Fix is to
make `EvalState` a stack of frames — which also removes the special-case `ownFrame` handling in
`ExportedJsonataFunction` and unblocks a simpler library implementation.

### <a id="f6"></a>F6 — The official-suite runner silently excludes two files (Medium)

`JsonataTestSuiteTest.runAllTestCases` (`:73-74`) filters out any file whose name contains
`sequences`, and `large.json`. That excludes `array-constructor/array-sequences.json` (5 cases) and
`flattening/large.json` (2 cases) — 1 279 of 1 281 files run.

Both files **pass in full today** (probe 6): 5/5 and 2/2, the large one in 147 ms and 125 ms. The
exclusions are stale. Meanwhile README and GitHub Pages state "All test cases from the official
JSONata test suite pass" — true in substance, but the suite as wired does not actually assert it.

Also in that runner: a case that declares no expectation at all (`result` / `undefinedResult` /
`code`) and throws is swallowed silently (`:146-153` — the `catch` block has no `else` branch), and
the `.jsonata` filter on `:72` is dead (a path ending in `.json` can never end in `.jsonata`).

**Fix.** Delete both exclusions and the dead filter; add an `else fail(...)` to the catch.

### <a id="f7"></a>F7 — `evaluate()` returns `MissingNode`, Javadoc promises `NullNode` (Low)

`JsonataExpression.evaluate`'s contract says "or `NullNode` if the expression yields no match
(consistent with JSONata's undefined-to-null semantics)", and `AbstractJsonataExpression` documents
"the generated class does this automatically". Neither converts: `doEvaluate`'s result is returned
verbatim, and the project's own tests assert the opposite —
`HigherOrderFunctionsTest.filter_singleParam_noMatchReturnsMissing` requires `result.isMissingNode()`.

Callers writing `result.isNull()` get `false` for an undefined result. `MissingNode` is arguably the
better contract (it round-trips JSONata semantics); the fix is to correct the Javadoc in
`JsonataExpression` and `AbstractJsonataExpression`, not the behaviour.

### Coverage that is genuinely solid

* All 108 `fn_*` runtime entry points map to documented JSONata built-ins; the `BUILTIN_NAMES` set in
  the parser matches the JSONata 2.0 function list.
* Signature types `f` (function) and `x` are unsupported — documented in README, `CLAUDE.md` and
  `JsonataBoundFunction`'s Javadoc, and consistent across all three.
* Language features are exercised by 23 feature-specific test classes plus the official suite.
* The new function-library feature (51 tests) covers every lambda shape, error path and concurrency.

---

## 4. Code purity

### <a id="p1"></a>P1 — The translator generates the same block twice and emits an orphan method (Medium)

In the generated source for `$sort([3,1,2], function($a,$b){ ($c := 1; $a > $b) })`, two identical
block methods appear — `__block1` (with alias-suffixed parameters) and `__block3` — and only
`__block3` is called. `__block1` is dead weight in every affected class: it costs a duplicate
translation pass, consumes `GenState.counter` ids, and enlarges the compiled class.

The cause is speculative generation: a lambda body is translated down one path (`buildInlineLambda`),
the result is discarded when the caller decides on `genUnpackLambda` instead, but the helper methods
the first pass appended to `GenState.helperMethods` are never removed. Helper emission should be
transactional (generate into a scratch buffer, commit only the chosen path) or the decision should be
made before any body translation.

### <a id="p2"></a>P2 — Factory constructor mutates global static state (Medium)

`JsonataExpressionFactory`'s constructor (`:47-64`) calls
`JsonataRuntime.registerEvalDelegate(...)`, a process-wide `volatile static`. Consequences:

* Constructing a second factory silently rebinds `$eval` for *every* expression already compiled by
  the first — including expressions in unrelated components of the same JVM.
* An expression compiled by factory A may execute `$eval` through factory B's loader and classpath.
* A constructor with a global side effect is untestable in isolation and order-dependent.

Prefer an instance-scoped delegate: the generated class already reaches its factory indirectly, or
`$eval` support can be passed through the evaluation frame like bindings are.

### <a id="p3"></a>P3 — God methods in the translator (Low)

`Translator.compilePathSteps` is **335 lines**; `visitFunctionCall` 165; `visitPathExpr` 134;
`visitGroupByExpr` 113. `Translator.java` totals 1 958 lines even after `FunctionCallCodeGen` (764),
`BlockCodeGen` (226) and `ScopeAnalyzer` (204) were split out. `compilePathSteps` is where path
semantics, predicate handling, context binding and constructor steps all interleave — the highest-risk
code in the project to change, and the natural next extraction (`PathCodeGen`).

`JsonataRuntime` at 2 268 lines with 108 static methods is large but cohesive, and the numeric/string/
datetime/sequence subsystems have already been extracted; the remaining bulk is fine as a dispatch
surface for generated code.

### <a id="p4"></a>P4 — Four near-identical arithmetic methods (Low)

`add`, `subtract`, `multiply`, `divide` (`JsonataRuntime.java:353-401`) repeat the same two type
guards with only the error message and operator differing. A single
`arith(a, b, op, symbol)` taking a `DoubleBinaryOperator` collapses ~40 lines to ~12 with no
behavioural change (the JIT inlines the operator).

### <a id="p5"></a>P5 — Class-name extraction by naive regex (Low)

`JsonataExpressionLoader.CLASS_PATTERN = "\\bclass\\s+(\\w+)"` (`:37`) takes the first textual match
in the source. It works today only because `ClassAssembler` emits the class declaration before the
`__SOURCE` literal that contains arbitrary user text. Any reordering of the template — or a future
import/annotation containing the word `class` — silently picks the wrong name. The assembler already
knows the class name; pass it to the loader instead of re-deriving it.

### <a id="p6"></a>P6 — `evaluate(JsonNode, JsonataBindings)` default silently drops bindings (Low)

`JsonataExpression`'s default implementation (`:62-64`) ignores its `bindings` argument and delegates
to `evaluate(input)`. Every generated class overrides it, so this only bites hand-written
implementations — where a passed-in binding vanishing without a trace is a nasty surprise. Either
make it abstract or throw `UnsupportedOperationException`.

### <a id="p7"></a>P7 — Smaller items (Low)

* `FunctionExportRewriter.exportedNames` uses `List.contains` for duplicate detection — fine at
  export-list sizes, but a `LinkedHashSet` states the intent better.
* `JsonataLibraryOptions.getSignatureOverrides()` is unused; either use it or drop it.
* `EvaluationContext.EMPTY_BINDINGS` is a shared *mutable* `JsonataBindings` instance used as an
  immutable empty; safe today because nothing writes to it, but one accidental `bindValue` would be a
  cross-evaluation bug. Make it a genuinely immutable instance.
* No `TODO`/`FIXME`/`System.out` anywhere in main — the codebase is clean of debt markers, which is
  worth noting given its size.

---

## 5. CPU performance

### <a id="c1"></a>C1 — Bindings are re-merged on every `evaluate()` (High)

`EvaluationContext.beginEvaluation` (`:115-127`) allocates a fresh `JsonataBindings` — two
`LinkedHashMap`s — and copies every permanent value and function into it on **every** call, even when
no per-evaluation bindings are supplied and nothing has changed since the last call.

Measured (probe 7, 2 M iterations of `a.b + 1`, steady state):

| Instance state | ns / evaluate | vs baseline |
|---|---|---|
| no bindings | **21.2 ns** | — |
| 1 permanent binding | **62.6 ns** | 3.0× slower |
| 10 permanent bindings | **198.6 ns** | 9.4× slower |

The expression itself is unchanged across all three rows; the entire difference is merge overhead.
This is the highest-leverage runtime fix in the project, and it hits precisely the shape a long-lived
embedding uses — register functions once at startup, evaluate on the hot path.

**Fix.** Cache the merged `JsonataBindings` on the expression instance, invalidated by `assign` /
`registerFunction` (a `volatile` field plus a dirty flag). When `perEval == null`, install the cached
instance directly — zero allocation. When `perEval != null`, merge on top of the cached base.

### <a id="c2"></a>C2 — Per-expression compilation cost, and `$eval` paying it per call (High)

Measured (probe 5):

| Path | Total | Per expression |
|---|---|---|
| `compile()` × 200 | 17 272 ms | **86.4 ms** |
| `compileAll(200)` | 499 ms | **2.50 ms** |

A **35× difference**, consistent with the documented rationale for batching: the fixed `javac`
bootstrap dominates. Two consequences:

* `compile()` should be understood as the exceptional path, not the default. The README shows
  `compile()` first and mentions `compileAll` only in passing; the guidance should be inverted for any
  caller with more than one expression.
* The project's own test suite pays this: `JsonataTestSuiteTest` compiles 1 279 expressions one at a
  time and takes **139.8 s** of the 234 s spent running tests — 60% of the suite's wall time. Batching per test file (or per group) would cut the
  build time several-fold.

`$eval` is the same cost on the *evaluation* path, with no cache at all
(`JsonataExpressionFactory:48-63` compiles a new class per call):

```
$map([1..50], function($i){ $eval("1+" & $i) })   → 4 189 ms  (~84 ms per call)
$map([1..50], function($i){ 1 + $i })             → 0 ms
```

**Fix.** A bounded `ConcurrentHashMap<String, JsonataExpression>` cache in the eval delegate keyed by
expression text turns repeated `$eval` of the same expression into a lookup; `$eval` with a
genuinely dynamic string remains expensive by nature, and that is worth documenting loudly.

### <a id="c3"></a>C3 — `$sort` comparator invoked twice per comparison (Medium)

`SequenceBuiltins.fn_sort_comparator` (`:107-114`) calls the user comparator with `(a,b)` and then,
whenever the first call is falsy, again with `(b,a)` to distinguish "less" from "equal". Each call
allocates a fresh two-element `ArrayNode`. For n log n comparisons that is up to 2 n log n user-lambda
invocations and 2 n log n allocations.

The second call is only needed to return `0` for stable ordering — but `List.sort` is TimSort, which
is already stable, so returning `-1` instead of `0` for the "not greater" case preserves the same
order with half the work. Two related notes:

* An inconsistent user comparator (not a strict weak ordering) risks TimSort's
  `IllegalArgumentException: Comparison method violates its general contract`, surfacing as a generic
  `JsonataEvaluationException`. A 40-element probe with `function($a,$b){ true }` did *not* trip it,
  but the exposure is real; the reference implementation uses merge sort and cannot fail this way.
* Stability itself is correct today (verified: equal keys retain input order).

### <a id="c4"></a>C4 — Allocation on every path step, including empty ones (Low)

`field`, `wildcard`, `mapStep`, `mapConstructorStep`, `filter` and friends allocate an `ArrayNode`
before knowing whether anything will match, then discard it via `unwrap` → `MISSING` when nothing
does. On a document where most steps miss (the common case for `a.b.c` over heterogeneous records),
that is one garbage `ArrayNode` per step per element. A fast path — scan first, allocate only on the
second match, return the single node directly on exactly one — removes most of it. `field` is the
single hottest method in the runtime, so it is worth the extra branch.

`arrayOf(Object...)` also boxes into an `Object[]` on every array construction; a `JsonNode[]`
overload for the common case (no `RangeHolder`, no preserve wrapper) avoids both the boxing and the
`instanceof` chain.

### <a id="c5"></a>C5 — `dynamicFilter` index dedup is `O(n²)` (Low)

`JsonataRuntime.dynamicFilter` (`:196-200`) dedups multi-index subscripts with
`indices.contains(actual)` on an `ArrayList`, then sorts. A `TreeSet<Integer>` (or a `BitSet` bounded
by sequence size) gives sorted-unique in one pass. Only matters for large index arrays, hence Low.

---

## 6. Memory performance

### <a id="m1"></a>M1 — One classloader and a retained byte-array map per compilation (High)

Measured (probe 5, metaspace pool via `MemoryPoolMXBean`):

| Scenario | Metaspace held |
|---|---|
| 200 × `compile()` (all instances kept) | +2 940 KB |
| after dropping all references + 2 × `System.gc()` + 1 s | **+2 437 KB still held** (222 classes unloaded) |
| 1 × `compileAll(200)` (all instances kept) | +793 KB |

Two distinct issues:

1. **Per-compile classloader.** `load()` delegates to `loadAll(List.of(src))`, so each single
   compilation creates its own `InMemoryClassLoader`. Metaspace for a class is reclaimed only when its
   entire loader becomes unreachable and a GC cycle unloads it — which the measurement shows is slow
   and partial. Batching gives 3.7× lower metaspace for the same 200 classes because one loader holds
   them all. This is the mechanism behind the known "compile in a loop → metaspace growth" behaviour.
2. **Class bytes retained forever.** `InMemoryClassLoader` (`:311-330`) keeps its
   `Map<String, byte[]> classBytes` for the lifetime of the loader, although each entry is needed
   exactly once, inside `findClass`. For a 200-expression batch that is 200 byte arrays (several
   hundred KB) pinned for the life of every expression instance. **Easy, safe fix:** remove the entry
   after `defineClass` — `loadClass` consults `findLoadedClass` first, so `findClass` is never called
   twice for the same name.

Recommendation beyond the fix: document the lifecycle contract — an expression instance owns a class
and a classloader; compile once and cache. And prefer `compileAll` (see C2) for both CPU and memory.

### <a id="m2"></a>M2 — `$string` deep-copies whole subtrees (Medium)

`JsonataRuntime.toText` (`:2173`) routes every array/object through
`sanitizeForString(n).toString()`, and `sanitizeForString` (`:2177`) rebuilds the entire tree
unconditionally to replace lambda/regex sentinel strings. Serialising a 10 MB document therefore
allocates a full second copy before writing a character — even though the overwhelming majority of
documents contain no sentinels at all.

**Fix.** Scan first and copy only if a sentinel is present (`containsSentinel(n)` short-circuits on
the first hit); most calls then serialise the original node with zero copying. Fixing F3 by moving
sentinels out of data space removes this cost entirely.

### <a id="m3"></a>M3 — `$eval` leaks a class and a classloader per call (High, same root as C2)

Each `$eval` invocation compiles and loads a new class through a new loader (~84 ms, §C2). In a loop
this grows metaspace monotonically until GC can unload the loaders. The expression cache proposed in
C2 fixes the memory profile as well as the CPU one.

### <a id="m4"></a>M4 — Thread-local evaluation state retains peak-sized structures (Low)

`EvaluationContext.EvalState.end()` (`:51`) nulls `bindings` and `instanceRegexes` — good — but
`evalLambdas` is `clear()`ed, not released, so each thread that ever evaluated a lambda-heavy
expression retains a `HashMap` at its peak capacity for the life of the thread. On a large web
container pool this is a bounded but real per-thread footprint. Clearing is the right default (it
avoids re-allocation); consider dropping the map when it grew past a threshold.

### <a id="m5"></a>M5 — Registries are bounded and correctly scoped (no action)

Worth recording as verified-good: the static `LAMBDA_REGISTRY` and `REGEX_REGISTRY` fallbacks are
LRU-bounded at 100 entries; per-evaluation lambdas live in the thread-local map and are discarded per
call; `LambdaScope` (the function-library scope) is registered in a `ConcurrentHashMap` and removed by
`close()` **and** by a `Cleaner` if the library is dropped without closing. Regex compilation is
content-keyed and cached per expression instance. No unbounded growth found in any of them.

---

## 7. Prioritised actions

| Priority | Action | Finding | Effort |
|---|---|---|---|
| 1 | Pass `__root` into `__unpackN` / `__lambdaN`; add regression tests for all six HOFs | F1 | S |
| 2 | Pass the full tuple to non-literal HOF callbacks; decide comparator-vs-key at runtime | F2 | M |
| 3 | Cache the merged bindings per instance; invalidate on `assign`/`registerFunction` | C1 | S |
| 4 | Cache `$eval` compilations; document the cost of dynamic `$eval` | C2, M3 | S |
| 5 | Drop class bytes after `defineClass`; document compile-once lifecycle; steer callers to `compileAll` | M1 | S |
| 6 | Remove the `__PRESERVE__` data encoding (translator already knows statically) | F3a | M |
| 7 | Delete the stale test-suite exclusions and the swallow-on-no-expectation branch | F6 | XS |
| 8 | `checkTimeout()` in the sequence-builtin loops, or document the limitation | F4 | S |
| 9 | Make helper-method emission transactional (no orphan `__blockN`) | P1 | S |
| 10 | Fix `evaluate()`'s `NullNode`/`MissingNode` Javadoc | F7 | XS |
| 11 | Make `EvalState` a frame stack (fixes `$eval` re-entrancy, simplifies library frames) | F5 | M |
| 12 | Move lambda/regex tokens to dedicated `JsonNode` subclasses | F3b | L |
| 13 | Instance-scoped `$eval` delegate instead of the global static | P2 | S |
| 14 | Extract `PathCodeGen` from `Translator.compilePathSteps` | P3 | M |
| 15 | Single-comparator-call sort; fast paths in `field`; `TreeSet` dedup in `dynamicFilter` | C3–C5 | S |

Items 1, 2 and 3 are the ones worth doing before the next release: two are correctness defects
reachable from ordinary expressions, and the third is a 3–9× throughput multiplier for every
embedding that uses bindings.

---

## Appendix — reproducing the measurements

Seven standalone probe programs were compiled against `target/classes` and the Maven dependency
classpath and run on OpenJDK 21 (Temurin), Windows 11, on an otherwise idle machine:

| Probe | Establishes |
|---|---|
| 1 | Sentinel collisions (F3); `$eval` frame clobbering (F5); `$eval` cost (C2); timeout in `$string` |
| 2 | Timeout ignored in inline-lambda HOFs (F4); the codegen failure that led to F1 |
| 3 | Generated source for the F1 failure, including the orphan `__block1` (P1) |
| 4 | The exact F1 matrix — which shapes fail and which compile |
| 5 | F2 result table; metaspace and compile-time figures (C2, M1) |
| 6 | Both excluded suite files pass in full (F6) |
| 7 | Bindings-merge overhead: 21.2 / 62.6 / 198.6 ns per evaluate (C1) |

Micro-benchmarks were run in-process with warm-up rounds rather than under JMH; the reported ratios
(3×, 9×, 35×) are large enough to survive that imprecision, and the absolute nanosecond figures should
be treated as indicative rather than publishable.

---

## <a id="11-outcome"></a>11. Outcome

Everything in §7 was implemented on the same branch, each step validated against the full suite.
Two of the fixes turned out to be worth more than the review expected, and one was wrong.

### Correctness

| Finding | Fix | Evidence |
|---|---|---|
| F1 | `genUnpackLambda` / `genLambdaMethod` take `__root` as a parameter; call sites pass it | 8 new tests in `HigherOrderFunctionsTest` cover all six built-ins, block bodies nested in conditionals, and a body that reads the document root |
| F2 | Function values carry their declared arity; `$map`/`$filter`/`$single`/`$sift`/`$each`/`$sort` overloads take the callback as a node and pick the plain or indexed variant from it | 12 new tests: named callbacks of 1/2/3 parameters, comparator vs key function, built-in as a value, partial application, `~>` chain |
| F3 | `LambdaNode`, `RegexNode` and `PreservedNode` replace the string and object sentinels entirely | `FunctionValueTest` — 9 tests asserting that data resembling a marker stays data, and that real function values still behave |
| F4 | `deadlineGuard` wraps callbacks at every higher-order entry point, and only when a deadline exists | the 16 M-iteration probe now stops at `U1001` in 69 ms instead of running to completion |
| F5 | Nested evaluations suspend and restore the enclosing frame instead of overwriting it | `EvalNestingTest` — 8 tests covering lambdas, permanent/per-evaluation bindings, bound functions and nested `$eval` |
| F6 | Stale exclusions deleted; a case with no expectation now fails instead of passing silently | the official suite runs all 1 281 files (was 1 279) |
| F7 | Javadoc corrected to `MissingNode`; the bindings-dropping default now throws | — |

The lambda-as-node change (F3) removed a whole layer: scope-qualified token keys, the durable scope
registry, the per-evaluation lambda map and the defining-evaluation mode all went with it, and the
"Lambda expired or not found" class of failure no longer exists.

### Purity

`__blockN` orphans are gone (P1 — the higher-order built-ins no longer have their callback
translated twice); the `$eval` delegate is per expression instance with the global as a fallback
(P2); the four arithmetic operators share one operand check (P4); the loader is told the class name
rather than parsing it out of the source (P5); `evaluate(input, bindings)`'s default throws rather
than discarding the bindings (P6); small items in P7 done. P3 is done too: path compilation moved to a
`PathCodeGen` of its own (14 methods, 786 lines), taking `Translator.java` from 2 012 lines to
1 225 and `compilePathSteps` from 336 to 97 — its two largest branches, the context binding
(`@$v`) and the positional binding (`#$i`), are now named methods. No behaviour changed, which is
what the 2 656 tests confirm.

### Performance

The review asked for the throughput items; the target then became the benchmark ratio itself. Both
libraries measured in the same JVM, `PerformanceComparisonTest`, 100 000 evaluations:

| | eval/s | vs JSONata4Java |
|---|---|---|
| Before | 59,508 | 24.6× |
| After | 102,738 | **41.5×** |

What produced it, in order of contribution:

1. **Literal nodes hoisted to static fields** (+29%). `employees[level = "senior"]` allocated a
   fresh `TextNode` for every employee on every evaluation; a value node is immutable, so one
   instance per class serves every evaluation on every thread.
2. **`missing()` as a pointer comparison** (+9%), and the same for every other missing check in the
   runtime. `MissingNode` is a singleton; `isMissingNode()` against the dozen node types a path
   expression touches is a megamorphic call that cannot be inlined.
3. **`$round` without `BigDecimal`** in the common case, **`deepEquals`/`isTruthy` as one type
   dispatch** instead of a chain of `isX()` calls, and the deadline check skipped outright until
   some expression actually sets a timeout (+13% together).
4. **Object constructors with literal keys** build an exactly-sized map through `objectOf`, with the
   key array hoisted too, and the duplicate-key check reusing the insert's own lookup.
5. **`$count(seq[field = literal])` fused** into a single loop with the comparison chosen once,
   outside it.
6. C1 (bindings merged per call) had already been fixed as a correctness-adjacent item: an
   expression with ten permanent bindings went from 198.6 ns to 19.9 ns per evaluation.

C4 and C5 were done as described. **C3 was wrong and is reverted**: halving the comparator calls in
`$sort` breaks stability, because the second call is what distinguishes "equal" from "before" — the
official suite's sort-by-price case caught it immediately. The two-call shape is inherent to a
comparator that answers only "should $a come after $b?".

Remaining floor: roughly 45% of evaluation time is now `HashMap.getNode` — Jackson field lookups.
That is the data model, not the compiler.

The suite itself was the other place paying for per-expression compilation: it compiled every one of
its ~1 500 expressions individually. Pre-compiling them in batches of 250 — skipping the cases that
assert a compilation error, since a batch aborts as a whole — took the official suite from **140.8 s
to 29.3 s**.

### Memory

Class bytes are dropped after `defineClass` (M1); `toText` no longer deep-copies containers to strip
sentinels, since there are no sentinels to strip (M2); `$eval` compilations are cached, so a repeated
`$eval` no longer leaks a class and a classloader per call (M3); the per-evaluation lambda map that
M4 was about no longer exists.
