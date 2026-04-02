package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;

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
    private static final ObjectWriter PRETTY_WRITER =
            new ObjectMapper().writerWithDefaultPrettyPrinter();

    static JsonNode fn_uppercase(JsonNode arg) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        return NF.textNode(JsonataRuntime.toText(arg).toUpperCase());
    }

    static JsonNode fn_lowercase(JsonNode arg) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        return NF.textNode(JsonataRuntime.toText(arg).toLowerCase());
    }

    static JsonNode fn_trim(JsonNode arg) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        // JSONata $trim normalises whitespace: collapse all internal runs of
        // whitespace (tabs, newlines, multiple spaces) to a single space and
        // strip leading/trailing whitespace.
        return NF.textNode(JsonataRuntime.toText(arg).replaceAll("\\s+", " ").strip());
    }

    static JsonNode fn_length(JsonNode arg) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        return NF.numberNode(JsonataRuntime.toText(arg).length());
    }

    static JsonNode fn_substring(JsonNode str, JsonNode start) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);
        int begin = JsonataRuntime.clampIndex((int) JsonataRuntime.toNumber(start), s.length());
        return NF.textNode(s.substring(begin));
    }

    static JsonNode fn_substring(JsonNode str, JsonNode start, JsonNode length)
            throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);
        int len   = s.length();
        int begin = JsonataRuntime.clampIndex((int) JsonataRuntime.toNumber(start), len);
        int end   = Math.min(begin + (int) JsonataRuntime.toNumber(length), len);
        return NF.textNode(begin < end ? s.substring(begin, end) : "");
    }

    static JsonNode fn_substringBefore(JsonNode str, JsonNode chars)
            throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(chars)) return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);
        String c = JsonataRuntime.toText(chars);
        int idx = s.indexOf(c);
        return NF.textNode(idx < 0 ? s : s.substring(0, idx));
    }

    static JsonNode fn_substringAfter(JsonNode str, JsonNode chars)
            throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(chars)) return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);
        String c = JsonataRuntime.toText(chars);
        int idx = s.indexOf(c);
        return NF.textNode(idx < 0 ? "" : s.substring(idx + c.length()));
    }

    static JsonNode fn_contains(JsonNode str, JsonNode search) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(search)) return JsonataRuntime.MISSING;
        if (RegexRegistry.isRegexToken(search)) {
            byte[] bytes = JsonataRuntime.toText(str).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return JsonataRuntime.bool(RegexRegistry.lookupRegex(search).matcher(bytes)
                    .search(0, bytes.length, org.joni.Option.NONE) >= 0);
        }
        return JsonataRuntime.bool(JsonataRuntime.toText(str).contains(JsonataRuntime.toText(search)));
    }

    static JsonNode fn_split(JsonNode str, JsonNode separator) throws JsonataEvaluationException {
        return fn_split(str, separator, JsonataRuntime.MISSING);
    }

    static JsonNode fn_split(JsonNode str, JsonNode separator, JsonNode limit)
            throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(separator)) return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);
        int lim = JsonataRuntime.missing(limit) ? -1 : (int) JsonataRuntime.toNumber(limit);
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

    static JsonNode fn_match(JsonNode str, JsonNode pattern) throws JsonataEvaluationException {
        return fn_match(str, pattern, JsonataRuntime.MISSING);
    }

    static JsonNode fn_match(JsonNode str, JsonNode pattern, JsonNode limit)
            throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(pattern)) return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);
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

    static JsonNode fn_replace(JsonNode str, JsonNode pattern, JsonNode replacement)
            throws JsonataEvaluationException {
        return fn_replace(str, pattern, replacement, JsonataRuntime.MISSING);
    }

    static JsonNode fn_replace(JsonNode str, JsonNode pattern,
                                JsonNode replacement, JsonNode limit)
            throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(pattern) || JsonataRuntime.missing(replacement))
            return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);
        org.joni.Regex rx = RegexRegistry.isRegexToken(pattern)
                ? RegexRegistry.lookupRegex(pattern)
                : RegexRegistry.buildLiteralRegex(JsonataRuntime.toText(pattern));
        int lim = JsonataRuntime.missing(limit) ? Integer.MAX_VALUE : (int) JsonataRuntime.toNumber(limit);
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
                sb.append(JsonataRuntime.toText(LambdaRegistry.lookupLambda(replacement).apply(matchObj)));
            } else {
                sb.append(expandReplacement(JsonataRuntime.toText(replacement), matchStr, bytes, region));
            }
            count++;
            pos = (end > found) ? end : end + 1;
        }
        if (pos <= bytes.length) {
            sb.append(new String(bytes, pos, bytes.length - pos,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return NF.textNode(sb.toString());
    }

    static JsonNode fn_join(JsonNode arr, JsonNode separator) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(arr)) return JsonataRuntime.MISSING;
        String sep = JsonataRuntime.missing(separator) ? "" : JsonataRuntime.toText(separator);
        if (!arr.isArray()) return JsonataRuntime.fn_string(arr);
        StringJoiner sj = new StringJoiner(sep);
        for (JsonNode elem : arr) sj.add(JsonataRuntime.toText(elem));
        return NF.textNode(sj.toString());
    }

    static JsonNode fn_string_prettify(JsonNode arg) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(arg)) return JsonataRuntime.MISSING;
        if (arg.isTextual()) return arg;
        try {
            return NF.textNode(PRETTY_WRITER.writeValueAsString(arg));
        } catch (Exception e) {
            throw new JsonataEvaluationException("$string: " + e.getMessage());
        }
    }

    static JsonNode fn_pad(JsonNode str, JsonNode width, JsonNode padChar)
            throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str) || JsonataRuntime.missing(width)) return JsonataRuntime.MISSING;
        String s = JsonataRuntime.toText(str);
        int w = (int) JsonataRuntime.toNumber(width);
        String pc = JsonataRuntime.missing(padChar) ? " " : JsonataRuntime.toText(padChar);
        if (pc.isEmpty()) pc = " ";
        int absW = Math.abs(w);
        if (s.length() >= absW) return NF.textNode(s);
        int need = absW - s.length();
        // Repeat padChar chars until we have 'need' characters
        StringBuilder padding = new StringBuilder();
        while (padding.length() < need) padding.append(pc);
        String pad = padding.substring(0, need);
        return NF.textNode(w >= 0 ? s + pad : pad + s);
    }

    static JsonNode fn_eval(JsonNode expr, JsonNode context) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(expr)) return JsonataRuntime.MISSING;
        JsonataRuntime.EvalDelegate delegate = JsonataRuntime.getEvalDelegate();
        if (delegate == null) {
            throw new JsonataEvaluationException(
                    "$eval: no eval delegate registered (create a JsonataExpressionFactory first)");
        }
        JsonNode ctx = JsonataRuntime.missing(context) ? JsonataRuntime.MISSING : context;
        return delegate.eval(JsonataRuntime.toText(expr), ctx);
    }

    static JsonNode fn_base64encode(JsonNode str) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        byte[] bytes = JsonataRuntime.toText(str).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return NF.textNode(java.util.Base64.getEncoder().encodeToString(bytes));
    }

    static JsonNode fn_base64decode(JsonNode str) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(JsonataRuntime.toText(str));
            return NF.textNode(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new JsonataEvaluationException("$base64decode: invalid base64 input");
        }
    }

    static JsonNode fn_encodeUrlComponent(JsonNode str) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        return NF.textNode(percentEncode(JsonataRuntime.toText(str), false));
    }

    static JsonNode fn_decodeUrlComponent(JsonNode str) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        try {
            return NF.textNode(percentDecode(JsonataRuntime.toText(str)));
        } catch (Exception e) {
            throw new JsonataEvaluationException("$decodeUrlComponent: " + e.getMessage());
        }
    }

    static JsonNode fn_encodeUrl(JsonNode str) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        return NF.textNode(percentEncode(JsonataRuntime.toText(str), true));
    }

    static JsonNode fn_decodeUrl(JsonNode str) throws JsonataEvaluationException {
        if (JsonataRuntime.missing(str)) return JsonataRuntime.MISSING;
        try {
            return NF.textNode(percentDecode(JsonataRuntime.toText(str)));
        } catch (Exception e) {
            throw new JsonataEvaluationException("$decodeUrl: " + e.getMessage());
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
    private static String percentEncode(String s, boolean preserveReserved) {
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

    /** Decodes percent-encoded sequences in a URL or URL component. */
    private static String percentDecode(String s) {
        byte[] bytes = new byte[s.length()];
        int len = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes[len++] = (byte) (hi * 16 + lo);
                    i += 3;
                    continue;
                }
            }
            bytes[len++] = (byte) c;
            i++;
        }
        return new String(bytes, 0, len, java.nio.charset.StandardCharsets.UTF_8);
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
            if (c == '$' && i + 1 < repl.length()) {
                char next = repl.charAt(i + 1);
                if (next == '$') {
                    out.append('$');
                    i += 2;
                } else if (Character.isDigit(next)) {
                    int j = i + 1;
                    while (j < repl.length() && Character.isDigit(repl.charAt(j))) j++;
                    int idx = Integer.parseInt(repl.substring(i + 1, j));
                    if (idx == 0) {
                        out.append(wholeMatch);
                    } else if (region != null && idx < region.getNumRegs()) {
                        int gb = region.getBeg(idx);
                        int ge = region.getEnd(idx);
                        if (gb >= 0) {
                            out.append(new String(bytes, gb, ge - gb,
                                    java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                    i = j;
                } else {
                    out.append(c);
                    i++;
                }
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
