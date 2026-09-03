# String Functions — Implementation Reference

This document covers the design of the string built-in functions in
`org.json_kula.jsonata_jvm.runtime.string`.

---

## Package layout

| Class | Visibility | Responsibility |
|---|---|---|
| `StringBuiltins` | `public final` | Entry point delegated from `JsonataRuntime`; implements all `$string*`, `$length`, `$substring*`, `$uppercase`, `$lowercase`, `$trim`, `$contains`, `$split`, `$join`, `$match`, `$replace`, `$pad`, `$eval`, `$base64encode`, `$base64decode`, `$encodeUrl*`, `$decodeUrl*` |
| `RegexOps` | package-private | `bytePosToCharPos`, `expandReplacement`, `matchWithLambda` — shared by `$match` and `$replace` |
| `UrlCodec` | package-private | `encode` and `decode` — shared by `$encodeUrl`, `$encodeUrlComponent`, `$decodeUrl`, `$decodeUrlComponent` |

---

## Cross-package access

`StringBuiltins` lives in `runtime.string` and cannot access package-private members of `runtime`
directly. The following delegates / visibility changes were made to `JsonataRuntime`:

| Added / changed | Purpose |
|---|---|
| `public static EvalDelegate getEvalDelegate()` | Was package-private; needed by `$eval` |
| `public static JsonNode sanitizeForString(JsonNode)` | Was package-private; needed by `$string` prettify |
| `public static org.joni.Regex lookupRegex(JsonNode)` | New delegate to `RegexRegistry.lookupRegex` |
| `public static org.joni.Regex buildLiteralRegex(String)` | New delegate to `RegexRegistry.buildLiteralRegex` |
| `public static boolean isLambdaToken(JsonNode)` | Already added for `NumericBuiltins` |
| `public static boolean isRegexToken(JsonNode)` | Already added for `NumericBuiltins` |
| `public static JsonNode fn_apply(JsonNode, JsonNode)` | Already public; used for lambda replacement in `$replace` |

---

## Bug-fixes (vs the original `runtime.StringBuiltins`)

### Locale-sensitive case conversion

`$uppercase` / `$lowercase` previously called `String.toUpperCase()` / `String.toLowerCase()`
without a locale, so Turkish deployments (where `"i".toUpperCase()` → `"İ"`) would produce
wrong results. Both now use `Locale.ROOT`.

### `$trim` type-checking

`$trim` with a non-string argument silently returned MISSING instead of throwing `T0410`.
The behaviour is now consistent with every other string function.

### `$trim` regex pre-compilation

The `\\s+` pattern was compiled on every call via `String.replaceAll(...)`. It is now a
`static final Pattern WHITESPACE` compiled once at class-load time.

### `$split` limit ignored for empty separator

`$split("hello", "", 3)` returned all 5 characters instead of 3 because the empty-separator
fast path (`codePoints().forEach(...)`) never checked the `lim` variable. Fixed by converting
to an array and slicing to `min(lim, length)`.

### `$base64encode` type-checking

Previously returned MISSING silently for non-string arguments. Now throws `T0410`.

### `$base64decode` error handling

Wraps `IllegalArgumentException` from `Base64.getDecoder().decode()` in a
`RuntimeEvaluationException` with a descriptive message instead of letting the raw exception
propagate.

---

## Performance improvements (vs the original `runtime.StringBuiltins`)

### `bytePosToCharPos` — no per-codepoint allocation

The original converted each codepoint to a temporary `String` to obtain its UTF-8 byte length:
```java
b += new String(Character.toChars(cp)).getBytes(UTF_8).length;
```
This allocates two objects per codepoint. The replacement computes the byte length
arithmetically in O(1) per codepoint:
```java
b += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
```

### `percentEncode` — HEX table instead of `String.format`

`String.format("%%%02X", v)` inside the per-byte encoding loop parsed the format string on
every call. Replaced with a pre-allocated `static final char[] HEX` table:
```java
sb.append('%').append(HEX[v >> 4]).append(HEX[v & 0xF]);
```

