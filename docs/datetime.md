# Date/Time Functions — Implementation Reference

This document covers the JSONata date/time built-in functions (`$now`, `$millis`, `$fromMillis`, `$toMillis`) as implemented in this library.

## Package layout

```
org.json_kula.jsonata_jvm.runtime.datetime/
├── IsoConverter        — millisToIso / isoToMillis  (ISO 8601 only)
├── TimezoneUtils       — parseZoneOffset, normalizeOffsetInTimestamp
├── RomanNumerals       — toRoman, toArabic, isValid
├── WordNumbers         — toCardinal, toOrdinal, wordsToDigits
├── PictureFormatter    — format(millis, picture, timezone) → String
└── PictureParser       — parse(timestamp, picture) → long (epoch ms)
```

`DateTimeUtils` in `org.json_kula.jsonata_jvm.runtime` is a thin facade that delegates to the classes above. It is the only entry point called by `JsonataRuntime`.

---

## JSONata functions

| Function | Translator call | Notes |
|----------|----------------|-------|
| `$millis()` | `fn_millis()` | Returns evaluation-start epoch ms; same value for all calls within one evaluation. |
| `$now()` | `fn_now()` | Returns evaluation-start as ISO 8601 UTC string. |
| `$now(picture)` | `fn_now(picture)` | Formats with XPath/XQuery picture string. |
| `$now(picture, tz)` | `fn_now(picture, tz)` | Same, in the given timezone. |
| `$fromMillis(n)` | `fn_fromMillis(n)` | Epoch ms → ISO 8601 UTC string. |
| `$fromMillis(n, picture)` | `fn_fromMillis(n, picture)` | Epoch ms → picture-formatted string. |
| `$fromMillis(n, picture, tz)` | `fn_fromMillis(n, picture, tz)` | Same, in the given timezone. |
| `$toMillis(ts)` | `fn_toMillis(ts)` | ISO 8601 string → epoch ms. |
| `$toMillis(ts, picture)` | `fn_toMillis(ts, picture)` | Picture-formatted string → epoch ms. |

---

## Timezone format

Accepted by `$fromMillis`, `$now`, and `parseZoneOffset`:

| Format | Example | Notes |
|--------|---------|-------|
| `±HHMM` | `-0500`, `+0530` | JSONata spec default |
| `±HH:MM` | `-05:00`, `+05:30` | Also accepted |
| `GMT±HH:MM` | `GMT-05:00`, `GMT+5:30` | Also accepted |
| `Z` / `UTC` / empty | | UTC |

---

## Picture string components

Implements a subset of the XPath/XQuery `fn:format-dateTime` picture syntax.

### Date components

| Token | Description | Default modifier | Example output |
|-------|-------------|-----------------|----------------|
| `[Y]` | Year | none (no padding) | `2024` |
| `[Y0001]` | Year, 4-digit zero-padded | `0001` | `0024` |
| `[Y,2]` | Last 2 digits of year | | `24` |
| `[M]` | Month as number | none | `3` |
| `[M01]` | Month, 2-digit zero-padded | `01` | `03` |
| `[MN]` | Month name, uppercase | `N` | `MARCH` |
| `[MNn]` / `[Mn]` | Month name, title / lower | `Nn` / `n` | `March` / `march` |
| `[Mi]` | Month as lowercase Roman | `i` | `iii` |
| `[MI]` | Month as uppercase Roman | `I` | `III` |
| `[D]` | Day of month | none | `7` |
| `[D01]` | Day of month, 2-digit | `01` | `07` |
| `[Do]` | Day of month with ordinal suffix | `o` | `7th` |
| `[Dwo]` | Day of month as ordinal words | `wo` | `seventh` |
| `[d]` | Day of year | none | `42` |
| `[d001]` | Day of year, 3-digit | `001` | `042` |
| `[F]` | Day of week name, lowercase | none | `tuesday` |
| `[FN]` | Day of week name, uppercase | `N` | `TUESDAY` |
| `[FNn]` | Day of week name, title case | `Nn` | `Tuesday` |
| `[X]` | ISO week-based year | | `2024` |
| `[W]` | ISO week of year | | `08` |

### Time components

