package org.json_kula.jsonata_jvm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry for compiled regex objects used by JSONata regex literals.
 *
 * <p>Regex literals are compiled once and stored here with a sentinel {@link
 * com.fasterxml.jackson.databind.node.TextNode} key so they can flow through
 * the Jackson type system.
 */
final class RegexRegistry {

    private RegexRegistry() {}

    /** Bounded static LRU cache: fallback when no evaluation context is active (max 100 entries). */
    private static final Map<String, org.joni.Regex> REGEX_REGISTRY =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, org.joni.Regex> eldest) {
                    return size() > 100;
                }
            });
    static final String REGEX_PREFIX = "__rx:";

    /**
     * Compiles a JSONata regex literal and stores it in the per-instance or static cache.
     * Uses {@code pattern + "\0" + flags} as a stable cache key so the same regex literal
     * is compiled at most once per expression instance regardless of evaluation count.
     *
     * @param pattern the regex pattern string (without delimiters)
     * @param flags   flags string: {@code "i"} for case-insensitive,
     *                {@code "m"} for multiline, or {@code ""} for none
     */
    static JsonNode regexNode(String pattern, String flags) throws RuntimeEvaluationException {
        String key = pattern + "\0" + flags;
        Map<String, org.joni.Regex> instanceMap = EvaluationContext.getInstanceRegexes();
        org.joni.Regex compiled;
        if (instanceMap != null) {
            compiled = instanceMap.computeIfAbsent(key, k -> compile(pattern, flags));
        } else {
            synchronized (REGEX_REGISTRY) {
                compiled = REGEX_REGISTRY.computeIfAbsent(key, k -> compile(pattern, flags));
            }
        }
        return new RegexNode(compiled, pattern, flags);
    }

    private static org.joni.Regex compile(String pattern, String flags) {
        int opts = org.joni.Option.NONE;
        if (flags.contains("i")) opts |= org.joni.Option.IGNORECASE;
        if (flags.contains("m")) opts |= org.joni.Option.MULTILINE;
        byte[] pat = pattern.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new org.joni.Regex(pat, 0, pat.length, opts,
                org.jcodings.specific.UTF8Encoding.INSTANCE,
                org.joni.Syntax.ECMAScript,
                org.joni.WarnCallback.DEFAULT);
    }

    /** Returns {@code true} if {@code n} is a compiled regex value. */
    static boolean isRegexToken(JsonNode n) {
        return n instanceof RegexNode;
    }

    /** Returns the compiled {@link org.joni.Regex} carried by {@code n}. */
    static org.joni.Regex lookupRegex(JsonNode n) throws RuntimeEvaluationException {
        if (!(n instanceof RegexNode rx))
            throw new RuntimeEvaluationException(null, "Not a regular expression: " + n);
        return rx.regex();
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