### `expandReplacement` — dead code removed

The original had a j-reset + re-scan block that was a no-op (j ended at the same position
after the reset). The block is now removed.

---

## Regex operations (`RegexOps`)

### `bytePosToCharPos(String s, int bytePos)`

Translates a byte offset in the Joni (UTF-8) match result back to a Java `String` character
index (UTF-16). Used by `$match` and `$replace` to populate the `index` field of match objects.

### `expandReplacement(String repl, String wholeMatch, byte[] bytes, Region region)`

Expands a JSONata replacement string:
- `$0` → the whole match string
- `$N` (N ≥ 1) → capture group N (empty string if the group did not participate)
- `$$` → a literal dollar sign
- Multi-digit group references use greedy longest-first matching

### `matchWithLambda(String s, JsonNode pattern, int limit)`

Drives `$match` when the pattern is a custom lambda matcher. The protocol:
1. First call: `fn_apply(pattern, textNode(s))` — the lambda receives the full input string.
2. Subsequent calls: `fn_apply(next, NULL)` — the `next` function returned in the previous
   result is called with a dummy argument.
3. Stops when the result is MISSING, non-object, fields are missing, or `next` is not a lambda.

---

## URL codec (`UrlCodec`)

### `encode(String s, boolean preserveReserved)`

Percent-encodes to match JavaScript's `encodeURIComponent`/`encodeURI`, which is *not*
quite RFC 3986's unreserved/reserved split.

- **`preserveReserved = false`** (`$encodeUrlComponent`): `A-Za-z0-9` and
  `- _ . ~ ! * ' ( )` pass through unencoded. The four sub-delimiters `! * ' ( )` are
  RFC-reserved but `encodeURIComponent` leaves them alone.
- **`preserveReserved = true`** (`$encodeUrl`): additionally keeps `# $ & + , / : ; = ? @`.
  Note that `[` and `]` are *not* kept — `encodeURI` escapes them, though RFC 3986 lists
  them as reserved.
- Lone surrogates throw `D3140` immediately (detected before UTF-8 encoding).

### `decode(String s)`

Decodes percent-encoded sequences.

- Throws `D3140` for: incomplete `%XX` sequences, invalid hex digits, literal non-ASCII
  characters (must be percent-encoded), and byte sequences that are not valid UTF-8.

---

## Zero-length matches, and the shared regex cursor

`$match`, `$split`, `$replace` and `$contains` all drive one cursor
(`RegexOps.MatchCursor`). They used to run their own scan loops and disagreed: only
`$replace` raised `D1004`, and it raised it on the *first* empty match.

The rule is subtler than "reject empty matches". The cursor is the previous match's end,
and the guard fires only on a **produced subsequent** empty match — so
`$match("abc", /$/)` is legal (one match at index 3) while `$match("abc", /^/)` is `D1004`,
because that is the case that could never progress.

The guard is also what bounds the scan. Joni re-anchors `^` at the subject start, so a scan
told to resume past a match still found offset 0; without the guard the cursor never
advanced and the call built matches until the heap was exhausted.

## Regex flags are JavaScript's, not Oniguruma's

Joni is Oniguruma, and its option names do not mean what the JavaScript flag letters of the
same name mean. `RegexRegistry.joniOptions` maps them:

| JSONata flag | Oniguruma option |
|---|---|
| *(none)* | `SINGLELINE` — `^`/`$` anchor the whole string |
| `m` | *no option* — `^`/`$` are line anchors |
| `i` | `IGNORECASE` |

(Oniguruma's `MULTILINE` is JavaScript's `/s`, which JSONata does not expose.)

Two constructs need rewriting on top of that, in `RegexRegistry.toEcmaScriptDialect`:
`$` becomes `\z` outside `/m`, because Oniguruma's `$` still matches before a trailing
newline; and `.` becomes an explicit class excluding all four ECMAScript line terminators,
where Oniguruma's excludes only `
`. Under `/m` the anchors are widened the same way.

See [design/CONFORMANCE-REVIEW.md](design/CONFORMANCE-REVIEW.md) §6-7.
