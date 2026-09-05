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

    /**
     * Translates JSONata's JavaScript regex flags into Joni options.
     *
     * <p>Joni is Oniguruma, and Oniguruma's option <em>names</em> do not mean what the
     * JavaScript flag letters of the same name mean. Verified against Joni directly:
     * <ul>
     *   <li>{@code SINGLELINE} makes {@code ^} and {@code $} anchor the whole string —
     *       that is JavaScript's behaviour <em>without</em> {@code /m}, so it is the
     *       default and must be set when {@code m} is absent;
     *   <li>Oniguruma's default (no option) makes {@code ^}/{@code $} line anchors, which
     *       is JavaScript {@code /m};
     *   <li>{@code MULTILINE} makes {@code .} match a newline — that is JavaScript
     *       {@code /s}, not {@code /m}.
     * </ul>
     *
     * <p>Mapping {@code m} onto {@code MULTILINE} therefore got it wrong twice: every
     * pattern behaved as if it carried {@code /m}, and an explicit {@code m} turned on
     * dot-matches-newline instead. {@code $match("a
b", /$/)} found two matches where
     * JavaScript finds one.
     */

    /**
     * ECMAScript's {@code .}: any character except the four line terminators.
     *
     * <p>Written with the literal U+2028 and U+2029 characters because Joni's ECMAScript
     * syntax does not understand {@code \\x{...}} — it consumed the following character
     * instead of rejecting the pattern, which silently dropped one character in every
     * other match.
     */
    private static final String ECMASCRIPT_DOT = "[^\\n\\r\u2028\u2029]";


    /**
     * {@code ^} and {@code $} under the {@code m} flag.
     *
     * <p>Oniguruma's line anchors break lines on {@code \\n} only; ECMAScript also breaks
     * on {@code \\r}, U+2028 and U+2029. Each anchor is therefore widened with a
     * zero-width alternative covering the three Oniguruma misses, leaving its own
     * {@code \\n} and string-boundary handling untouched.
     */
    private static final String MULTILINE_CARET = "(?:^|(?<=[\\r\u2028\u2029]))";
    private static final String MULTILINE_DOLLAR = "(?:$|(?=[\\r\u2028\u2029]))";

    private static int joniOptions(String flags) {
        int opts = org.joni.Option.NONE;
        if (flags.contains("i")) opts |= org.joni.Option.IGNORECASE;
        if (!flags.contains("m")) opts |= org.joni.Option.SINGLELINE;
        return opts;
    }


    /**
     * Rewrites the two constructs where Oniguruma still disagrees with ECMAScript after
     * {@link #joniOptions} has set the right mode.
     *
     * <ul>
     *   <li><b>{@code $}</b> — Oniguruma's {@code SINGLELINE} {@code $} still matches
     *       before a single trailing newline (Ruby's {@code \Z}), so
     *       {@code $match("tail\n", /$/)} found two matches. ECMAScript without
     *       {@code /m} anchors only at the very end, which is {@code \z}.
     *   <li><b>{@code .}</b> — Oniguruma's {@code .} excludes only {@code \n};
     *       ECMAScript's also excludes {@code \r}, U+2028 and U+2029.
     * </ul>
     *
     * <p>The scan tracks escapes and character-class nesting so an escaped {@code \.} or
     * a {@code .} inside {@code [...]} is left alone — inside a class both characters are
     * literal and rewriting them would change what the class matches.
     *
     * <p>Under the {@code m} flag {@code ^}/{@code $} stay as Oniguruma's line anchors.
     * Those break lines on {@code \n} only, where ECMAScript also breaks on {@code \r},
     * U+2028 and U+2029 — a narrower residual difference, recorded rather than papered
     * over with a lookaround rewrite.
     */
    static String toEcmaScriptDialect(String pattern, boolean multiline) {
        StringBuilder out = new StringBuilder(pattern.length() + 16);
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                out.append(c).append(pattern.charAt(++i));
                continue;
            }
            if (inClass) {
                if (c == ']') inClass = false;
                out.append(c);
                continue;
            }
            switch (c) {
                case '[' -> { inClass = true; out.append(c); }
                case '^' -> { if (multiline) out.append(MULTILINE_CARET); else out.append(c); }
                case '.' -> out.append(ECMASCRIPT_DOT);
                case '$' -> out.append(multiline ? MULTILINE_DOLLAR : "\\z");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static org.joni.Regex compile(String pattern, String flags) {
        String rewritten = toEcmaScriptDialect(pattern, flags.contains("m"));
        byte[] pat = rewritten.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new org.joni.Regex(pat, 0, pat.length, joniOptions(flags),
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
        return new org.joni.Regex(pat, 0, pat.length, joniOptions(""),
                org.jcodings.specific.UTF8Encoding.INSTANCE,
                org.joni.Syntax.ECMAScript,
                org.joni.WarnCallback.DEFAULT);
    }
}
