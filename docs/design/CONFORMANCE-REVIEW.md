# Conformance review — built-in functions, differentially tested against the reference

**Date:** 2026-09-02
**Environment:** Temurin JDK 21.0.10, Windows 11, Joni 2.2.3, Jackson 2.21.2
**Prompted by:** a comparison of this port's built-in implementations against the sibling
[jsonata2js](https://github.com/vlad-public-code/jsonata2js) port.

This port passes all 1,281 files of the official JSONata acceptance suite, and did so
throughout. Everything below is behaviour the official suite does not cover.

## Why the two ports differ at all

jsonata2js is a **near-verbatim port of the reference `jsonata` package's own
`src/functions.js` and `src/datetime.js`** — its `datetime.js` still carries upstream's
TODO comments verbatim. This port instead hand-wrote its built-ins against the *specs*
(XSLT 2.0 §16, XPath F&O §9.8), which is a reasonable thing to have done and is why the
two disagree: a spec admits readings that an implementation has already resolved, and
JSONata's observable behaviour is defined by the implementation.

The sibling Python port hit the same asymmetry and reached the same verdict. Its review
found 115 divergences and the reference sided with jsonata2js on all 115.

## Method: three engines, one oracle

Reading two implementations side by side finds candidate differences but cannot say which
is right. So all three engines were run over the same probe corpus and the results diffed:

1. **jsonata-jvm-compiler** — this port.
2. **jsonata2js** — the sibling port.
3. **`jsonata` 2.2.2** — the reference interpreter, the arbiter.

A 3,132-case corpus covering `$string`, the string functions, the numeric and date/time
picture engines, the codecs, the regex built-ins and the sequence/object built-ins
produced **734 divergences**. Arbitrating each against the reference:

| reference agrees with | count |
|---|---:|
| jsonata2js | **733** |
| jsonata-jvm-compiler | **0** |
| neither | 1 |

The one case where neither port was right is `$sort([{"a":1}])`: both raise D3070, but the
default comparator is never invoked for a single element, so the reference sorts it fine.
That one is now fixed here and remains wrong in jsonata2js.

The result settled the question: on every one of these the JS port was right, so the work
was to port the reference's algorithm, not to negotiate between two readings of a spec.

> The 734 figure is measured over 3,076 of the 3,132 cases. The other 56 use a `^`
> pattern, and the pre-fix build **cannot evaluate them at all** — see finding 6.

## Results

| corpus | before | after |
|---|---:|---:|
| the 3,076-case cross-port corpus | 734 divergences | **16** (all under *Known divergences*) |
| `Number::toString` / `$floor` / `$ceil` sweep (2,688 cases) | 446 | **0** |
| `$formatNumber` sweep (660 cases) | 185 | **0** |
| `$formatInteger`/`$parseInteger` sweep (1,530 cases) | 649 | **0** |
| date/time sweep (2,634 cases: every component × modifier × instant) | 1,471 | **20** (18 are a reference crash) |
| regex sweep (1,633 cases: 23 patterns × 17 subjects × 4 built-ins, plus limits) | 396 | **0** |
| string/codec sweep (240 cases) | 3 | **0** |
| official acceptance suite | 1,281 passed | **1,281 passed** |
| unit tests | 2,712 | **2,855** |

## What was wrong, and what was ported

### 1. `$string` was not ECMA-262 `Number::toString` — 446 of the sweep

A JSONata number is an IEEE double and renders by JavaScript's own rules: integers via
`String(v)`, non-integers via `String(Number(v.toPrecision(15)))` — the 15-digit rounding
sheds float noise so `1/3` is `"0.333333333333333"`, and an integer is *not* rounded,
because that turns 2^70 into `"1.18059162071741e+21"`.

`numberToString` hand-rolled the notation rules and diverged on subnormals
(`$string(1e-320)` gave `"9.99988867182683e-321"`), on the 1e21 exponential switch, on
integers beyond 2^53, and on the lower switch — its `"0.0000"`-prefix test put `0.00001`
into scientific notation where JavaScript prints it plainly.

It is now the spec algorithm. Java's `Double.toString` is *almost* the shortest
round-tripping form but guarantees a digit after the point, so `Double.MIN_VALUE` prints
as `"4.9E-324"` where the answer is `"5e-324"`; the digits are therefore found by
searching upward for the fewest that survive a round trip.

`$floor`/`$ceil` fed it wrong values too: a raw `(long)` cast saturates, so `$floor(1e21)`
became 9223372036854775807.

> **Performance note.** The integral fast path is bounded at **2^53, not at the 1e21
> notation boundary**. Above 2^53 a double's exact integer value has more digits than its
> shortest round-tripping form, so widening it is a tempting optimisation that silently
> breaks `$string(12345678901234567890)`. `NumberRendering` pins it.

### 2. `x & ""` was optimised away

The optimizer rewrote `x & ""` → `x`. That is only the identity when `x` is already a
string: `&` stringifies, so `5 & ""` is `"5"` and `[1,2] & ""` is `"[1,2]"`. Every such
expression silently returned its unconverted operand. The rewrite now applies only when
both sides are string literals, where it is a plain constant fold.

This one is not a port of anything — it is a bug the cross-port comparison exposed because
the corpus used `x & ""` as a stringify idiom.

### 3. `$formatNumber` was structurally the wrong algorithm — 185 of the sweep

`DecimalPicture` was a `java.text.DecimalFormat`-shaped pipeline written against the XSLT
2.0 §16 picture *grammar*. The visible symptoms — no leading-zero elision
(`$formatNumber(0.5, "#.##")` gave `"0.5"` not `".5"`), a trailing decimal separator never
suppressed, an exponent search that scaled to the wrong mantissa — traced to different
parts of one structural mismatch rather than to patchable rules, so it was rewritten as a
port of XPath 3.1 F&O `fn:format-number` (§4.7.3 validate, §4.7.4 analyse, §4.7.5 bullets
2-14).

The algorithm is *defined over* ECMAScript numeric primitives and its output depends on
them, so `toFixed` and the half-even rounding reproduce those rather than substituting
Java's near-equivalents. Two consequences are worth stating because they look like bugs:

- `toFixed` hands off to the plain number rendering at 1e21, so `$formatNumber(1e21, "0.00")`
  really is `"1e+21.00"` — the exponent leaks into fixed-point output.
- Irregular grouping positions are spliced with JavaScript's `slice`, whose negative-index
  behaviour *drops digits*: `$formatNumber(1234.5678, "#,##,##0")` is `"12,35,"`. Clamping
  the index instead reads better and disagrees with the reference on every short value.

Rounding now goes through `$round`'s own routine, so the two can no longer round
differently.

### 4. `$formatInteger` mishandled every negative — 649 of the sweep

Mandatory-digit padding, the ordinal suffix, and the roman/alpha/word conversions all have
to apply to the **absolute** value with the sign prepended. Sign was participating instead,
so `-7` with `"01"` gave `"-7"` rather than `"-07"`, and the roman and alphabetic pictures
rejected negatives outright. Also in the same area:

- **The picture analysis was wrong about digit positions.** `"0001"` declares four
  mandatory digits, not three; `"#"` declares *none*, which makes it a "numbering
  sequence" — implementation-defined, and D3130 rather than plain decimal output.
- `$formatInteger` truncated where the reference floors, so `-12.6` gave `-12`.
- Roman numerals were capped at 3,999,999 and rejected 0; above 3,999 the reference simply
  repeats `M`, and 0 is the empty string. Alphabetic rejected 0 where the answer is `""`.
- The English word generator used "and" everywhere and prefixed "minus". The reference
  puts `", "` at a magnitude or hundreds boundary and `" and "` before a smaller
  remainder — 1970 is `"one thousand, nine hundred and seventy"` — and the sign is a
  literal `"-"`.

`analyse` and `format` were split so the analysis is reusable, which is what finding 5
needs.

### 5. Date/time components ignored most picture modifiers — 1,471 of the sweep

XPath F&O §9.8.4.3 routes every integer-valued component through the *same* integer
formatter `$formatInteger` uses, which is what the reference does. `PictureFormatter`
instead hand-wrote each component with its own modifier handling, so `[Ya]`, `[YA]`,
`[YWw]`, `[Di]`, `[Mw]`, `[DW]` and friends silently fell back to plain decimal or threw.
It now delegates to `IntegerPicture`, and that single change is what makes all of them work
without a line of per-component code. Also fixed in the same area:

- **`[f]` is not a decimal fraction.** It formats the raw millisecond value through the
  integer path, so the width pads and never scales: `[f0001]` on 1 ms is `"0001"`, not the
  `"0010"` a scaled fraction gives, and `[f01]` on 150 ms is `"150"`, not `"15"`.
- **`[w]` week-in-month** used day-of-month/7 with ad-hoc corrections. The rule is ISO's,
  extended to months: the first week is the Monday-based week containing the month's first
  Thursday, so 1970-01-01 was reported as week 5 instead of week 1.
- **`[z]`** always rendered `GMT±HH:MM` and ignored its picture; it is `[Z]` with a `GMT`
  prefix.
- A year width **maximum** assigns the digit count rather than raising it, so
  `[Y0001,2-2]` yields two digits.

The `datetime` package's duplicate `RomanNumerals` and `WordNumbers` — less complete than
the numeric package's own — are no longer reachable from the formatter. They are still used
by `PictureParser`; consolidating that side is left as follow-up work.

### 6. The regex built-ins each ran their own scan — 396 of the sweep

Only `$replace` raised D1004, and it raised it on the **first** empty match, while `$match`
and `$split` never raised it and advanced a byte at a time. All four now share one cursor
carrying the reference's guard, whose subtlety is that it fires on a *produced subsequent*
empty match — so `$match("abc", /$/)` is legal (one match at index 3) while
`$match("abc", /^/)` is D1004.

**The absence of that guard was not only a wrong answer.** Joni re-anchors `^` at the
subject start, so a scan told to resume past a match still found offset 0; `pos = end + 1`
never advanced and the call built matches until the heap was gone. On the pre-fix build,
`$match(" nbsp ", /^/)` — a plain expression over ordinary input — exhausts the
heap. That is why 56 of the 3,132 probes had to be excluded to get a baseline number at
all. It now raises D1004.

Also in this area: `$match` singleton-collapses (one match is the bare object); a
non-participating capture group is `null`, not `""`; the replacer function receives the raw
matcher closure (`match`, `start`, `end`, `groups`, `next`), not the remapped shape
`$match` publishes; `$0` is recognised before any digit parsing, so `"$01"` is the whole
match followed by a literal `1`; and a negative limit is D3040 for `$match`.

`$split`'s two forms really do differ and are not one rule stated twice: the regex form
compares `count < limit` as a number, so 2.5 admits three pieces, while the string form
slices the finished array, so 2.5 gives two.

### 7. Joni's option names are not JavaScript's flag letters

The biggest single finding, and the one that had been hiding behind a plausible comment.
Joni is Oniguruma, and verified directly against it:

| what we want | Oniguruma option |
|---|---|
| JS default (`^`/`$` anchor the whole string) | `SINGLELINE` |
| JS `/m` (`^`/`$` are line anchors) | *no option* |
| JS `/s` (`.` matches a newline) | `MULTILINE` |

The code mapped `m` onto `MULTILINE`, which got it wrong twice: **every** pattern behaved
as if it carried `/m`, and an explicit `m` turned on dot-matches-newline instead. Two
narrower dialect differences remained after fixing the mapping, and both are now closed by
rewriting the pattern:

- Oniguruma's `SINGLELINE` `$` still matches before a single trailing newline (Ruby's
  `\Z`); ECMAScript anchors only at the very end, which is `\z`.
