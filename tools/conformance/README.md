# Conformance harness

Differential testing of this port's built-ins against the reference JSONata interpreter.
The method and its findings are written up in
[docs/design/CONFORMANCE-REVIEW.md](../../docs/design/CONFORMANCE-REVIEW.md).

Three engines share one I/O contract — a JSON array of `[{id, expr, input}]` in, an object
of `{id: {"ok": value} | {"err": "eval:CODE"}}` out — so their outputs diff directly:

| engine | runner |
|---|---|
| `jsonata` (the reference, the arbiter) | `jsonata2js/_xrun_ref.js` in the sibling repo |
| jsonata2js (a third voice) | `run_js.js` |
| jsonata2py (a fourth voice) | `run_py.py` |
| this port | `src/test/java/…/conformance/ProbeRunner.java` |

Reading two implementations side by side finds candidate differences but cannot say which
is right; the reference decides.

## Scripts

| script | what it does |
|---|---|
| `gen_corpus.js <out>` | the mixed cross-port corpus (~3,100 cases, every area) |
| `gen_sweep.js <area> <out>` | one wide sweep: `numbers`, `formatNumber`, `integers`, `datetime`, `regex`, `strings` |
| `gen_fusion.js <out>` | 373 blocks that bind a sequence and interrogate it — the shape `SequenceScanFusion` rewrites |
| `gen_sibling.js <out>` | the findings the sibling ports recorded, as questions for this one |
| `gen_argfuzz.js <names> <out> [n]` | every built-in against an adversarial argument pool |
| `scan_leaks.js <cases> <out>` | reports results whose error is not a JSONata error (no ref needed) |
| `run_js.js <cases> <out>` | evaluates the corpus with jsonata2js |
| `run_py.py <cases> <out> [src]` | evaluates the corpus with jsonata2py |
| `arbitrate.js <cases> <java> <ref> [js]` | counts divergences and says who the reference agrees with |
| `byarea.js <cases> <java> <ref> [js]` | the same, broken down by the corpus's area tags |
| `show.js <cases> <java> <ref> <area> [n]` | prints the diverging cases for one area |

`run_js.js` resolves jsonata2js by absolute path; edit the `require` if the sibling repo
lives elsewhere. `run_py.py` takes the jsonata2py source directory as an optional third
argument, defaulting to the same sibling layout.

Bringing both sibling ports in is worth the trouble when this port disagrees with the
reference: if the siblings disagree the same way, the difference is in territory every
reimplementation gets wrong — which is a different finding from one this port owns.

## Running

`ProbeRunner` needs `target/cp.txt`, the dependency classpath. `mvn clean` deletes it and
`test-compile` does not put it back, so regenerate it after a clean build — otherwise the
runner fails and, if an output file from an earlier run is still lying around, the
arbitration silently reports *those* results:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
```

```bash
node tools/conformance/gen_corpus.js cases.json
node ../../js/jsonata2js/_xrun_ref.js "$PWD/cases.json" "$PWD/ref.json"
node tools/conformance/run_js.js      "$PWD/cases.json" "$PWD/js.json"

mvn -q test-compile
java -Xmx4g -cp "target/test-classes;target/classes;$(cat target/cp.txt)" \
     org.json_kula.jsonata_jvm.conformance.ProbeRunner cases.json java.json

node tools/conformance/byarea.js cases.json java.json ref.json js.json
```

`ProbeRunner` bounds each probe with a wall clock, because a pathological probe is a
finding in its own right rather than a reason to lose the other 3,000 results.

### Leak scanning

`scan_leaks.js` needs no oracle. It reads a `ProbeRunner` output and reports every result whose
error is not a JSONata error — `ProbeRunner` falls back to the exception's class name when there
is no code, so a host exception that escaped shows up as `eval:NullPointerException` or
`eval:JsonataEvaluationException`. Those are bugs regardless of what the reference does: the
caller gets an error it cannot match on, carrying an internal class name.

```bash
node tools/conformance/gen_argfuzz.js builtins.txt fuzz.json 2
java -cp "target/test-classes;target/classes;$(cat target/cp.txt)" \
     org.json_kula.jsonata_jvm.conformance.ProbeRunner fuzz.json fuzz_out.json
node tools/conformance/scan_leaks.js fuzz.json fuzz_out.json   # exits non-zero if any leaked
```

`builtins.txt` is one name per line; generate it from the signature table:

```bash
grep -o 'Map.entry("[a-zA-Z0-9]*"' \
     src/main/java/org/json_kula/jsonata_jvm/translator/BuiltinSignatures.java \
  | sed 's/Map.entry("//;s/"//' | sort -u > builtins.txt
```

**Generate the reference output in the same UTC day you evaluate it.** A picture with no date
component defaults to today — `$toMillis("12:30", "[H01]:[m01]")` is one such case in the mixed
corpus — so a `ref.json` captured before UTC midnight and arbitrated after it reports a spurious
divergence, off by exactly 86,400,000. Re-run the oracle before believing a count that moved by
one.

`PictureBench` (same package) times the picture-string built-ins; it reports the minimum of
several rounds, since a single round varies by more than the effects worth measuring.
