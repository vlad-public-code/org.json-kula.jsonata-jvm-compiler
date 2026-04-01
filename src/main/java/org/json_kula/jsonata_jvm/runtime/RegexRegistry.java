package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry for compiled regex objects used by JSONata regex literals.
 *
 * <p>Regex literals are compiled once and stored here with a sentinel {@link
 * com.fasterxml.jackson.databind.node.TextNode} key so they can flow through
 * the Jackson type system.
 */
final class RegexRegistry {

    private RegexRegistry() {}

    private static final ConcurrentHashMap<String, org.joni.Regex> REGEX_REGISTRY =
            new ConcurrentHashMap<>();
    static final String REGEX_PREFIX = "__rx:";
    private static final AtomicLong REGEX_COUNTER = new AtomicLong();

    /**
     * Compiles a JSONata regex literal and stores it in the registry.
     * Returns a sentinel {@link com.fasterxml.jackson.databind.node.TextNode} with
     * prefix {@code "__rx:"} that can be passed through the Jackson type system and
     * later resolved by {@link #lookupRegex}.
     *
     * @param pattern the regex pattern string (without delimiters)
     * @param flags   flags string: {@code "i"} for case-insensitive,
     *                {@code "m"} for multiline, or {@code ""} for none
     */
    static JsonNode regexNode(String pattern, String flags) throws JsonataEvaluationException {
        int opts = org.joni.Option.NONE;
        if (flags.contains("i")) opts |= org.joni.Option.IGNORECASE;
        if (flags.contains("m")) opts |= org.joni.Option.MULTILINE;
        byte[] pat = pattern.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.joni.Regex rx = new org.joni.Regex(pat, 0, pat.length, opts,
                org.jcodings.specific.UTF8Encoding.INSTANCE,
                org.joni.Syntax.ECMAScript,
                org.joni.WarnCallback.DEFAULT);
        String key = String.valueOf(REGEX_COUNTER.incrementAndGet());
        REGEX_REGISTRY.put(key, rx);
        return JsonNodeFactory.instance.textNode(REGEX_PREFIX + key);
    }

    /** Returns {@code true} if {@code n} is a regex sentinel token. */
    static boolean isRegexToken(JsonNode n) {
        return n != null && n.isTextual() && n.textValue().startsWith(REGEX_PREFIX);
    }

    /** Resolves the regex sentinel token to the compiled {@link org.joni.Regex}. */
    static org.joni.Regex lookupRegex(JsonNode n) throws JsonataEvaluationException {
        String key = n.textValue().substring(REGEX_PREFIX.length());
        org.joni.Regex rx = REGEX_REGISTRY.get(key);
        if (rx == null) throw new JsonataEvaluationException("Regex token expired: " + key);
        return rx;
    }

    /** Builds a regex that matches the literal string {@code s} (no special regex chars). */
    static org.joni.Regex buildLiteralRegex(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Escape ECMAScript metacharacters
            if ("\\^$.|?*+()[]{}/".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        byte[] pat = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new org.joni.Regex(pat, 0, pat.length, org.joni.Option.NONE,
                org.jcodings.specific.UTF8Encoding.INSTANCE,
                org.joni.Syntax.ECMAScript,
                org.joni.WarnCallback.DEFAULT);
    }
}
