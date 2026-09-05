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
    /**
     * Expands a JSONata replacement string's {@code $$}, {@code $0} and {@code $N}
     * references — a port of the reference's own routine, whose rules are not the obvious
     * ones.
     *
     * <p>{@code $0} is recognised before any digit parsing, so {@code "$01"} is the whole
     * match followed by a literal {@code 1}, not group 1. How many digits a {@code $N}
     * may consume is derived from the <em>number of capture groups</em>, and an index
     * past the last group is retried one digit shorter. The cursor then advances by the
     * length of the parsed index rather than by the digits consumed. A greedy
     * longest-first scan looks equivalent and is not.
     */
    static String expandReplacement(String repl, String wholeMatch, ArrayNode groups) {
        StringBuilder out = new StringBuilder();
        int position = 0;
        int index = repl.indexOf('$', position);
        while (index != -1 && position < repl.length()) {
            out.append(repl, position, index);
            position = index + 1;
            // A '$' at the very end of the replacement has no following character; the
            // sentinel simply has to be something that is neither '$' nor '0'.
            char dollarVal = position < repl.length() ? repl.charAt(position) : ' ';
            if (dollarVal == '$') {
                out.append('$');
                position++;
            } else if (dollarVal == '0') {
                out.append(wholeMatch);
                position++;
            } else {
                int maxDigits = groups.isEmpty()
                        ? 1
                        : (int) Math.floor(Math.log10(groups.size())) + 1;
                Integer groupIndex = parseLeadingInt(repl, position, maxDigits);
                if (maxDigits > 1 && groupIndex != null && groupIndex > groups.size()) {
                    groupIndex = parseLeadingInt(repl, position, maxDigits - 1);
                }
                if (groupIndex != null) {
                    if (!groups.isEmpty() && groupIndex >= 1 && groupIndex <= groups.size()) {
                        JsonNode submatch = groups.get(groupIndex - 1);
                        if (!submatch.isNull()) out.append(submatch.asText());
                    }
                    position += String.valueOf(groupIndex).length();
                } else {
                    out.append('$');
                }
            }
            index = repl.indexOf('$', position);
        }
        out.append(repl.substring(position));
        return out.toString();
    }

    /** {@code parseInt} of at most {@code maxDigits} characters, or null if there are none. */
    private static Integer parseLeadingInt(String s, int from, int maxDigits) {
        int end = Math.min(from + maxDigits, s.length());
        int i = from;
        while (i < end && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == from) return null;
        return Integer.parseInt(s.substring(from, i));
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

    /**
     * One match produced by {@link MatchCursor}. Offsets are UTF-16 character indices —
     * what JSONata reports — while the cursor itself works in UTF-8 byte offsets, which is
     * what Joni operates on.
     */
    record Match(String match, int start, int end, ArrayNode groups, int endByte) {}

    /**
     * The single regex cursor shared by {@code $match}, {@code $split}, {@code $replace}
     * and {@code $contains}.
     *
     * <p>Each built-in used to run its own scan loop, and they disagreed: only
     * {@code $replace} raised D1004, and it raised it on the <em>first</em> empty match,
     * while {@code $match} and {@code $split} advanced a byte at a time and silently
     * returned one empty match per position. Worse, Joni re-anchors {@code ^} at the
     * subject start, so {@code $match("abc", /^/)} found offset 0 no matter where the scan
     * was told to begin: {@code pos = end + 1} never advanced and the call built matches
     * until the heap was gone.
     *
     * <p>The reference's rule, reproduced here, is subtler than "reject empty matches".
     * The cursor is the previous match's end, and the guard fires only on a
     * <em>produced subsequent</em> empty match — so a first empty match is legal
     * ({@code $match("abc", /$/)} is one match at index 3) while a second one is D1004
     * ({@code $match("abc", /^/)}), because that is the case that could never progress.
     */
    static final class MatchCursor {

        private final String subject;
        private final byte[] bytes;
        private final org.joni.Matcher matcher;
        private int cursor;              // byte offset just past the previous match
        private boolean started;

        MatchCursor(String subject, org.joni.Regex regex) {
            this.subject = subject;
            this.bytes = subject.getBytes(StandardCharsets.UTF_8);
            this.matcher = regex.matcher(bytes);
        }

        /**
         * Returns the next match, or {@code null} when the scan is done.
         *
         * @throws RuntimeEvaluationException D1004 if a subsequent match is zero-length
         */
        Match next() throws RuntimeEvaluationException {
            if (started && cursor >= bytes.length) return null;

            int found = matcher.search(cursor, bytes.length, org.joni.Option.NONE);
            // Joni can report a match that begins before the requested start — an
            // anchored `^` does exactly that. ECMAScript semantics require a match at or
            // after the cursor, so anything earlier means "no further match".
            if (found < 0 || found < cursor) return null;

            int endByte = matcher.getEnd();
            String text = new String(bytes, found, endByte - found, StandardCharsets.UTF_8);

            if (started && text.isEmpty()) {
                throw new RuntimeEvaluationException("D1004",
                        "Regular expression matches zero length string");
            }

            ArrayNode groups = NF.arrayNode();
            org.joni.Region region = matcher.getRegion();
            if (region != null) {
                for (int i = 1; i < region.getNumRegs(); i++) {
                    int begin = region.getBeg(i);
                    int end = region.getEnd(i);
                    // A group that did not participate is null, not an empty string —
                    // the two are distinguishable, and $replace's "$N" expansion and a
                    // replacer function both see the difference.
                    if (begin >= 0) {
                        groups.add(new String(bytes, begin, end - begin, StandardCharsets.UTF_8));
                    } else {
                        groups.addNull();
                    }
                }
            }

            started = true;
            cursor = endByte;
            return new Match(text, bytePosToCharPos(subject, found),
                    bytePosToCharPos(subject, endByte), groups, endByte);
        }
    }


    /** Renders a cursor result as the matcher-closure object, or MISSING at the end. */
    static JsonNode toMatchObject(Match found) {
        if (found == null) return JsonataRuntime.MISSING;
        ObjectNode obj = NF.objectNode();
        obj.put("match", found.match());
        obj.put("start", found.start());
        obj.put("end", found.end());
        obj.set("groups", found.groups());
        return obj;
    }
}
