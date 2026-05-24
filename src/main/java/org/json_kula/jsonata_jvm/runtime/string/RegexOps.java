package org.json_kula.jsonata_jvm.runtime.string;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.runtime.JsonataRuntime;
import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;
import org.joni.Region;

import java.nio.charset.StandardCharsets;

/**
 * Shared regex utilities for {@code $match}, {@code $replace}, and related functions.
 *
 * <p>Fixes vs the original {@code StringBuiltins}:
 * <ul>
 *   <li>{@link #bytePosToCharPos} no longer allocates temporary objects per codepoint —
 *       UTF-8 byte length is now computed arithmetically.
 *   <li>{@link #expandReplacement} had dead code (a j-reset + re-scan that was a no-op);
 *       removed.
 * </ul>
 */
final class RegexOps {

    private RegexOps() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    /**
     * Converts a byte offset in the UTF-8 encoding of {@code s} to a character (UTF-16) index.
     * Computes byte length arithmetically — no per-codepoint allocation.
     */
    static int bytePosToCharPos(String s, int bytePos) {
        int charPos = 0;
        int b = 0;
        while (b < bytePos && charPos < s.length()) {
            int cp = s.codePointAt(charPos);
            charPos += Character.charCount(cp);
            b += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
        }
        return charPos;
    }

    /**
     * Expands a JSONata replacement string:
     * {@code $0} → whole match, {@code $N} → capture group N, {@code $$} → literal {@code $}.
     * Uses greedy longest-first group-reference parsing.
     */
    static String expandReplacement(String repl, String wholeMatch,
                                    byte[] bytes, Region region) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < repl.length()) {
            char c = repl.charAt(i);
            if (c == '$' && i + 1 < repl.length() && Character.isDigit(repl.charAt(i + 1))) {
                int j = i + 1;
                while (j < repl.length() && Character.isDigit(repl.charAt(j))) j++;
                String digits = repl.substring(i + 1, j);
                boolean matched = false;
                for (int len = digits.length(); len >= 1; len--) {
                    int idx = Integer.parseInt(digits.substring(0, len));
                    String literal = digits.substring(len);
                    if (idx == 0) {
                        out.append(wholeMatch).append(literal);
                        matched = true;
                        break;
                    } else if (region != null && idx < region.getNumRegs()) {
                        int gb = region.getBeg(idx);
                        int ge = region.getEnd(idx);
                        if (gb >= 0)
                            out.append(new String(bytes, gb, ge - gb, StandardCharsets.UTF_8));
                        out.append(literal);
                        matched = true;
                        break;
                    } else if (len == 1) {
                        out.append(literal);
                        matched = true;
                        break;
                    }
                }
                if (!matched) out.append(digits);
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

    /** Drives {@code $match} when the pattern is a custom lambda matcher. */
    static JsonNode matchWithLambda(String s, JsonNode pattern, int limit)
            throws RuntimeEvaluationException {
        ArrayNode results = NF.arrayNode();
        int count = 0;
        JsonNode currentPattern = pattern;
        boolean firstCall = true;

        while (count < limit) {
            JsonNode result;
            if (firstCall) {
                result = JsonataRuntime.fn_apply(currentPattern, NF.textNode(s));
                firstCall = false;
            } else {
                result = JsonataRuntime.fn_apply(currentPattern, JsonataRuntime.NULL);
            }

            if (JsonataRuntime.missing(result)) break;
            if (!result.isObject()) break;

            JsonNode match  = result.get("match");
            JsonNode start  = result.get("start");
            JsonNode end    = result.get("end");
            JsonNode groups = result.get("groups");
            JsonNode next   = result.get("next");

            if (JsonataRuntime.missing(match) || JsonataRuntime.missing(start)
                    || JsonataRuntime.missing(end)) break;

            ObjectNode out = NF.objectNode();
            out.put("match", match.asText());
            out.put("index", start.asInt());
            out.set("groups", JsonataRuntime.missing(groups) ? NF.arrayNode() : groups);
            results.add(out);
            count++;

            if (!JsonataRuntime.missing(next) && JsonataRuntime.isLambdaToken(next)) {
                currentPattern = next;
            } else {
                break;
            }
        }
        return results.isEmpty() ? JsonataRuntime.MISSING : results;
    }
}