- ECMAScript's `.` excludes four line terminators (`\n \r` U+2028 U+2029) where
  Oniguruma's excludes only `\n`, and ECMAScript's `/m` anchors break on all four.
  `\x{2028}` is *not* supported by Joni's ECMAScript syntax — it silently consumed the
  following character rather than rejecting the pattern, which dropped one character in
  every other match — so the class is written with the literal characters.

The regex sweep is **0 divergences over 1,633 cases**, which closes every regex-dialect
family. The sibling Python port left several of these open.

### 8. base64 was not self-inverse

Encoding used latin-1 (correct: `btoa` gives each UTF-16 code unit's low byte) but decoding
used UTF-8, so `$base64decode($base64encode("é"))` returned U+FFFD. Decoding is now latin-1
too, and lenient like Node's `Buffer.from(s, "base64")`: characters outside the alphabet are
skipped and surplus padding ignored, so `"!!!!"` decodes to `""` rather than throwing.

`$encodeUrlComponent` escaped `! * ' ( )`, which `encodeURIComponent` leaves alone even
though RFC 3986 lists them as reserved.

### 9. `$round`'s fast path was unsound at its own ceiling

The tie window has to be sound for the ceiling it is paired with. `v * 10^p` is accurate to
about half an ulp *of the product*, and at the 1e15 ceiling an ulp is 0.125 — eight orders
of magnitude wider than the fixed `1e-9` window meant to catch a tie. So the fast path
sailed past genuine ties and answered confidently with the wrong value:
`$round(-36435.03133177965, 10)` gave `-36435.0313317797` where the true product ends in
`.5` and half-even gives `…796`.

The fix was not to invent a better window but to adopt the reference's own pairing (a 1e9
ceiling with a 1e-6 window) and its slow path, which shifts the decimal exponent through
the number's *string* form rather than multiplying — `8.835 * 100` is `883.4999999999999`,
but `8.835e2` is exactly `883.5`, which is what half-even has to be shown.

