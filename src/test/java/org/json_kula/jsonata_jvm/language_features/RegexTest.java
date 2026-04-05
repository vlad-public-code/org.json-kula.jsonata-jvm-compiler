package org.json_kula.jsonata_jvm.language_features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.junit.jupiter.api.Test;

import static org.json_kula.jsonata_jvm.JsonNodeTestHelper.EMPTY_OBJECT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JSONata regex support:
 *   - regex literal syntax /pattern/flags
 *   - $contains with regex
 *   - $split with regex and limit
 *   - $match
 *   - $replace with string replacement and lambda replacement
 */
class RegexTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    private JsonNode eval(String expr) throws Exception {
        return FACTORY.compile(expr).evaluate(EMPTY_OBJECT);
    }

    private void assertJsonEqual(String expected, JsonNode actual) throws Exception {
        assertEquals(MAPPER.readTree(expected).toString(), actual.toString());
    }

    // =========================================================================
    // $contains
    // =========================================================================

    @Test
    void contains_stringPattern() throws Exception {
        assertJsonEqual("true",  eval("$contains(\"abracadabra\", \"bra\")"));
        assertJsonEqual("false", eval("$contains(\"abracadabra\", \"xyz\")"));
    }

    @Test
    void contains_regexCaseSensitive() throws Exception {
        assertJsonEqual("true",  eval("$contains(\"Hello World\", /World/)"));
        assertJsonEqual("false", eval("$contains(\"Hello World\", /world/)"));
    }

    @Test
    void contains_regexCaseInsensitive() throws Exception {
        assertJsonEqual("true",  eval("$contains(\"Hello World\", /hello/i)"));
        assertJsonEqual("true",  eval("$contains(\"Hello World\", /wo/i)"));
    }

    @Test
    void contains_regexMultiline() throws Exception {
        // In joni ECMAScript mode, ^ anchors to line start by default.
        // The 'm' flag (MULTILINE) makes '.' span newlines (Oniguruma semantics).
        assertJsonEqual("true",  eval("$contains(\"line1\\nline2\", /^line2/)"));
        assertJsonEqual("true",  eval("$contains(\"line1\\nline2\", /line1.line2/m)"));
        assertJsonEqual("false", eval("$contains(\"line1\\nline2\", /line1.line2/)"));
    }

    // =========================================================================
    // Division still works (regression: / must not always start a regex)
    // =========================================================================

    @Test
    void divisionNotRegex() throws Exception {
        assertJsonEqual("5", eval("10 / 2"));
    }

    @Test
    void divisionAfterVariable() throws Exception {
        assertJsonEqual("5", eval("($x := 10; $x / 2)"));
    }

    @Test
    void divisionAfterParenthesis() throws Exception {
        assertJsonEqual("3", eval("(2 + 4) / 2"));
    }

    // =========================================================================
    // $split
    // =========================================================================

    @Test
    void split_stringPattern() throws Exception {
        assertJsonEqual("[\"so\",\"many\",\"words\"]",
                eval("$split(\"so many words\", \" \")"));
    }

    @Test
    void split_regexPattern() throws Exception {
        assertJsonEqual("[\"too\",\"much\",\"punctuation\",\"hard\",\"to\",\"read\"]",
                eval("$split(\"too much, punctuation. hard; to read\", /[ ,.;]+/)"));
    }

    @Test
    void split_withLimit() throws Exception {
        assertJsonEqual("[\"a\",\"b\"]",
                eval("$split(\"a1b2c3\", /[0-9]/, 2)"));
    }

    @Test
    void split_stringPatternWithDot() throws Exception {
        // dot in string separator must NOT be treated as a regex metacharacter
        assertJsonEqual("[\"a\",\"b\",\"c\"]",
                eval("$split(\"a.b.c\", \".\")"));
    }

    // =========================================================================
    // $match
    // =========================================================================

    @Test
    void match_noCaptures() throws Exception {
        assertJsonEqual("""
                [{"match":"1","index":5,"groups":[]},
                 {"match":"2","index":12,"groups":[]}]
                """,
                eval("$match(\"test 1 test 2\", /[0-9]+/)"));
    }

    @Test
    void match_withCaptures() throws Exception {
        JsonNode result = eval("$match(\"ababbabbcc\", /a(b+)/)");
        // Expect 3 matches: "ab","abb","abbb" (no—re-check: "ab"@0, "abb"@2, "ab"@5?
        // actual: ababbabbcc
        // match 1: "ab" at 0, group "b"
        // match 2: "abb" at 2, group "bb"
        // match 3: "abb" at 5, group "bb"
        assertTrue(result.isArray());
        assertEquals(3, result.size());
        assertEquals("ab",  result.get(0).get("match").asText());
        assertEquals(0,     result.get(0).get("index").asInt());
        assertEquals("b",   result.get(0).get("groups").get(0).asText());
        assertEquals("abb", result.get(1).get("match").asText());
        assertEquals("bb",  result.get(1).get("groups").get(0).asText());
    }

    @Test
    void match_withLimit() throws Exception {
        assertJsonEqual("""
                [{"match":"1","index":5,"groups":[]}]
                """,
                eval("$match(\"test 1 test 2\", /[0-9]+/, 1)"));
    }

    @Test
    void match_noMatchReturnsMissing() throws Exception {
        // $match returns MISSING (undefined) when no match — evaluate returns NULL at boundary
        JsonNode result = FACTORY.compile("$match(\"hello\", /[0-9]/)").evaluate(EMPTY_OBJECT);
        // MISSING is converted to NULL at evaluate() boundary
        assertTrue(result.isMissingNode());
    }

    // =========================================================================
    // $replace
    // =========================================================================

    @Test
    void replace_stringReplacement() throws Exception {
        assertJsonEqual("\"hello there\"",
                eval("$replace(\"hello world\", /world/, \"there\")"));
    }

    @Test
    void replace_withCaptureGroupRef() throws Exception {
        assertJsonEqual("\"Smith, John\"",
                eval("$replace(\"John Smith\", /(\\w+)\\s(\\w+)/, \"$2, $1\")"));
    }

    @Test
    void replace_dollarSignEscape() throws Exception {
        assertJsonEqual("\"$265\"",
                eval("$replace(\"265USD\", /([0-9]+)USD/, \"$$$1\")"));
    }

    @Test
    void replace_allOccurrences() throws Exception {
        assertJsonEqual("\"hello! world!\"",
                eval("$replace(\"hello world\", /(\\w+)/, \"$1!\")"));
    }

    @Test
    void replace_withLimit() throws Exception {
        assertJsonEqual("\"HELLO world\"",
                eval("$replace(\"hello world\", /\\w+/, function($m) { $uppercase($m.match) }, 1)"));
    }

    @Test
    void replace_withLambda() throws Exception {
        assertJsonEqual("\"HELLO WORLD\"",
                eval("$replace(\"hello world\", /\\w+/, function($m) { $uppercase($m.match) })"));
    }

    @Test
    void replace_stringPatternLiteral() throws Exception {
        assertJsonEqual("\"hi world\"",
                eval("$replace(\"hello world\", \"hello\", \"hi\")"));
    }

    @Test
    void replace_stringPatternWithMetachars() throws Exception {
        // "." in string pattern must match only literal dot, not any char
        assertJsonEqual("\"aXb\"",
                eval("$replace(\"a.b\", \".\", \"X\")"));
        // This should NOT replace "acb" (literal dot only)
        assertJsonEqual("\"acb\"",
                eval("$replace(\"acb\", \".\", \"X\")"));
    }

    // =========================================================================
    // Regex in predicate / path
    // =========================================================================

    @Test
    void regexInPredicate() throws Exception {
        assertJsonEqual("[\"hat\",\"baseball hat\"]",
                eval("""
                    (
                      $items := ["hat", "baseball hat", "gloves", "scarf"];
                      $items[$contains($, /hat/)]
                    )
                    """));
    }
}
