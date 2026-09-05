package org.json_kula.jsonata_jvm.runtime.string;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;
import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * String built-in functions for JSONata, delegated from {@link JsonataRuntime}.
 *
 * <p>Regex-heavy helpers live in {@link RegexOps}; URL codec helpers in {@link UrlCodec}.
 *
 * <p>Fixes vs the original {@code runtime.StringBuiltins}:
 * <ul>
 *   <li>{@code $uppercase}/{@code $lowercase} now use {@code Locale.ROOT} (was default locale).
 *   <li>{@code $trim} now throws {@code T0410} for non-string arguments (was silent MISSING).
 *   <li>{@code $trim} pre-compiles the {@code \s+} pattern (was compiled per-call).
 *   <li>{@code $split} now respects the {@code limit} argument when separator is {@code ""}.
 *   <li>{@code $join} now validates and joins in a single pass.
 *   <li>{@code $base64encode} now throws {@code T0410} for non-string arguments (was silent MISSING).
 *   <li>{@code $base64decode} now throws on invalid base64 input with a clear error message.
 * </ul>
 *
 * @see <a href="../../../../../../../docs/string.md">docs/string.md</a>
 */
public final class StringBuiltins {

    private StringBuiltins() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final ObjectWriter PRETTY_WRITER;
    static {
        com.fasterxml.jackson.core.util.DefaultIndenter unixIndenter =
                new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n");
        com.fasterxml.jackson.core.util.DefaultPrettyPrinter pp =
                new com.fasterxml.jackson.core.util.DefaultPrettyPrinter();
        pp.indentArraysWith(unixIndenter);
        pp.indentObjectsWith(unixIndenter);
        PRETTY_WRITER = new ObjectMapper().writer(pp);
    }

    // =========================================================================
    // $uppercase / $lowercase
    // =========================================================================

    public static JsonNode fn_uppercase(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$uppercase() function: argument 1 of $uppercase must be a string");
        return NF.textNode(arg.textValue().toUpperCase(Locale.ROOT));
    }

    public static JsonNode fn_lowercase(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$lowercase() function: argument 1 of $lowercase must be a string");
        return NF.textNode(arg.textValue().toLowerCase(Locale.ROOT));
    }

    // =========================================================================
    // $trim
    // =========================================================================

    public static JsonNode fn_trim(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$trim() function: argument 1 of $trim must be a string");
        return NF.textNode(WHITESPACE.matcher(arg.textValue()).replaceAll(" ").strip());
    }

    // =========================================================================
    // $length
    // =========================================================================