### 10. `$number` accepted a wider grammar than the reference

`Double.parseDouble` accepts `" 1 "`, `"1."`, `".5"` and `"+1"`; the reference's grammar is
`-?[0-9]+(\.[0-9]+)?([Ee][-+]?[0-9]+)?`, and everything else is D3030.

### 11. Sequence, object and evaluation-model fixes

`$clone` was **not registered at all** (T1006). `$reverse` of a non-array must array-wrap
(`<a:a>`). `$keys`/`$lookup` must recurse into nested arrays. `$spread` of a single-key
object collapses to the bare object. `$reduce` of a missing sequence is missing, not the
initial value, and a *dynamic* reducer's arity is now checked — the translator checked a
literal `function($a,$b){…}`, but a variable holding one, or a built-in like `$sum`, reached
the runtime unchecked and was invoked with the packed 4-element tuple. `$sort` without a
comparator reports **D3070** for a number/string mix (T2007/T2008 belong to the `^(key)`
order-by operator) and succeeds on a one-element array, where no comparison happens.

`$toMillis` now lets an in-range day roll over — the reference does not validate the day
against the month's length, so `"2023-02-29"` is 1 March. A nested `$eval` inherits the
outer evaluation's clock snapshot, so `$eval("$millis()") = $millis()` is true.