| Token | Description | Default modifier | Example output |
|-------|-------------|-----------------|----------------|
| `[H]` | Hour 0–23, no padding | none | `9` |
| `[H01]` | Hour 0–23, 2-digit | `01` | `09` |
| `[h]` | Hour 1–12, no padding | none | `9` |
| `[h#1]` | Hour 1–12, no leading zero | `#1` | `9` |
| `[m]` | Minute, no padding | none | `5` |
| `[m01]` | Minute, 2-digit | `01` | `05` |
| `[s]` | Second, no padding | none | `7` |
| `[s01]` | Second, 2-digit | `01` | `07` |
| `[f]` | Milliseconds, 3-digit | `001` | `121` |
| `[f01]` | Centiseconds, 2-digit | `01` | `12` (150ms → 15) |
| `[f1]` | Deciseconds, 1-digit | `1` | `1` (150ms → 1) |
| `[P]` | am/pm | none | `am` |
| `[PN]` | AM/PM | `N` | `AM` |

### Timezone components

| Token | Description | Example output |
|-------|-------------|----------------|
| `[Z]` | Offset as `±HH:MM` or `Z` | `+05:30`, `Z` |
| `[Z0]` | Offset, minimal width | `+5`, `-5`, `+0` for UTC |
| `[z]` | Offset as `GMT±HH:MM` | `GMT+05:30`, `GMT` |

### Literal escapes

| Sequence | Meaning |
|----------|---------|
| `[[` | Literal `[` |
| `]]` | Literal `]` |

---

## Extended picture modifiers (formatting only)

### Number formatting

| Modifier | Meaning |
|----------|---------|
| (empty) | Raw decimal, no padding |
| `01` / `001` | Zero-pad to 2 / 3 digits |
| `#1` | No leading zeros (explicit) |
| `9` | No minimum width |
| `9,999,*` | Thousands-separator grouping |
| `Y,2` | Last 2 digits (right-truncate to max width) |
| `Y,2-4` | min 2, max 4 digits |

### Name formatting

| Modifier | Meaning |
|----------|---------|
| `n` | Lowercase name |
| `N` | Uppercase name |
| `Nn` | Title-case name (first letter upper, rest lower) |
| `,3-3` | Abbreviate to exactly 3 characters |

---

## `$toMillis` preprocessing pipeline

When a picture string is given to `$toMillis`, the timestamp string is pre-processed before being fed to the `DateTimeFormatter`. `PictureParser.preprocess()` applies, in order:

1. **GMT timezone** — `GMT±HH:MM` / `GMT±H` → `±HH:MM`
2. **Bare offset** — ` ±HHMM` at end of string → ` ±HH:MM`
3. **Ordinal suffixes** — strip `1st`/`2nd`/`3rd`/`4th` when `[D…o]` is present
4. **Roman month** — lowercase Roman numerals (i–xii) → Arabic digit when `[Mi]`
5. **Month letters** — `JA`/`FE`/`MA`/… → `01`/`02`/`03`/… when `[MA]`
6. **Day words** — English ordinal words → digit when `[DW]`, `[DWwo]`, `[dwo]`, etc.
7. **Year words/Roman** — English number words or uppercase Roman numerals → digit when `[Yw]`, `[YI]`, `[Yi]`
8. **Single-letter day** — letter placeholder (a=1, b=2, …) → digit

The `DateTimeFormatter` is then configured so that `[Yw]`, `[YI]`, and `[Yi]` all use `appendValue(YEAR)` rather than `appendText`, because preprocessing always produces a digit string by step 7.

---

## Error codes

| Code | Meaning |
|------|---------|
| `D3110` | Invalid ISO 8601 timestamp or timezone string |
| `D3132` | Unknown picture-string component |
| `D3133` | Year name (`[YN]`) is not supported |
| `D3134` | Timezone picture string too long |
| `D3135` | Unclosed `[` in picture string |
| `D3136` | Date/time fields are underspecified (cannot reconstruct an instant) |

---

## Adding a new picture component

1. Add formatting logic to `PictureFormatter.formatComponent` (switch on the component letter).
2. Add parsing logic to `PictureParser.appendComponent` (switch on the component letter).
3. If the new component requires preprocessing (e.g., name → number), add a helper to `PictureParser.preprocess` and call it from there.
4. Add tests in `src/test/java/…/runtime/datetime/DateTimeFormattingTest.java`.

---

## Known limitations

- `[YN]` (year name) is not supported (throws `D3133`).
- `[x]` (lowercase, week-of-month context month) throws `D3136` in parsing.
- `[w]` (week of month) throws `D3136` in parsing.
- ISO week-year `[X]` parsing falls back to the calendar year.
- `WordNumbers.toCardinal` and `wordsToDigits` handle numbers up to 9,999. Years outside that range fall back to their decimal string.
- `RomanNumerals.toRoman` covers 1–3999 (standard notation); larger values return the decimal string.