    public static JsonNode fn_length(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isTextual())
            throw new RuntimeEvaluationException("T0410", "$length: argument must be a string");
        String s = arg.textValue();
        return NF.numberNode(s.codePointCount(0, s.length()));
    }

    public static JsonNode fn_length_ctx(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isTextual())
            throw new RuntimeEvaluationException("T0411", "$length: context value must be a string");
        String s = arg.textValue();
        return NF.numberNode(s.codePointCount(0, s.length()));
    }

    // =========================================================================
    // $substring
    // =========================================================================

    public static JsonNode fn_substring(JsonNode str, JsonNode start) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        if (!str.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$substring() function: argument 1 of $substring must be a string");
        if (!start.isNumber()) throw new RuntimeEvaluationException(
                "T0410", "$substring() function: argument 2 of $substring must be a number");
        String s = str.textValue();
        int cpLen = s.codePointCount(0, s.length());
        int cpBegin = clampCpIndex((int) JsonataRuntime.toNumber(start), cpLen);
        return NF.textNode(s.substring(s.offsetByCodePoints(0, cpBegin)));
    }

    public static JsonNode fn_substring(JsonNode str, JsonNode start, JsonNode length)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        if (!str.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$substring() function: argument 1 of $substring must be a string");
        if (!start.isNumber()) throw new RuntimeEvaluationException(
                "T0410", "$substring() function: argument 2 of $substring must be a number");
        if (!length.isNumber()) throw new RuntimeEvaluationException(
                "T0410", "$substring() function: argument 3 of $substring must be a number");
        String s = str.textValue();
        int cpLen = s.codePointCount(0, s.length());
        int cpBegin = clampCpIndex((int) JsonataRuntime.toNumber(start), cpLen);
        int rawLen = (int) JsonataRuntime.toNumber(length);
        if (rawLen <= 0) return NF.textNode("");
        int cpEnd = Math.min(cpBegin + rawLen, cpLen);
        if (cpBegin >= cpEnd) return NF.textNode("");
        int charBegin = s.offsetByCodePoints(0, cpBegin);
        int charEnd   = s.offsetByCodePoints(0, cpEnd);
        return NF.textNode(s.substring(charBegin, charEnd));
    }

    private static int clampCpIndex(int i, int cpLen) {
        if (i < 0) i = Math.max(0, cpLen + i);
        return Math.min(i, cpLen);
    }

    // =========================================================================
    // $substringBefore / $substringAfter
    // =========================================================================

    public static JsonNode fn_substringBefore(JsonNode str, JsonNode chars)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(chars)) return JsonataRuntime.MISSING;
        if (!str.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$substringBefore() function: argument 1 of $substringBefore must be a string");
        if (!chars.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$substringBefore() function: argument 2 of $substringBefore must be a string");
        String s = str.textValue();
        String c = chars.textValue();
        int idx = s.indexOf(c);
        return NF.textNode(idx < 0 ? s : s.substring(0, idx));
    }

    public static JsonNode fn_substringBefore_ctx(JsonNode str, JsonNode chars)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(chars)) return JsonataRuntime.MISSING;
        if (!str.isTextual()) throw new RuntimeEvaluationException(
                "T0411", "$substringBefore() function: context value of $substringBefore must be a string");
        if (!chars.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$substringBefore() function: argument 2 of $substringBefore must be a string");
        String s = str.textValue();
        String c = chars.textValue();
        int idx = s.indexOf(c);
        return NF.textNode(idx < 0 ? s : s.substring(0, idx));
    }

    public static JsonNode fn_substringAfter(JsonNode str, JsonNode chars)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(chars)) return JsonataRuntime.MISSING;
        if (!str.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$substringAfter() function: argument 1 of $substringAfter must be a string");
        if (!chars.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$substringAfter() function: argument 2 of $substringAfter must be a string");
        String s = str.textValue();
        String c = chars.textValue();
        int idx = s.indexOf(c);
        return NF.textNode(idx < 0 ? s : s.substring(idx + c.length()));
    }

    public static JsonNode fn_substringAfter_ctx(JsonNode str, JsonNode chars)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(chars)) return JsonataRuntime.MISSING;
        if (!str.isTextual()) throw new RuntimeEvaluationException(
                "T0411", "$substringAfter() function: context value of $substringAfter must be a string");
        if (!chars.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$substringAfter() function: argument 2 of $substringAfter must be a string");
        String s = str.textValue();
        String c = chars.textValue();
        int idx = s.indexOf(c);
        return NF.textNode(idx < 0 ? s : s.substring(idx + c.length()));
    }

    // =========================================================================
    // $contains
    // =========================================================================

    public static JsonNode fn_contains(JsonNode str, JsonNode search) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(search)) return JsonataRuntime.MISSING;
        if (!str.isTextual())
            throw new RuntimeEvaluationException("T0410", "Argument 1 of $contains must be a string");
        if (JsonataRuntime.isRegexToken(search)) {
            byte[] bytes = str.textValue().getBytes(StandardCharsets.UTF_8);
            return JsonataRuntime.bool(JsonataRuntime.lookupRegex(search).matcher(bytes)
                    .search(0, bytes.length, org.joni.Option.NONE) >= 0);
        }
        if (!search.isTextual())
            throw new RuntimeEvaluationException("T0410",
                    "Argument 2 of $contains must be a string or regex");
        return JsonataRuntime.bool(str.textValue().contains(search.textValue()));
    }

    // =========================================================================
    // $split
    // =========================================================================

    /**
     * Validates the optional numeric limit that {@code $split}, {@code $replace} and {@code
     * $match} each declare as {@code n?}.
     *
     * <p>It runs <em>before</em> the undefined-argument short-circuit, because the reference
     * validates a call against its signature before the function body sees anything:
     * {@code $split(nope, /a/, "1")} is T0410 there, not undefined. Checking it inside the body
     * — after the early return — makes a bad argument invisible whenever an earlier one happens
     * to be absent.
     */
    private static void requireOptionalLimit(JsonNode limit, String fnName, int position)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(limit)) return;
        if (!limit.isNumber())
            throw new RuntimeEvaluationException("T0410",
                    fnName + ": argument " + position + " must be a number");
    }

    public static JsonNode fn_split(JsonNode str, JsonNode separator) throws RuntimeEvaluationException {
        return fn_split(str, separator, JsonataRuntime.MISSING);
    }

    public static JsonNode fn_split(JsonNode str, JsonNode separator, JsonNode limit)
            throws RuntimeEvaluationException {
        requireOptionalLimit(limit, "$split", 3);
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        if (JsonataRuntime.missing(separator)) separator = NF.textNode("");
        if (!str.isTextual())
            throw new RuntimeEvaluationException("T0410", "$split: argument 1 must be a string");
        if (JsonataRuntime.isLambdaToken(separator))
            throw new RuntimeEvaluationException("T1010",
                    "The separator argument of $split must be a string or regular expression");
        if (!JsonataRuntime.isRegexToken(separator) && !separator.isTextual())
            throw new RuntimeEvaluationException("T0410",
                    "$split: argument 2 must be a string or regex");
        if (!JsonataRuntime.missing(limit) && limit.doubleValue() < 0)
            throw new RuntimeEvaluationException("D3020", "$split: limit must be non-negative");
        String s = str.textValue();
        // Compared as a number rather than truncated to an int: the reference tests
        // `count < limit`, so a limit of 1.5 admits two pieces, not one.
        double lim = JsonataRuntime.missing(limit) ? -1 : limit.doubleValue();
        ArrayNode result = NF.arrayNode();

        if (JsonataRuntime.isRegexToken(separator)) {
            org.joni.Regex rx = JsonataRuntime.lookupRegex(separator);
            RegexOps.MatchCursor cursor = new RegexOps.MatchCursor(s, rx);
            RegexOps.Match found = lim == 0 ? null : cursor.next();
            if (found == null) {
                // No match at all: the whole subject is the single piece. A zero limit
                // yields no pieces at all, which is why it short-circuits above.
                if (lim != 0) result.add(s);
            } else {
                int start = 0, count = 0;
                while (found != null && (lim < 0 || count < lim)) {
                    result.add(s.substring(start, found.start()));
                    start = found.end();
                    count++;
                    found = cursor.next();
                }
                if (lim < 0 || count < lim) result.add(s.substring(start));
            }
        } else {
            // A string separator splits the whole subject and then truncates, because the
            // reference does `str.split(sep).slice(0, limit)` — and Array#slice truncates
            // its argument toward zero. The regex branch above instead compares
            // `count < limit` as a number, so 2.5 means two pieces here but admits a
            // third there. The two really do differ; they are not one rule stated twice.
            String sep = JsonataRuntime.toText(separator);
            if (sep.isEmpty()) {
                for (int cp : s.codePoints().toArray()) {
                    result.add(new String(Character.toChars(cp)));
                }
            } else {
                int start = 0, idx;
                while ((idx = s.indexOf(sep, start)) >= 0) {
                    result.add(s.substring(start, idx));
                    start = idx + sep.length();
                }
                result.add(s.substring(start));
            }
            if (lim >= 0 && result.size() > (int) lim) {
                ArrayNode truncated = NF.arrayNode();
                for (int k = 0; k < (int) lim; k++) truncated.add(result.get(k));
                result = truncated;
            }
        }
        return result;
    }

    // =========================================================================
    // $join
    // =========================================================================

    public static JsonNode fn_join(JsonNode arr, JsonNode separator) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        if (!JsonataRuntime.missing(separator) && !separator.isTextual())
            throw new RuntimeEvaluationException("T0410",
                    "$join: separator argument must be a string");
        if (!arr.isArray()) {
            if (!arr.isTextual())
                throw new RuntimeEvaluationException("T0412",
                        "$join: function argument must be an array of strings");
            return arr;
        }
        String sep = JsonataRuntime.missing(separator) ? "" : separator.textValue();
        StringJoiner sj = new StringJoiner(sep);
        for (JsonNode elem : arr) {
            if (!elem.isTextual())
                throw new RuntimeEvaluationException("T0412",
                        "$join: function argument must be an array of strings");
            sj.add(elem.textValue());
        }
        return NF.textNode(sj.toString());
    }

    // =========================================================================
    // $match
    // =========================================================================

    public static JsonNode fn_match(JsonNode str, JsonNode pattern) throws RuntimeEvaluationException {
        return fn_match(str, pattern, JsonataRuntime.MISSING);
    }

    public static JsonNode fn_match(JsonNode str, JsonNode pattern, JsonNode limit)
            throws RuntimeEvaluationException {
        requireOptionalLimit(limit, "$match", 3);
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(pattern))
            return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);

        if (JsonataRuntime.isLambdaToken(pattern)) {
            return RegexOps.matchWithLambda(s, pattern,
                    JsonataRuntime.missing(limit) ? Integer.MAX_VALUE
                            : (int) JsonataRuntime.toNumber(limit));
        }

        if (!JsonataRuntime.missing(limit) && JsonataRuntime.toNumber(limit) < 0)
            throw new RuntimeEvaluationException("D3040", "$match: limit must be non-negative");

        org.joni.Regex rx = JsonataRuntime.isRegexToken(pattern)
                ? JsonataRuntime.lookupRegex(pattern)
                : JsonataRuntime.buildLiteralRegex(JsonataRuntime.toText(pattern));
        double lim = JsonataRuntime.missing(limit) ? Double.MAX_VALUE
                : JsonataRuntime.toNumber(limit);

        ArrayNode results = NF.arrayNode();
        RegexOps.MatchCursor cursor = new RegexOps.MatchCursor(s, rx);
        int count = 0;
        for (RegexOps.Match found = cursor.next();
             found != null && count < lim;
             found = cursor.next()) {
            ObjectNode obj = NF.objectNode();
            obj.put("match", found.match());
            obj.put("index", found.start());
            obj.set("groups", found.groups());
            results.add(obj);
            count++;
        }
        // A JSONata sequence collapses: no matches is absent, exactly one match is the
        // bare object rather than a one-element array.
        if (results.isEmpty()) return JsonataRuntime.MISSING;
        return results.size() == 1 ? results.get(0) : results;
    }

    // =========================================================================
    // $replace
    // =========================================================================

    public static JsonNode fn_replace(JsonNode str, JsonNode pattern, JsonNode replacement)
            throws RuntimeEvaluationException {
        return fn_replace(str, pattern, replacement, JsonataRuntime.MISSING);
    }

    public static JsonNode fn_replace(JsonNode str, JsonNode pattern,
                                      JsonNode replacement, JsonNode limit)
            throws RuntimeEvaluationException {
        requireOptionalLimit(limit, "$replace", 4);
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(pattern)
                || JsonataRuntime.missing(replacement))
            return JsonataRuntime.MISSING;
        if (!str.isTextual())
            throw new RuntimeEvaluationException("T0410", "$replace: argument 1 must be a string");
        if (!JsonataRuntime.isRegexToken(pattern) && !pattern.isTextual())
            throw new RuntimeEvaluationException("T0410",
                    "$replace: argument 2 must be a string or regex");
        if (!JsonataRuntime.isLambdaToken(replacement) && !replacement.isTextual())
            throw new RuntimeEvaluationException("T0410",
                    "$replace: argument 3 must be a string or function");
        if (!JsonataRuntime.missing(limit) && limit.doubleValue() < 0)
            throw new RuntimeEvaluationException("D3011", "$replace: limit must be non-negative");
        String s = str.textValue();
        if (!JsonataRuntime.isRegexToken(pattern) && pattern.textValue().isEmpty())
            throw new RuntimeEvaluationException("D3010",
                    "$replace: second argument cannot be an empty string");
        org.joni.Regex rx = JsonataRuntime.isRegexToken(pattern)
                ? JsonataRuntime.lookupRegex(pattern)
                : JsonataRuntime.buildLiteralRegex(pattern.textValue());
        double lim = JsonataRuntime.missing(limit) ? Double.MAX_VALUE : limit.doubleValue();
        StringBuilder sb = new StringBuilder();
        RegexOps.MatchCursor cursor = new RegexOps.MatchCursor(s, rx);
        int position = 0, count = 0;
        for (RegexOps.Match found = lim == 0 ? null : cursor.next();
             found != null && count < lim;
             found = cursor.next()) {
            sb.append(s, position, found.start());
            if (JsonataRuntime.isLambdaToken(replacement)) {
                // The replacer receives the raw matcher-closure object — match, start,
                // end and groups — not the remapped {match, index, groups} shape $match
                // publishes. That is the reference's calling convention.
                ObjectNode matchObj = NF.objectNode();
                matchObj.put("match", found.match());
                matchObj.put("start", found.start());
                matchObj.put("end", found.end());
                matchObj.set("groups", found.groups());
                // The closure also carries `next`, which continues the same scan. It is
                // observable — $keys of the argument lists it — and it advances the one
                // shared cursor, exactly as the reference's does.
                matchObj.set("next", JsonataRuntime.lambdaNode(
                        ignored -> RegexOps.toMatchObject(cursor.next()), 0));
                JsonNode repResult = JsonataRuntime.fn_apply(replacement, matchObj);
                // Undefined is not a string: the reference tests `typeof x === "string"` and
                // raises D3012 for everything else, so a replacer that navigates to a field the
                // match object does not have ($m.index — it is `start`/`end` here and there)
                // fails rather than silently substituting nothing.
                if (!repResult.isTextual())
                    throw new RuntimeEvaluationException("D3012",
                            "$replace: replacement function must return a string");
                sb.append(repResult.textValue());
            } else {
                sb.append(RegexOps.expandReplacement(
                        JsonataRuntime.toText(replacement), found.match(), found.groups()));
            }
            position = found.start() + found.match().length();
            count++;
        }
        sb.append(s, position, s.length());
        return NF.textNode(sb.toString());
    }

    // =========================================================================
    // $pad
    // =========================================================================

    /**
     * The longest string the reference host will build ({@code 2^29 - 24}). Past this V8 raises
     * {@code RangeError: Invalid string length}, so it is the natural place for {@code $pad} to
     * stop trying too.
     */
    private static final double MAX_PAD_WIDTH = 536_870_888d;

    public static JsonNode fn_pad(JsonNode str, JsonNode width, JsonNode padChar)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(width))
            return JsonataRuntime.MISSING;
        if (!str.isTextual())
            throw new RuntimeEvaluationException("T0410", "$pad: argument 1 must be a string");
        String s = str.textValue();
        double requested = JsonataRuntime.toNumber(width);
        // Bounded before anything is allocated. `(int)` saturates 1e15 to Integer.MAX_VALUE and
        // the builder below then tried for a two-billion-character string: an OutOfMemoryError,
        // which is not an error the caller can catch and act on -- it can take unrelated work in
        // the same JVM down with it. The bound is the reference host's own maximum string
        // length, so every pad it can produce is still produced here, and the width that makes
        // it throw RangeError is the width that raises here.
        if (Math.abs(requested) > MAX_PAD_WIDTH)
            throw new RuntimeEvaluationException("D1001",
                    "$pad: width out of range: " + JsonataRuntime.renderNumberRaw(requested));
        int w  = (int) requested;
        String pc = JsonataRuntime.missing(padChar) ? " " : JsonataRuntime.toText(padChar);
        if (pc.isEmpty()) pc = " ";
        int cpLen   = s.codePointCount(0, s.length());
        int pcCpLen = pc.codePointCount(0, pc.length());
        int absW = Math.abs(w);
        if (cpLen >= absW) return NF.textNode(s);
        int need = absW - cpLen;
        StringBuilder padding = new StringBuilder();
        int addedCp = 0;
        while (addedCp < need) {
            int take = Math.min(pcCpLen, need - addedCp);
            padding.append(pc, 0, pc.offsetByCodePoints(0, take));
            addedCp += take;
        }
        String pad = padding.toString();
        return NF.textNode(w >= 0 ? s + pad : pad + s);
    }

    // =========================================================================
    // $eval
    // =========================================================================

    public static JsonNode fn_eval(JsonNode expr, JsonNode context) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(expr)) return JsonataRuntime.MISSING;
        JsonataRuntime.EvalDelegate delegate = JsonataRuntime.getEvalDelegate();
        if (delegate == null)
            throw new RuntimeEvaluationException(null,
                    "$eval: no eval delegate registered (create a JsonataExpressionFactory first)");
        JsonNode ctx = JsonataRuntime.missing(context) ? JsonataRuntime.MISSING : context;
        return delegate.eval(JsonataRuntime.toText(expr), ctx);
    }

    // =========================================================================
    // $string (prettify variant)
    // =========================================================================

    public static JsonNode fn_string_prettify(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (arg.isTextual()) return arg;
        try {
            JsonNode sanitized = JsonataRuntime.sanitizeForString(arg);
            String raw = PRETTY_WRITER.writeValueAsString(sanitized);
            raw = raw.replace(" : ", ": ").replace("[ ]", "[]");
            return NF.textNode(raw);
        } catch (RuntimeEvaluationException e) {
            throw new RuntimeEvaluationException(e.getErrorCode(), "$string: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeEvaluationException(null, "$string: " + e.getMessage());
        }
    }

    // =========================================================================
    // $base64encode / $base64decode
    // =========================================================================

    public static JsonNode fn_base64encode(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        if (!str.isTextual())
            throw new RuntimeEvaluationException("T0410",
                    "$base64encode: argument must be a string");
        // btoa semantics: each UTF-16 code unit contributes its low byte. Latin-1 is
        // exactly that mapping. Decoding reverses it with the SAME charset — decoding as
        // UTF-8 made the pair non-invertible, so $base64decode($base64encode("e-acute"))
        // came back as U+FFFD.
        byte[] bytes = str.textValue().getBytes(StandardCharsets.ISO_8859_1);
        return NF.textNode(java.util.Base64.getEncoder().encodeToString(bytes));
    }

    public static JsonNode fn_base64decode(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        if (!str.isTextual()) return JsonataRuntime.MISSING;
        // Lenient, like Node's Buffer.from(s, "base64"): characters outside the alphabet
        // are skipped and surplus padding ignored, rather than rejected. The reference
        // decodes "!!!!" to "" and "YQ===" to "a"; a strict decoder threw on both.
        //
        // The pad character is not skipped, though — it *ends* the stream, wherever it appears.
        // Skipping it agrees with Node on every well-formed input, which is why it survived: it
        // only shows up when the padding is in the middle, as in "YW=J" (decoding to "a"
        // there, and to "ab" if you read past it).
        String encoded = str.textValue();
        StringBuilder cleaned = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '=') break;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '-' || c == '_') {
                cleaned.append(c == '-' ? '+' : c == '_' ? '/' : c);
            }
        }
        // A trailing group of one character carries no whole byte and is discarded.
        int usable = cleaned.length() - (cleaned.length() % 4 == 1 ? 1 : 0);
        byte[] decoded = java.util.Base64.getMimeDecoder()
                .decode(cleaned.substring(0, usable));
        return NF.textNode(new String(decoded, StandardCharsets.ISO_8859_1));
    }

    // =========================================================================
    // $encodeUrlComponent / $decodeUrlComponent / $encodeUrl / $decodeUrl
    // =========================================================================

    public static JsonNode fn_encodeUrlComponent(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        return NF.textNode(UrlCodec.encode(JsonataRuntime.toText(str), false));
    }

    public static JsonNode fn_decodeUrlComponent(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        try {
            return NF.textNode(UrlCodec.decode(JsonataRuntime.toText(str)));
        } catch (RuntimeEvaluationException e) {
            throw new RuntimeEvaluationException(e.getErrorCode(),
                    "$decodeUrlComponent: " + e.getMessage());
        }
    }

    public static JsonNode fn_encodeUrl(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        return NF.textNode(UrlCodec.encode(JsonataRuntime.toText(str), true));
    }

    public static JsonNode fn_decodeUrl(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        try {
            return NF.textNode(UrlCodec.decode(JsonataRuntime.toText(str)));
        } catch (RuntimeEvaluationException e) {
            throw new RuntimeEvaluationException(e.getErrorCode(),
                    "$decodeUrl: " + e.getMessage());
        }
    }
}