### 12. A bare name in a path step was not resolved as a field

`a.g(...)` calls the **field** `g` of the step context — never a variable, and never a
built-in, so `$o.count()` is `$o`'s own `count` and `$count` is only reachable by writing
the `$`. The parser produced the same node for `$o.g()` and `$o.$g()` — the `$` was
stripped and nothing recorded that it had been there — so the translator resolved every
call as a variable or built-in and `$o.g()` was T1006. `($o.g)()` always worked, which is
what made it look like a niche problem rather than a whole syntax being unreachable.

`FunctionCall` gained an `isVariable` flag, defaulting true so every synthetic
construction site keeps the meaning it had; only the parser sets it false, via a *one-shot*
flag armed around a dot-step's primary. One-shot matters: `$o.g($count(x))` must not
resolve the built-in in the argument as a field too.

The receiver rules are subtler than "map over the sequence". An absent receiver or empty
sequence yields absent without resolving the callee, but a **JSON null** does not — it is
T1006, like any other value with no such field. A missing field whose name is also a
built-in reports T1005 ("did you mean `$count`?") rather than a bare T1006, and whether the
name is a built-in is known at compile time, so it is baked into the emitted call.

Two neighbouring things were wrong and are fixed with it: a bare number, boolean or null
literal cannot be a path step (S0213 — `(5).g()` is fine, `5.g()` is not), and two
zero-parameter lambdas nested in one generated method both declared the same unused
parameter name, which does not compile. The latter is pre-existing; chaining field calls
is simply the first thing that produced it.

### 13. Context substitution was decided per built-in, not by the signature

`a.$fn()` does not automatically hand `a` to `$fn`. A parameter marked `-` is filled from
the context; a built-in without one is called with exactly the arguments written. That is
why `"hi".$uppercase()` is `"HI"` — `<s-:s>` — while `[1,2].$count()` is T0410, because
`<a:n>` never takes the context.

