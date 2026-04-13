package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.util.StringJoiner;

/**
 * String built-in functions for JSONata.
 *
 * <p>All methods are package-private static helpers delegated from
 * {@link JsonataRuntime}.
 */
final class StringBuiltins {

    private StringBuiltins() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;
    private static final ObjectWriter PRETTY_WRITER;
    static {
        // Use Unix line endings (\n) and 2-space indent — matching JSONata reference output
        com.fasterxml.jackson.core.util.DefaultIndenter unixIndenter =
                new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n");
        com.fasterxml.jackson.core.util.DefaultPrettyPrinter pp =
                new com.fasterxml.jackson.core.util.DefaultPrettyPrinter();
        pp.indentArraysWith(unixIndenter);
        pp.indentObjectsWith(unixIndenter);
        PRETTY_WRITER = new ObjectMapper().writer(pp);
    }

    static JsonNode fn_uppercase(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$uppercase() function: argument 1 of $uppercase must be a string");
        return NF.textNode(arg.textValue().toUpperCase());
    }

    static JsonNode fn_lowercase(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$lowercase() function: argument 1 of $lowercase must be a string");
        return NF.textNode(arg.textValue().toLowerCase());
    }

    static JsonNode fn_trim(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isTextual()) return JsonataRuntime.MISSING;
        // JSONata $trim normalises whitespace: collapse all internal runs of
        // whitespace (tabs, newlines, multiple spaces) to a single space and
        // strip leading/trailing whitespace.
        return NF.textNode(arg.textValue().replaceAll("\\s+", " ").strip());
    }

    static JsonNode fn_length(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isTextual())
            throw new RuntimeEvaluationException("T0410", "$length: argument must be a string");
        String s = arg.textValue();
        return NF.numberNode(s.codePointCount(0, s.length()));
    }

    /** Context-bound variant — throws T0411 when context value is not a string. */
    static JsonNode fn_length_ctx(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (!arg.isTextual())
            throw new RuntimeEvaluationException("T0411", "$length: context value must be a string");
        String s = arg.textValue();
        return NF.numberNode(s.codePointCount(0, s.length()));
    }

    static JsonNode fn_substring(JsonNode str, JsonNode start) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        if (!str.isTextual()) throw new RuntimeEvaluationException(
                "T0410", "$substring() function: argument 1 of $substring must be a string");
        if (!start.isNumber()) throw new RuntimeEvaluationException(
                "T0410", "$substring() function: argument 2 of $substring must be a number");
        String s = str.textValue();
        int cpLen = s.codePointCount(0, s.length());
        int cpBegin = clampCpIndex((int) JsonataRuntime.toNumber(start), cpLen);
        int charBegin = s.offsetByCodePoints(0, cpBegin);
        return NF.textNode(s.substring(charBegin));
    }

    static JsonNode fn_substring(JsonNode str, JsonNode start, JsonNode length)
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
        int cpEnd = Math.min(cpBegin + (int) JsonataRuntime.toNumber(length), cpLen);
        if (cpBegin >= cpEnd) return NF.textNode("");
        int charBegin = s.offsetByCodePoints(0, cpBegin);
        int charEnd = s.offsetByCodePoints(0, cpEnd);
        return NF.textNode(s.substring(charBegin, charEnd));
    }

    /** Clamps a codepoint index (possibly negative = from-end) to [0, cpLen]. */
    private static int clampCpIndex(int i, int cpLen) {
        if (i < 0) i = Math.max(0, cpLen + i);
        return Math.min(i, cpLen);
    }

    static JsonNode fn_substringBefore(JsonNode str, JsonNode chars)
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

    /** Context-bound variant — throws T0411 when context value is not a string. */
    static JsonNode fn_substringBefore_ctx(JsonNode str, JsonNode chars)
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

    static JsonNode fn_substringAfter(JsonNode str, JsonNode chars)
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

    /** Context-bound variant — throws T0411 when context value is not a string. */
    static JsonNode fn_substringAfter_ctx(JsonNode str, JsonNode chars)
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

    static JsonNode fn_contains(JsonNode str, JsonNode search) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(search)) return JsonataRuntime.MISSING;
        if (!str.isTextual())
            throw new RuntimeEvaluationException("T0410", "Argument 1 of $contains must be a string");
        if (RegexRegistry.isRegexToken(search)) {
            byte[] bytes = str.textValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return JsonataRuntime.bool(RegexRegistry.lookupRegex(search).matcher(bytes)
                    .search(0, bytes.length, org.joni.Option.NONE) >= 0);
        }
        if (!search.isTextual())
            throw new RuntimeEvaluationException("T0410", "Argument 2 of $contains must be a string or regex");
        return JsonataRuntime.bool(str.textValue().contains(search.textValue()));
    }

    static JsonNode fn_split(JsonNode str, JsonNode separator) throws RuntimeEvaluationException {
        return fn_split(str, separator, JsonataRuntime.MISSING);
    }

    static JsonNode fn_split(JsonNode str, JsonNode separator, JsonNode limit)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        // Missing separator defaults to empty (split each character)
        if (JsonataRuntime.missing(separator)) separator = NF.textNode("");
        if (!str.isTextual())
            throw new RuntimeEvaluationException("T0410", "$split: argument 1 must be a string");
        if (org.json_kula.jsonata_jvm.runtime.LambdaRegistry.isLambdaToken(separator))
            throw new RuntimeEvaluationException("T1010", "The separator argument of $split must be a string or regular expression");
        if (!RegexRegistry.isRegexToken(separator) && !separator.isTextual())
            throw new RuntimeEvaluationException("T0410", "$split: argument 2 must be a string or regex");
        if (!JsonataRuntime.missing(limit)) {
            if (!limit.isNumber())
                throw new RuntimeEvaluationException("T0410", "$split: argument 3 must be a number");
            double limD = limit.doubleValue();
            if (limD < 0)
                throw new RuntimeEvaluationException("D3020", "$split: limit must be non-negative");
        }
        String s = str.textValue();
        int lim = JsonataRuntime.missing(limit) ? -1 : (int) limit.doubleValue();
        ArrayNode result = NF.arrayNode();
        if (RegexRegistry.isRegexToken(separator)) {
            org.joni.Regex rx = RegexRegistry.lookupRegex(separator);
            byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int start = 0;
            int count = 0;
            org.joni.Matcher m = rx.matcher(bytes);
            while (start <= bytes.length) {
                if (lim >= 0 && count >= lim) break;
                int found = m.search(start, bytes.length, org.joni.Option.NONE);
                if (found < 0) {
                    result.add(new String(bytes, start, bytes.length - start,
                            java.nio.charset.StandardCharsets.UTF_8));
                    break;
                }
                result.add(new String(bytes, start, found - start,
                        java.nio.charset.StandardCharsets.UTF_8));
                count++;
                int end = m.getEnd();
                start = (end > found) ? end : end + 1;
            }
        } else {
            String sep = JsonataRuntime.toText(separator);
            if (sep.isEmpty()) {
                s.codePoints().forEach(cp -> result.add(new String(Character.toChars(cp))));
            } else {
                int start = 0;
                int count = 0;
                int idx;
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

    static JsonNode fn_match(JsonNode str, JsonNode pattern) throws RuntimeEvaluationException {
        return fn_match(str, pattern, JsonataRuntime.MISSING);
    }

    static JsonNode fn_match(JsonNode str, JsonNode pattern, JsonNode limit)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(pattern)) return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);

        if (LambdaRegistry.isLambdaToken(pattern)) {
            return fn_matchWithLambda(s, pattern,
                JsonataRuntime.missing(limit) ? Integer.MAX_VALUE : (int) JsonataRuntime.toNumber(limit));
        }

        org.joni.Regex rx = RegexRegistry.isRegexToken(pattern)
                ? RegexRegistry.lookupRegex(pattern)
                : RegexRegistry.buildLiteralRegex(JsonataRuntime.toText(pattern));
        int lim = JsonataRuntime.missing(limit) ? Integer.MAX_VALUE : (int) JsonataRuntime.toNumber(limit);
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.joni.Matcher m = rx.matcher(bytes);
        ArrayNode results = NF.arrayNode();
        int pos = 0;
        int count = 0;
        while (pos <= bytes.length && count < lim) {
            int found = m.search(pos, bytes.length, org.joni.Option.NONE);
            if (found < 0) break;
            int end = m.getEnd();
            org.joni.Region region = m.getRegion();
            ObjectNode obj = NF.objectNode();
            obj.put("match", new String(bytes, found, end - found,
                    java.nio.charset.StandardCharsets.UTF_8));
            obj.put("index", bytePosToCharPos(s, found));
            ArrayNode groups = NF.arrayNode();
            if (region != null) {
                for (int i = 1; i < region.getNumRegs(); i++) {
                    int gb = region.getBeg(i);
                    int ge = region.getEnd(i);
                    groups.add(gb >= 0
                            ? new String(bytes, gb, ge - gb, java.nio.charset.StandardCharsets.UTF_8)
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

    private static JsonNode fn_matchWithLambda(String s, JsonNode pattern, int limit) {
        // Custom matcher protocol: the pattern is function($str, $offset).
        // Initial call: pass the full string as a TextNode (single arg, so $offset = MISSING
        // inside the lambda, which uses $exists($offset) ? $offset : 0).
        // Subsequent calls: use the "next" zero-arg function returned in the previous result.
        ArrayNode results = NF.arrayNode();
        int count = 0;
        JsonNode currentPattern = pattern;
        boolean firstCall = true;

        while (count < limit) {
            JsonNode result;
            if (firstCall) {
                result = LambdaRegistry.fn_apply(currentPattern, NF.textNode(s));
                firstCall = false;
            } else {
                // "next" is a zero-arg closure: the single arg is ignored by the lambda body.
                result = LambdaRegistry.fn_apply(currentPattern, JsonataRuntime.NULL);
            }

            if (JsonataRuntime.missing(result)) break;
            if (!result.isObject()) break;

            JsonNode match = result.get("match");
            JsonNode start = result.get("start");
            JsonNode end   = result.get("end");
            JsonNode groups = result.get("groups");
            JsonNode next  = result.get("next");

            if (JsonataRuntime.missing(match) || JsonataRuntime.missing(start) || JsonataRuntime.missing(end)) break;

            // start/end returned by the custom matcher are already absolute positions.
            ObjectNode out = NF.objectNode();
            out.put("match", match.asText());
            out.put("index", start.asInt());
            out.set("groups", JsonataRuntime.missing(groups) ? NF.arrayNode() : groups);
            results.add(out);
            count++;

            if (!JsonataRuntime.missing(next) && LambdaRegistry.isLambdaToken(next)) {
                currentPattern = next;
            } else {
                break;
            }
        }

        return results.isEmpty() ? JsonataRuntime.MISSING : results;
    }

    static JsonNode fn_replace(JsonNode str, JsonNode pattern, JsonNode replacement)
            throws RuntimeEvaluationException {
        return fn_replace(str, pattern, replacement, JsonataRuntime.MISSING);
    }

    static JsonNode fn_replace(JsonNode str, JsonNode pattern,
                                JsonNode replacement, JsonNode limit)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(pattern) || JsonataRuntime.missing(replacement))
            return JsonataRuntime.MISSING;
        if (!str.isTextual())
            throw new RuntimeEvaluationException("T0410", "$replace: argument 1 must be a string");
        if (!RegexRegistry.isRegexToken(pattern) && !pattern.isTextual())
            throw new RuntimeEvaluationException("T0410", "$replace: argument 2 must be a string or regex");
        if (!LambdaRegistry.isLambdaToken(replacement) && !replacement.isTextual())
            throw new RuntimeEvaluationException("T0410", "$replace: argument 3 must be a string or function");
        if (!JsonataRuntime.missing(limit)) {
            if (!limit.isNumber())
                throw new RuntimeEvaluationException("T0410", "$replace: argument 4 must be a number");
            if (limit.doubleValue() < 0)
                throw new RuntimeEvaluationException("D3011", "$replace: limit must be non-negative");
        }
        String s = str.textValue();
        // Empty string pattern is invalid
        if (!RegexRegistry.isRegexToken(pattern) && pattern.textValue().isEmpty())
            throw new RuntimeEvaluationException("D3010", "$replace: second argument cannot be an empty string");
        if (LambdaRegistry.isLambdaToken(replacement)) {
            // Lambda replacement: check return type later per match
        }
        org.joni.Regex rx = RegexRegistry.isRegexToken(pattern)
                ? RegexRegistry.lookupRegex(pattern)
                : RegexRegistry.buildLiteralRegex(pattern.textValue());
        int lim = JsonataRuntime.missing(limit) ? Integer.MAX_VALUE : (int) limit.doubleValue();
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.joni.Matcher m = rx.matcher(bytes);
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        int count = 0;
        while (pos <= bytes.length && count < lim) {
            int found = m.search(pos, bytes.length, org.joni.Option.NONE);
            if (found < 0) break;
            int end = m.getEnd();
            sb.append(new String(bytes, pos, found - pos,
                    java.nio.charset.StandardCharsets.UTF_8));
            String matchStr = new String(bytes, found, end - found,
                    java.nio.charset.StandardCharsets.UTF_8);
            org.joni.Region region = m.getRegion();
            if (LambdaRegistry.isLambdaToken(replacement)) {
                ObjectNode matchObj = NF.objectNode();
                matchObj.put("match", matchStr);
                matchObj.put("index", bytePosToCharPos(s, found));
                ArrayNode groups = NF.arrayNode();
                if (region != null) {
                    for (int i = 1; i < region.getNumRegs(); i++) {
                        int gb = region.getBeg(i);
                        int ge = region.getEnd(i);
                        groups.add(gb >= 0
                                ? new String(bytes, gb, ge - gb, java.nio.charset.StandardCharsets.UTF_8)
                                : "");
                    }
                }
                matchObj.set("groups", groups);
                JsonNode repResult = LambdaRegistry.lookupLambda(replacement).apply(matchObj);
                if (!JsonataRuntime.missing(repResult) && !repResult.isTextual())
                    throw new RuntimeEvaluationException("D3012", "$replace: replacement function must return a string");
                sb.append(JsonataRuntime.missing(repResult) ? "" : repResult.textValue());
            } else {
                sb.append(expandReplacement(JsonataRuntime.toText(replacement), matchStr, bytes, region));
            }
            count++;
            if (end == found) {
                throw new RuntimeEvaluationException("D1004", "Regular expression matches zero length string");
            }
            pos = (end > found) ? end : end + 1;
        }
        if (pos <= bytes.length) {
            sb.append(new String(bytes, pos, bytes.length - pos,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return NF.textNode(sb.toString());
    }

    static JsonNode fn_join(JsonNode arr, JsonNode separator) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        if (!JsonataRuntime.missing(separator) && !separator.isTextual())
            throw new RuntimeEvaluationException(
                    "T0410", "$join: separator argument must be a string");
        if (!arr.isArray()) {
            if (!arr.isTextual())
                throw new RuntimeEvaluationException(
                        "T0412", "$join: function argument must be an array of strings");
            return arr;
        }
        for (JsonNode elem : arr) {
            if (!elem.isTextual())
                throw new RuntimeEvaluationException(
                        "T0412", "$join: function argument must be an array of strings");
        }
        String sep = JsonataRuntime.missing(separator) ? "" : separator.textValue();
        StringJoiner sj = new StringJoiner(sep);
        for (JsonNode elem : arr) sj.add(elem.textValue());
        return NF.textNode(sj.toString());
    }

    static JsonNode fn_string_prettify(JsonNode arg) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (arg.isTextual()) return arg;
        try {
            // Sanitize lambda tokens → "" before serializing
            JsonNode sanitized = JsonataRuntime.sanitizeForString(arg);
            String raw = PRETTY_WRITER.writeValueAsString(sanitized);
            // Jackson adds space after colon in object keys: replace " : " → ": "
            raw = raw.replace(" : ", ": ");
            // Jackson adds spaces inside empty arrays: "[ ]" → "[]"
            raw = raw.replace("[ ]", "[]");
            return NF.textNode(raw);
        } catch (RuntimeEvaluationException e) {
            throw new RuntimeEvaluationException(e.getErrorCode(), "$string: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeEvaluationException(null, "$string: " + e.getMessage());
        }
    }

    static JsonNode fn_pad(JsonNode str, JsonNode width, JsonNode padChar)
            throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(width)) return JsonataRuntime.MISSING;
        if (!str.isTextual())
            throw new RuntimeEvaluationException("T0410", "$pad: argument 1 must be a string");
        String s = str.textValue();
        int w = (int) JsonataRuntime.toNumber(width);
        String pc = JsonataRuntime.missing(padChar) ? " " : JsonataRuntime.toText(padChar);
        if (pc.isEmpty()) pc = " ";
        // Use Unicode codepoint count for string length
        int cpLen = s.codePointCount(0, s.length());
        int pcCpLen = pc.codePointCount(0, pc.length());
        int absW = Math.abs(w);
        if (cpLen >= absW) return NF.textNode(s);
        int need = absW - cpLen; // number of codepoints to add
        // Build padding: repeat pc codepoints until we have 'need' codepoints
        StringBuilder padding = new StringBuilder();
        int addedCp = 0;
        while (addedCp < need) {
            int take = Math.min(pcCpLen, need - addedCp);
            // Append 'take' codepoints from pc
            int charEnd = pc.offsetByCodePoints(0, take);
            padding.append(pc, 0, charEnd);
            addedCp += take;
        }
        String pad = padding.toString();
        return NF.textNode(w >= 0 ? s + pad : pad + s);
    }

    static JsonNode fn_eval(JsonNode expr, JsonNode context) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(expr)) return JsonataRuntime.MISSING;
        JsonataRuntime.EvalDelegate delegate = JsonataRuntime.getEvalDelegate();
        if (delegate == null) {
            throw new RuntimeEvaluationException(null,
                    "$eval: no eval delegate registered (create a JsonataExpressionFactory first)");
        }
        JsonNode ctx = JsonataRuntime.missing(context) ? JsonataRuntime.MISSING : context;
        return delegate.eval(JsonataRuntime.toText(expr), ctx);
    }

    static JsonNode fn_base64encode(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || !str.isTextual()) return JsonataRuntime.MISSING;
        byte[] bytes = str.textValue().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return NF.textNode(java.util.Base64.getEncoder().encodeToString(bytes));
    }

    static JsonNode fn_base64decode(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str) || !str.isTextual()) return JsonataRuntime.MISSING;
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(JsonataRuntime.toText(str));
            return NF.textNode(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeEvaluationException e) {
            throw new RuntimeEvaluationException(e.getErrorCode(), "$base64decode: invalid base64 input: " + e.getMessage());
        }
    }

    static JsonNode fn_encodeUrlComponent(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        return NF.textNode(percentEncode(JsonataRuntime.toText(str), false));
    }

    static JsonNode fn_decodeUrlComponent(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        try {
            return NF.textNode(percentDecode(JsonataRuntime.toText(str)));
        } catch (RuntimeEvaluationException e) {
            throw new RuntimeEvaluationException(e.getErrorCode(), "$decodeUrlComponent: " + e.getMessage());
        }
    }

    static JsonNode fn_encodeUrl(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        return NF.textNode(percentEncode(JsonataRuntime.toText(str), true));
    }

    static JsonNode fn_decodeUrl(JsonNode str) throws RuntimeEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        try {
            return NF.textNode(percentDecode(JsonataRuntime.toText(str)));
        } catch (RuntimeEvaluationException e) {
            throw new RuntimeEvaluationException(e.getErrorCode(), "$decodeUrl: " + e.getMessage());
        }
    }

    /**
     * Percent-encodes a string per RFC 3986.
     *
     * @param s            the string to encode
     * @param preserveReserved if true, keep RFC 3986 reserved characters unencoded
     *                     (suitable for full-URL encoding); if false, only keep
     *                     unreserved characters (suitable for URL component encoding)
     */
    private static String percentEncode(String s, boolean preserveReserved)
            throws RuntimeEvaluationException {
        // Reject lone surrogates (D3140)
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (Character.isHighSurrogate(c)) {
                if (k + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(k + 1)))
                    throw new RuntimeEvaluationException(
                            "D3140", "$encodeUrl/Component: the string contains an invalid Unicode character");
                k++; // valid surrogate pair, skip low
            } else if (Character.isLowSurrogate(c)) {
                throw new RuntimeEvaluationException(
                        "D3140", "$encodeUrl/Component: the string contains an invalid Unicode character");
            }
        }
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (isUnreserved(v) || (preserveReserved && isReserved(v))) {
                sb.append((char) v);
            } else {
                sb.append(String.format("%%%02X", v));
            }
        }
        return sb.toString();
    }

    /** Decodes percent-encoded sequences in a URL or URL component. Throws on malformed input. */
    private static String percentDecode(String s) throws RuntimeEvaluationException {
        byte[] bytes = new byte[s.length()];
        int len = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new RuntimeEvaluationException(
                            "D3140", "Malformed URL: incomplete percent sequence at position " + i);
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new RuntimeEvaluationException(
                            "D3140", "Malformed URL: invalid percent sequence '%" + s.charAt(i + 1) + s.charAt(i + 2) + "'");
                }
                bytes[len++] = (byte) (hi * 16 + lo);
                i += 3;
            } else {
                bytes[len++] = (byte) c;
                i++;
            }
        }
        // Validate that the resulting bytes are valid UTF-8
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes, 0, len);
        java.nio.charset.CharsetDecoder decoder =
                java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            return decoder.decode(buf).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new RuntimeEvaluationException("D3140", "Malformed URL: invalid UTF-8 sequence");
        }
    }

    /** RFC 3986 unreserved characters: A-Za-z0-9 - _ . ~ */
    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
    }

    /** RFC 3986 reserved characters (kept by $encodeUrl but encoded by $encodeUrlComponent). */
    private static boolean isReserved(int c) {
        return c == ':' || c == '/' || c == '?' || c == '#' || c == '[' || c == ']'
                || c == '@' || c == '!' || c == '$' || c == '&' || c == '\''
                || c == '(' || c == ')' || c == '*' || c == '+' || c == ',' || c == ';' || c == '=';
    }

    private static String expandReplacement(String repl, String wholeMatch,
                                             byte[] bytes, org.joni.Region region) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < repl.length()) {
            char c = repl.charAt(i);
            if (c == '$' && i + 1 < repl.length() && Character.isDigit(repl.charAt(i + 1))) {
                // Parse all consecutive digits, stopping at $ or end
                int j = i + 1;
                while (j < repl.length() && Character.isDigit(repl.charAt(j))) j++;
                // Stop at $ (reserved for next group reference) or end
                if (j < repl.length() && repl.charAt(j) == '$') {
                    j = i + 1; // Reset to just after the first $
                    while (j < repl.length() && Character.isDigit(repl.charAt(j))) j++;
                }
                String digits = repl.substring(i + 1, j);
                // Try from longest group reference to shortest (greedy fallback)
                boolean matched = false;
                for (int len = digits.length(); len >= 1; len--) {
                    int idx = Integer.parseInt(digits.substring(0, len));
                    String literal = digits.substring(len); // remaining digits after this prefix
                    if (idx == 0) {
                        out.append(wholeMatch).append(literal);
                        matched = true;
                        break;
                    } else if (region != null && idx < region.getNumRegs()) {
                        int gb = region.getBeg(idx);
                        int ge = region.getEnd(idx);
                        if (gb >= 0) {
                            out.append(new String(bytes, gb, ge - gb, java.nio.charset.StandardCharsets.UTF_8));
                            out.append(literal);
                            matched = true;
                            break;
                        }
                        // Group exists but not captured - output literal suffix
                        out.append(literal);
                        matched = true;
                        break;
                    } else {
                        // No group found at this index - treat as empty capture for shorter fallback
                        if (len == 1) {
                            // Single-digit - output remaining literal (empty for just this)
                            out.append(literal);
                            matched = true;
                            break;
                        }
                        // Multi-digit - continue to try shorter
                    }
                }
                if (!matched) {
                    // Fallback: output digits as-is
                    out.append(digits);
                }
                i = j;
            } else if (c == '$' && i + 1 < repl.length() && repl.charAt(i + 1) == '$') {
                out.append('$');
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int bytePosToCharPos(String s, int bytePos) {
        int charPos = 0;
        int b = 0;
        while (b < bytePos && charPos < s.length()) {
            int cp = s.codePointAt(charPos);
            charPos += Character.charCount(cp);
            b += new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return charPos;
    }
}
