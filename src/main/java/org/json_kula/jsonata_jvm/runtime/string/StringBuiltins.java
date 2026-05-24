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

    public static JsonNode fn_split(JsonNode str, JsonNode separator) throws RuntimeEvaluationException {
        return fn_split(str, separator, JsonataRuntime.MISSING);
    }

    public static JsonNode fn_split(JsonNode str, JsonNode separator, JsonNode limit)
            throws RuntimeEvaluationException {
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
        if (!JsonataRuntime.missing(limit)) {
            if (!limit.isNumber())
                throw new RuntimeEvaluationException("T0410", "$split: argument 3 must be a number");
            if (limit.doubleValue() < 0)
                throw new RuntimeEvaluationException("D3020", "$split: limit must be non-negative");
        }
        String s = str.textValue();
        int lim = JsonataRuntime.missing(limit) ? -1 : (int) limit.doubleValue();
        ArrayNode result = NF.arrayNode();

        if (JsonataRuntime.isRegexToken(separator)) {
            org.joni.Regex rx = JsonataRuntime.lookupRegex(separator);
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            int start = 0, count = 0;
            org.joni.Matcher m = rx.matcher(bytes);
            while (start <= bytes.length) {
                if (lim >= 0 && count >= lim) break;
                int found = m.search(start, bytes.length, org.joni.Option.NONE);
                if (found < 0) {
                    result.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
                    break;
                }
                result.add(new String(bytes, start, found - start, StandardCharsets.UTF_8));
                count++;
                int end = m.getEnd();
                start = (end > found) ? end : end + 1;
            }
        } else {
            String sep = JsonataRuntime.toText(separator);
            if (sep.isEmpty()) {
                // Split into codepoints, honouring limit
                int[] cps = s.codePoints().toArray();
                int n = (lim >= 0) ? Math.min(lim, cps.length) : cps.length;
                for (int k = 0; k < n; k++)
                    result.add(new String(Character.toChars(cps[k])));
            } else {
                int start = 0, count = 0, idx;
                while ((idx = s.indexOf(sep, start)) >= 0) {
                    if (lim >= 0 && count >= lim) break;
                    result.add(s.substring(start, idx));
                    count++;
                    start = idx + sep.length();
                }
                if (lim < 0 || count < lim) result.add(s.substring(start));
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
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(pattern))
            return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);

        if (JsonataRuntime.isLambdaToken(pattern)) {
            return RegexOps.matchWithLambda(s, pattern,
                    JsonataRuntime.missing(limit) ? Integer.MAX_VALUE
                            : (int) JsonataRuntime.toNumber(limit));
        }

        org.joni.Regex rx = JsonataRuntime.isRegexToken(pattern)
                ? JsonataRuntime.lookupRegex(pattern)
                : JsonataRuntime.buildLiteralRegex(JsonataRuntime.toText(pattern));
        int lim = JsonataRuntime.missing(limit) ? Integer.MAX_VALUE
                : (int) JsonataRuntime.toNumber(limit);
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        org.joni.Matcher m = rx.matcher(bytes);
        ArrayNode results = NF.arrayNode();
        int pos = 0, count = 0;
        while (pos <= bytes.length && count < lim) {
            int found = m.search(pos, bytes.length, org.joni.Option.NONE);
            if (found < 0) break;
            int end = m.getEnd();
            org.joni.Region region = m.getRegion();
            ObjectNode obj = NF.objectNode();
            obj.put("match", new String(bytes, found, end - found, StandardCharsets.UTF_8));
            obj.put("index", RegexOps.bytePosToCharPos(s, found));
            ArrayNode groups = NF.arrayNode();
            if (region != null) {
                for (int gi = 1; gi < region.getNumRegs(); gi++) {
                    int gb = region.getBeg(gi);
                    int ge = region.getEnd(gi);
                    groups.add(gb >= 0
                            ? new String(bytes, gb, ge - gb, StandardCharsets.UTF_8)
                            : "");
                }
            }
            obj.set("groups", groups);
            results.add(obj);
            count++;
            pos = (end > found) ? end : end + 1;
        }
        return results.isEmpty() ? JsonataRuntime.MISSING : results;
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
        if (!JsonataRuntime.missing(limit)) {
            if (!limit.isNumber())
                throw new RuntimeEvaluationException("T0410",
                        "$replace: argument 4 must be a number");
            if (limit.doubleValue() < 0)
                throw new RuntimeEvaluationException("D3011", "$replace: limit must be non-negative");
        }
        String s = str.textValue();
        if (!JsonataRuntime.isRegexToken(pattern) && pattern.textValue().isEmpty())
            throw new RuntimeEvaluationException("D3010",
                    "$replace: second argument cannot be an empty string");
        org.joni.Regex rx = JsonataRuntime.isRegexToken(pattern)
                ? JsonataRuntime.lookupRegex(pattern)
                : JsonataRuntime.buildLiteralRegex(pattern.textValue());
        int lim = JsonataRuntime.missing(limit) ? Integer.MAX_VALUE : (int) limit.doubleValue();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        org.joni.Matcher m = rx.matcher(bytes);
        StringBuilder sb = new StringBuilder();
        int pos = 0, count = 0;
        while (pos <= bytes.length && count < lim) {
            int found = m.search(pos, bytes.length, org.joni.Option.NONE);
            if (found < 0) break;
            int end = m.getEnd();
            if (end == found)
                throw new RuntimeEvaluationException("D1004",
                        "Regular expression matches zero length string");
            sb.append(new String(bytes, pos, found - pos, StandardCharsets.UTF_8));
            String matchStr = new String(bytes, found, end - found, StandardCharsets.UTF_8);
            org.joni.Region region = m.getRegion();
            if (JsonataRuntime.isLambdaToken(replacement)) {
                ObjectNode matchObj = NF.objectNode();
                matchObj.put("match", matchStr);
                matchObj.put("index", RegexOps.bytePosToCharPos(s, found));
                ArrayNode groups = NF.arrayNode();
                if (region != null) {
                    for (int gi = 1; gi < region.getNumRegs(); gi++) {
                        int gb = region.getBeg(gi);
                        int ge = region.getEnd(gi);
                        groups.add(gb >= 0
                                ? new String(bytes, gb, ge - gb, StandardCharsets.UTF_8)
                                : "");
                    }
                }
                matchObj.set("groups", groups);
                JsonNode repResult = JsonataRuntime.fn_apply(replacement, matchObj);
                if (!JsonataRuntime.missing(repResult) && !repResult.isTextual())
                    throw new RuntimeEvaluationException("D3012",
                            "$replace: replacement function must return a string");
                sb.append(JsonataRuntime.missing(repResult) ? "" : repResult.textValue());
            } else {
                sb.append(RegexOps.expandReplacement(
                        JsonataRuntime.toText(replacement), matchStr, bytes, region));
            }
            count++;
            pos = end;
        }
        if (pos <= bytes.length)
            sb.append(new String(bytes, pos, bytes.length - pos, StandardCharsets.UTF_8));
        return NF.textNode(sb.toString());
    }

    // =========================================================================
    // $pad
    // =========================================================================

    public static JsonNode fn_pad(JsonNode str, JsonNode width, JsonNode padChar)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(width))
            return JsonataRuntime.MISSING;
        if (!str.isTextual())
            throw new RuntimeEvaluationException("T0410", "$pad: argument 1 must be a string");
        String s = str.textValue();
        int w  = (int) JsonataRuntime.toNumber(width);
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
        byte[] bytes = str.textValue().getBytes(StandardCharsets.ISO_8859_1);
        return NF.textNode(java.util.Base64.getEncoder().encodeToString(bytes));
    }

    public static JsonNode fn_base64decode(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        if (!str.isTextual()) return JsonataRuntime.MISSING;
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(str.textValue());
            return NF.textNode(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new RuntimeEvaluationException(null,
                    "$base64decode: invalid base64 input: " + e.getMessage());
        }
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