Each built-in's arm decided this for itself. Several were simply wrong (`$count`,
`$reverse`, `$type`, `$max`, `$sort` all substituted a context their signature does not
declare), and several indexed off the end of the argument list — `(2).$power(3)`,
`[1,2].$append(3)`, `"hi".$substring(1)` and four others **crashed the translator** with an
`IndexOutOfBoundsException`.

`BuiltinSignatures` now carries the reference's own signature table, read out of the
`jsonata` package, and drives both decisions. Where a written argument's type is known at
compile time it also decides *which* parameter that argument occupies, because the
reference's rule is type-directed: `$substring(1)` substitutes the context because `1` fits
the second parameter, while `$split(12345)` does not, because a number cannot be a
separator and the argument itself is at fault. A wrong-typed context that is the only thing
filling a slot is T0411, not T0410.

Twenty-four arms keep their own handling, listed in `BuiltinSignatures.SELF_MANAGED`: they
encode type-directed decisions a signature alone cannot make, and the acceptance suite pins
them.

## Performance

Picture strings were re-analysed on every call, though a picture is a compile-time literal
at essentially every call site. They are now memoized in a bounded cache, and the analyses
were made immutable so a formatter cannot corrupt a shared one.

| call | before | after | |
|---|---:|---:|---:|
| `$fromMillis` with a picture | 3.573 | 0.432 µs/call | **8.3×** |
| `$formatInteger` | 0.606 | 0.119 µs/call | **5.1×** |
| `$formatNumber` | 1.120 | 0.952 µs/call | **1.2×** |
| `$toMillis` with a picture | 8.213 | 7.302 µs/call | **1.1×** |

*(Minimum of seven timed rounds of 200,000 calls after 50,000 warm-up iterations; a single
round on this machine varies by more than the effects being measured. `$formatNumber` and
`$formatInteger` also changed algorithm, so these are end-to-end figures, not cache
attribution.)*

The caches are bounded on **both** axes — 512 entries, and a picture longer than 256
characters is analysed afresh rather than retained — because a picture can arrive from
input data and its length is therefore caller-controlled. This follows `RegexRegistry`'s
existing shape.

The end-to-end benchmark is unchanged. Measured as an A/B in one session — a git worktree
at the pre-review commit against the working tree, two runs each, same machine:

| | run 1 | run 2 | run 3 |
|---|---:|---:|---:|
| before | 102,851 eval/s (40.89× JSONata4Java) | 96,890 (39.00×) | 93,759 (37.77×) |
| after | 106,865 eval/s (42.05×) | 97,170 (39.03×) | 94,742 (39.12×) |

The run-to-run spread is about 10%, larger than any difference between the two builds, so
this is "no measurable change" rather than a small win. A single reading either way would
have been meaningless — which is why the comparison is run as a paired A/B rather than
against a number quoted from an earlier session.

**The benchmark cannot answer every performance question, and should not be asked to.** Its
expression contains no path headed by an array constructor, so it is blind to the change in
§14. The decisive evidence there was a *codegen diff* — generating Java for the benchmark
expression and thirteen path shapes from both builds and comparing them byte for byte — not
a timing.

### 14. An array constructor heading a path

A path whose first step is a `[...]` constructor evaluates that constructor as a value
rather than iterating it, and an empty one ends the path there — uncollapsed, with the
remaining steps not evaluated. So `[].x` is `[]` while `empty.x`, the same value through
the same step, is undefined. All three sibling ports collapsed it; the reference does not.

Two things made this harder than the rule:

- **The optimizer erased the distinction the rule depends on.** `([]).x` and `[].x` optimise
  to the same tree, so the flag has to be recorded by the *parser*, as the reference does
  with `consarray`. `ArrayConstructor` gained a `pathHead` boolean, set by a single
  `newPath` helper replacing the parser's five `new PathExpr(steps)` sites.
- **A sort on the head parses differently here.** `[]^(x).y` is `SortExpr[ArrayConstructor]`
  rather than two steps, so the head arrives as `unwrap(sort(...))` — already collapsed.
  The guard therefore treats an absent head as empty, which is sound because the head is
  statically an array constructor and so can only be absent if a wrapper collapsed it.

