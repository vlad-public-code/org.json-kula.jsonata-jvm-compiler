# Numeric Functions — Implementation Reference

This document covers the design of the numeric built-in functions in
`org.json_kula.jsonata_jvm.runtime.numeric`.

---

## Package layout

| Class | Visibility | Responsibility |
|---|---|---|
| `NumericBuiltins` | `public final` | Entry point delegated from `JsonataRuntime`; implements `$number`, `$round`, `$random`, `$formatBase`, `$formatNumber`, `$formatInteger`, `$parseInteger` |
| `DecimalPicture` | package-private | XPath/JSONata decimal picture-string engine used by `$formatNumber` |
| `IntegerPicture` | package-private | Integer picture-string engine used by `$formatInteger` / `$parseInteger` |
| `EnglishWords` | package-private | English word-number conversion used by picture `w`/`W`/`Ww` |

---

## $number

Coerces a value to a JSON number.

- **boolean** → 0 or 1.
- **string** — supports decimal literals, and radix literals `0x…` (hex), `0o…` (octal), `0b…` (binary). A leading minus sign is accepted before the radix prefix (`"-0x1A"` → -26).
- **NaN / Infinity** — always throws `D3030` (never returned as a value).
- **null, array, object, lambda, regex** → throws `T0410`.

## $round

Uses `BigDecimal.HALF_EVEN` (banker's rounding). Precision defaults to 0 (round to integer). The precision argument may be negative to round to tens, hundreds, etc.

## $random

Uses `ThreadLocalRandom.current().nextDouble()` to avoid contention when expressions are evaluated concurrently.

## $formatBase

Converts a number to a string in the given radix (2–36). The number is rounded to the nearest integer before conversion. Throws `D3100` for out-of-range radix.

---

## $formatNumber — decimal picture strings

Delegates to `DecimalPicture.format(...)`. The picture string follows the W3C XSLT 2.0 §16 specification:

- **Mandatory digit** — `0` (or the active zero-digit character).
- **Optional digit** — `#`.
- **Decimal separator** — `.` (default) or the `decimal-separator` option.
- **Grouping separator** — `,` (default) or the `grouping-separator` option.
- **Percent / per-mille** — `%` / `‰` scale the value before formatting.
- **Pattern separator** — `;` separates the positive and negative sub-pictures.
- **Exponent separator** — `e` separates mantissa from exponent in scientific notation.

The `options` object keys: `decimal-separator`, `grouping-separator`, `exponent-separator`, `percent`, `per-mille`, `zero-digit`, `digit`, `pattern-separator`, `minus-sign`.

---

## $formatInteger — integer picture strings

Delegates to `IntegerPicture.format(long n, String pic)`.

### Named pictures

| Picture | Output |
|---|---|
| `w` | lowercase English words ("one hundred and twenty-three") |
| `W` | UPPERCASE English words |
| `Ww` | Title Case English words |
| `I` | ROMAN NUMERALS (1–3,999,999; 0 returns `""`) |
| `i` | roman numerals (lowercase) |
| `A` | Alphabetic label (A, B … Z, AA, …) |
| `a` | alphabetic label (lowercase) |

### Ordinal modifier

Appending `;o` to any picture requests ordinal output:

- Decimal pictures: append `st`/`nd`/`rd`/`th` (sign-aware: `-1st`, `-11th`).
- Word pictures: irregular ordinal words ("first", "second", "twelfth", …).

### Decimal grouping

Standard patterns (e.g. `#,##0`) are handled by `java.text.DecimalFormat`. Custom grouping (`:` separator, or more than one grouping level) is handled by `IntegerPicture.applyCustomGrouping`, which strips the sign, formats the absolute value, then re-prepends the sign.

### Numbers beyond `long` range

For values outside `[Long.MIN_VALUE, Long.MAX_VALUE]` only word pictures (`w`/`W`/`Ww`) are supported. The algorithm in `EnglishWords.toWordsDouble` repeatedly divides by 10¹² (trillion) until the remainder fits in a long, then formats the remainder normally and appends `" trillion"` once per division. Example:

```
1e46 / 1e12 = 1e34  → 1 trillion
1e34 / 1e12 = 1e22  → 2 trillions
1e22 / 1e12 = 1e10  → 3 trillions
toWords(10_000_000_000) = "ten billion"
result = "ten billion trillion trillion trillion"
```

---

## $parseInteger — integer picture strings

Delegates to `IntegerPicture.parse(String s, String pic)`. Parsing is the inverse of formatting:

| Picture | Parser |
|---|---|
| `w`, `W`, `Ww` | `EnglishWords.parseWords` |
| `I`, `i` | `IntegerPicture.parseRoman` (empty string → 0) |
| `A`, `a` | `IntegerPicture.parseAlpha` |
| decimal | `java.text.DecimalFormat` after stripping grouping separators |

### English word parsing algorithm

`EnglishWords.parseWords` uses a two-level stacking algorithm:

- **Sub-total** accumulates ones/tens/hundreds.
- **Hundred** multiplies the sub-total (or 1 if sub-total is 0).
- **Magnitude words** (thousand, million, …) commit the sub-total to the running total, then reset the sub-total. Two modes depending on whether the new magnitude is larger or smaller than the previous:

  | Mode | Condition | Action |
  |---|---|---|
  | **Descending** (normal) | `val ≤ lastMagnitude` | `total += subtotal × val` (independent group) |
  | **Ascending** (multiplicative) | `val > lastMagnitude` | `total = (total + subtotal) × val` |

  Examples:
  - "one million one thousand" → 1,001,000 (descending: independent groups)
  - "one thousand trillion" → 10¹⁵ (ascending: 1000 × 10¹²)

---

## Cross-package access

`NumericBuiltins` is in the `runtime.numeric` package and cannot directly access package-private members of `runtime` (`LambdaRegistry`, `RegexRegistry`). Two public delegates were added to `JsonataRuntime`:

```java
public static boolean isLambdaToken(JsonNode node) { ... }
public static boolean isRegexToken(JsonNode node)  { ... }
```

Three helpers were also promoted from package-private to `public static` in `JsonataRuntime`: `numNode(double)`, `missing(JsonNode)`, `toText(JsonNode)`.