**Performance.** The emptiness is decided at compile time wherever it can be. A constructor
with any element that always produces a value is provably non-empty, so the guard is elided
and the generated code is byte-identical to before; a literally empty one is constant-folded
and the remaining steps are never emitted. What remains guarded is only a constructor whose
emptiness depends on data (`[nope].x`, `[nums[false]].x`). Verified by codegen diff: the
benchmark expression and thirteen path shapes are unchanged, `[1,2,3].x` is unchanged, and
`[].x` compiles to a constant.

The official suite exercises this shape 18 times but never with an empty constructor, so it
guards nothing here in either direction — `ArrayConstructorPathHeadTest` (40 tests) is the
only thing that does. Written up for the sibling ports, which are not yet fixed, in
`../../../jsonata-conformance.md`.

## Known divergences

Deliberate, and each verified rather than assumed. These are the 19 that remain.

**Where the reference is wrong and this port is not:**

- `$fromMillis(ms, picture, "Z")` — the reference `parseInt`s the timezone to NaN and emits
  `"0NaN-NaN-NaNTNaN:NaN:NaN.NaN0N:aN"`. A real offset is produced instead. (3 cases)
- `$fromMillis(ms, "[fn]")` and `[fN]`, `[fNn]` — the reference throws a raw JavaScript
  `TypeError`, not a JSONata error. A D3133 is raised instead. (18 of the 20 remaining
  date/time sweep cases)

  > This entry was aspirational when first written: `f` is formatted on its own branch, which
  > asked for a name it cannot have, found no integer picture to fall back on, and let a
  > `NullPointerException` out as the user-visible error. The arbiter counted it as the same
  > divergence either way, because both sides raise *something* — which is how an entry in a
  > known-divergence list can go stale without any count moving. Fixed, and pinned by
  > `DateTimeFormattingTest.fractionalSecondWithANamePresentationRaisesD3133`; a sweep of every
  > component against `n`/`N`/`Nn` found no other component with the same hole.
- `$toMillis("2023-13-01")`, `"2023-00-01"`, `"2023-01-00"`, `"2023-01-32"` — the reference
  leaks a NaN/invalid-`Date` artefact and returns undefined. D3110 is raised instead.
  In-range days still roll over, which *is* reference behaviour. (4 cases)
- `$number("x0o17y")` — the reference's radix alternation is unanchored in the middle, so it
  matches and returns NaN. D3030 is raised instead. The *anchored* quirks of that same regex,
  such as `$number("0x1A ")` → 26, are reproduced faithfully. (1 case)
- `$fromMillis` of a year below 100 with `[X]` or `[x]` — the reference computes the week
  boundary with `Date.UTC`, which maps years 0-99 to 1900-1999, so year 1 reports the ISO
  week-year 0. This is a legacy JavaScript artefact, not a spec behaviour. (2 cases)

**Where the reference's behaviour is a JavaScript artefact we do not emulate:**

- `$match("😀a", /./)` → 3 matches there, 2 here; `$split("😀a", "")` → 3 elements there,
  2 here. The reference iterates UTF-16 code units, so `.` matches each half of a surrogate
  pair. Java `String` is UTF-16 too, so **match indices already agree** — it is only the
  granularity of `.` and of an empty-separator split that differs, because this port
  operates on code points there, as `$length` and `$substring` do. (6 cases)

**Where all three ports agree with each other and not with the reference:**

- An **array constructor heading a path, evaluating to empty**, yields `[]` in the
  reference and undefined in every port. It is observable, not cosmetic:

  | expression | reference | jsonata2js | jsonata2py | this port |
  |---|---|---|---|---|
  | `[].x` | `[]` | undefined | undefined | undefined |
  | `[nums[false]].x` | `[]` | undefined | undefined | undefined |
  | `$exists([].x)` | `true` | `false` | `false` | `false` |
  | `[].x = []` | `true` | `false` | `false` | `false` |
  | `$string([].x)` | `"[]"` | undefined | undefined | undefined |

  The trigger is syntactic — the path's head must be a `[...]` constructor — but its
  emptiness can come from data, as `[nums[false]].x` shows. Every other route to an empty
  sequence agrees across all four engines: `empty.x`, `nums[false].x`,
  `$filter(nums, function($v){false}).x`, `(nums[false]).x`, `($e := []; $e.x)`,
  `["a"].x` and `$count([].x)`.

  This is not a place this port lags its siblings. jsonata2js is a near-verbatim port of
  the reference's *built-ins*, but path evaluation is its own — as it is in jsonata2py and
  here — and all three reimplementations collapse the empty sequence. The reference is also
  internally inconsistent about it: `[].x` is `[]` while `empty.x`, the same value through
  the same step, is undefined, and `["a"].x` is undefined too, so it is not a general
  "keep the array" rule. It reads as an artefact of how its evaluator seeds the input
  sequence when the first step is an array constructor, and undoing the collapse here would
  change sequence semantics that the seven agreeing rows above depend on.

# Follow-up: the sibling ports' findings, and a leak sweep

**Date:** 2026-09-05

Two sweeps, both prompted by the question "is there anything left?" rather than by a symptom.
`tools/conformance/gen_sibling.js` and `gen_argfuzz.js` + `scan_leaks.js` are what they left
behind; `conformance/ReferenceParityTest` pins the results.

## 1. Asking the reference about the siblings' findings

jsonata2py's review closes with items it fixed, items it left open, and two pre-existing bugs it
surfaced but did not fix. None of that transfers by assumption — **a finding in one port is a
question for the others, never an answer** — so each became a probe here. 91 cases; 85 agreed
first time, and the six that did not were all this port's own bugs rather than the sibling's:

| finding | here |
|---|---|
| `$o.g()` calls a function held in a field | already correct — the `is_variable` fix has an equivalent here |
| `$replace`'s replacer gets the raw matcher closure | already correct — `$keys` reports `match/start/end/groups/next`, as the reference does |
| `$match(str, re, -1)` should be D3040 | already correct |
| a fractional limit compares rather than truncates | already correct |
| `[1,2].$count()`, `{"a":1}.$keys()` | agree with the reference here |
| `$base64decode` strictness | **divergent** — see below |
| nested `$eval` shares the outer clock | already correct |
| `$reduce` validates a dynamic reducer's arity | already correct |

The six real ones:

- **A zero-argument call bound the first parameter to JSON `null`.** The runtime convention was
  "no argument becomes NULL", which is right for a *bound* function's packing convention and
  wrong for an ordinary call: `(function($x){$exists($x)})()` was true and `$type($x)` was
  `"null"`. Both are undefined in the reference. Two emission sites, one word each.
- **`$match` never type-checked its optional limit**, though `$split` and `$replace` — the same
  `n?` slot — both did.
- **All three checked it in the wrong place.** The reference validates a call against its
  signature *before* the body runs, so `$split(nope, /a/, "1")` is T0410 there; here the
  undefined-argument short-circuit returned first and the bad argument was never seen.
- **A `$replace` replacer returning undefined substituted nothing** instead of raising D3012.
  The reference tests `typeof x === "string"` and rejects everything else; an explicit
  "undefined is allowed" guard here exempted exactly the case that matters, since a replacer
  navigating to a field the match object lacks is how you get there.
- **`$base64decode` skipped the pad character** rather than stopping at it. Skipping agrees with
  Node on every well-formed input, so only mid-string padding tells them apart (`"YW=J"`).

## 2. Leak sweep: no host exception should reach the caller

An error carrying a JVM class name instead of a JSONata code is one a caller cannot match on.
92,421 fuzz cases (every built-in, 0-3 arguments from an adversarial pool) found **123 leaks in
four families**, now zero:

| leak | cause |
|---|---|
| 111 × a Java **compilation error** | `$filter(function($x){$x}, [1,2])` — a lambda in the *sequence* slot was taken for the callback the built-in generates itself, so the "the generator will fill this in" placeholder was emitted into the sequence slot. A plainly wrong expression came back as `cannot find symbol` naming generated code. |
| 5 × `IndexOutOfBoundsException` | `$contains()`, `$eval()`, `$match()`, `$substringBefore()`, `$substringAfter()` indexed an empty argument list. |
| 5 × `NumberFormatException` | `$round(1, 1e15)`: `(int) 1e15` saturates, shifting a decimal exponent by `Integer.MAX_VALUE` produced the literal string `"Infinity"`, and the next parse choked on it. |
| 2 × **`OutOfMemoryError`** | `$pad("x", 1e15)` tried for a two-billion-character string. The worst of the four by some distance: it is not contained to the call that caused it. |

Fixing the first also fixed a wrong *answer*: `$reduce(function($a,$b){$a}, [1,2])` returned the
function and `$sort(fn, [1,2])` a one-element array holding it, where the reference raises T0410.

The `$round` fix is worth stating precisely, because the rule is not "a big precision is an
error": the reference computes `value * 10^precision`, so it depends on both. `$round(1, 308)`
is 1 and `$round(1e15, 308)` is undefined. Checking the shifted value for finiteness reproduces
that exactly, with no threshold to tune. Verified over a 144-case grid: 0 divergences.

## 3. What these two sweeps left open

- **`$pad` past the maximum string length** is D1001 here and `RangeError` — a raw host error,
  not a JSONata one — in the reference. Not reconcilable; a JSONata code is the better of the
  two, and unlike an `OutOfMemoryError` it is contained.
- **A boolean in a numeric argument is coerced here and rejected there.** `$round(true)`,
  `$abs(true)`, `$floor(true)`, `$ceil(true)`, `$sqrt(true)`, `$power(true,2)`,
  `$formatNumber(true,"0")`, `$formatInteger(true,"0")`, `$pad("x",true)`, `$round(1,true)` all
  answer here and are T0410 there — 11 of an 18-case probe. One root cause: `toNumber` accepts
  a boolean and the numeric built-ins call it without a type check. It is a family, not a case
  — every `n` parameter in the signature table — so it wants its own sweep rather than a partial
  fix, in the spirit of jsonata2py leaving its regex-dialect families whole.

## Where this port is better

Recorded so it is not "fixed" later. Each was checked with the harness rather than asserted.

- **Regex dialect coverage is complete.** The regex sweep is 0 divergences over 1,633
  cases. Joni with `Syntax.ECMAScript` over UTF-8, once its option names are mapped
  correctly and two constructs rewritten, gives real ECMAScript semantics for `^`, `$`,
  `.` and the line-break set. The sibling Python port needed a compensation layer and
  still left several dialect families open.
- **Match indices are UTF-16 for free.** Java `String` is UTF-16, so `$match` reports the
  same indices the reference does with no remapping layer — the thing the Python port
  explicitly declined to build.
- **`$sort([{"a":1}])`** is the one probe where the reference agreed with neither port.
  This port now matches it; jsonata2js still raises D3070.
- **Bounded LRU caches for compiled regexes and analysed pictures.** jsonata2js has none —
  it carries a literal `TODO can cache this against the picture`.
- **`EnglishWords` reaches quintillions**, against the reference's four magnitude words.
- **A pathological regex is bounded.** The shared cursor cannot loop; the pre-fix code
  could exhaust the heap from a plain expression.

## Reproducing

The harness lives in `tools/conformance/` (Node) and
`src/test/java/…/conformance/ProbeRunner.java` (the Java voice). All three engines share
one I/O contract — `[{id, expr, input}]` in, `{id: {ok}|{err}}` out — so their outputs diff
directly.

```bash
# 1. generate a corpus
node tools/conformance/gen_corpus.js cases.json
node tools/conformance/gen_sweep.js regex regex.json     # or numbers|formatNumber|integers|datetime|strings

# 2. the oracle, and the sibling port as a third voice
node ../../js/jsonata2js/_xrun_ref.js "$PWD/cases.json" "$PWD/ref.json"
node tools/conformance/run_js.js      "$PWD/cases.json" "$PWD/js.json"

# 3. this port
mvn -q test-compile
java -Xmx4g -cp "target/test-classes;target/classes;$(cat target/cp.txt)" \
     org.json_kula.jsonata_jvm.conformance.ProbeRunner cases.json java.json

# 4. arbitrate
node tools/conformance/arbitrate.js cases.json java.json ref.json js.json
node tools/conformance/byarea.js    cases.json java.json ref.json js.json
```

Gates:

```bash
mvn test -Dtest=ConformanceRegressionTest   # 85 cases, the regressions this review added
mvn test -Dtest=PathStepCallTest            # 58 cases, path-step call semantics
mvn test -Dtest=JsonataTestSuiteTest        # 1,281-case acceptance gate, must not regress
mvn test                                    # 2,855 tests
```

Every expectation in `ConformanceRegressionTest` and `PathStepCallTest` came from the
oracle, and each nested class names the root cause it pins, so a refactor that reverts one fails with an
explanation rather than a bare value mismatch.
