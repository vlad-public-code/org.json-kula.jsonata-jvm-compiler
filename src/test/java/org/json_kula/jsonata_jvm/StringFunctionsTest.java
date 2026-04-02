package org.json_kula.jsonata_jvm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSONata string functions:
 *   $string, $length, $substring, $substringBefore, $substringAfter,
 *   $uppercase, $lowercase, $trim, $contains, $split, $join,
 *   $match, $replace, $pad, $eval,
 *   $base64encode, $base64decode,
 *   $encodeUrlComponent, $decodeUrlComponent, $encodeUrl, $decodeUrl
 *
 * Spec: https://docs.jsonata.org/string-functions
 */
class StringFunctionsTest {

    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();
    private static final String EMPTY = "{}";

    private JsonNode eval(String expr, String json) throws Exception {
        return FACTORY.compile(expr).evaluate(json);
    }

    private JsonNode eval(String expr) throws Exception {
        return eval(expr, EMPTY);
    }

    // =========================================================================
    // $string
    // =========================================================================

    @Test
    void string_from_number() throws Exception {
        assertEquals("5", eval("$string(5)").textValue());
    }

    @Test
    void string_from_boolean() throws Exception {
        assertEquals("true", eval("$string(true)").textValue());
    }

    @Test
    void string_from_array() throws Exception {
        assertEquals("[1,2,3]", eval("$string([1,2,3])").textValue());
    }

    @Test
    void string_from_null() throws Exception {
        assertEquals("null", eval("$string(null)").textValue());
    }

    @Test
    void string_missing_returns_null() throws Exception {
        assertTrue(eval("$string($notDefined)").isNull());
    }

    @Test
    void string_prettify_object() throws Exception {
        String result = eval("$string({\"a\": 1}, true)").textValue();
        assertNotNull(result);
        assertTrue(result.contains("\n"), "Prettified JSON should contain newlines");
        assertTrue(result.contains("\"a\"") && result.contains("1"));
    }

    @Test
    void string_prettify_false_is_compact() throws Exception {
        String result = eval("$string({\"a\": 1}, false)").textValue();
        assertNotNull(result);
        assertFalse(result.contains("\n"));
    }

    @Test
    void string_prettify_on_string_returns_string_unchanged() throws Exception {
        assertEquals("hello", eval("$string(\"hello\", true)").textValue());
    }

    // =========================================================================
    // $length
    // =========================================================================

    @Test
    void length_basic() throws Exception {
        assertEquals(5L, eval("$length(\"hello\")").longValue());
    }

    @Test
    void length_empty() throws Exception {
        assertEquals(0L, eval("$length(\"\")").longValue());
    }

    // =========================================================================
    // $substring
    // =========================================================================

    @Test
    void substring_from_index() throws Exception {
        assertEquals("orld", eval("$substring(\"hello world\", 7)").textValue());
    }

    @Test
    void substring_with_length() throws Exception {
        assertEquals("ello", eval("$substring(\"hello world\", 1, 4)").textValue());
    }

    @Test
    void substring_negative_index() throws Exception {
        assertEquals("orld", eval("$substring(\"hello world\", -4)").textValue());
    }

    // =========================================================================
    // $substringBefore / $substringAfter
    // =========================================================================

    @Test
    void substringBefore_found() throws Exception {
        assertEquals("hel", eval("$substringBefore(\"hello\", \"lo\")").textValue());
    }

    @Test
    void substringBefore_not_found() throws Exception {
        assertEquals("hello", eval("$substringBefore(\"hello\", \"xyz\")").textValue());
    }

    @Test
    void substringAfter_found() throws Exception {
        assertEquals("lo", eval("$substringAfter(\"hello\", \"hel\")").textValue());
    }

    @Test
    void substringAfter_not_found() throws Exception {
        assertEquals("", eval("$substringAfter(\"hello\", \"xyz\")").textValue());
    }

    // =========================================================================
    // $uppercase / $lowercase
    // =========================================================================

    @Test
    void uppercase_basic() throws Exception {
        assertEquals("HELLO", eval("$uppercase(\"hello\")").textValue());
    }

    @Test
    void lowercase_basic() throws Exception {
        assertEquals("hello", eval("$lowercase(\"HELLO\")").textValue());
    }

    // =========================================================================
    // $trim
    // =========================================================================

    @Test
    void trim_leading_trailing() throws Exception {
        assertEquals("hello world", eval("$trim(\"  hello   world  \")").textValue());
    }

    @Test
    void trim_internal_whitespace_collapsed() throws Exception {
        assertEquals("hello world", eval("$trim(\"hello\\t\\nworld\")").textValue());
    }

    // =========================================================================
    // $contains
    // =========================================================================

    @Test
    void contains_found() throws Exception {
        assertTrue(eval("$contains(\"hello world\", \"world\")").booleanValue());
    }

    @Test
    void contains_not_found() throws Exception {
        assertFalse(eval("$contains(\"hello\", \"xyz\")").booleanValue());
    }

    @Test
    void contains_regex() throws Exception {
        assertTrue(eval("$contains(\"hello\", /hel+o/)").booleanValue());
        assertFalse(eval("$contains(\"world\", /hel+o/)").booleanValue());
    }

    // =========================================================================
    // $split
    // =========================================================================

    @Test
    void split_by_string() throws Exception {
        JsonNode result = eval("$split(\"a,b,c\", \",\")");
        assertTrue(result.isArray());
        assertEquals(3, result.size());
        assertEquals("a", result.get(0).textValue());
        assertEquals("b", result.get(1).textValue());
        assertEquals("c", result.get(2).textValue());
    }

    @Test
    void split_with_limit() throws Exception {
        JsonNode result = eval("$split(\"a,b,c\", \",\", 2)");
        assertTrue(result.isArray());
        assertEquals(2, result.size());
    }

    @Test
    void split_by_regex() throws Exception {
        JsonNode result = eval("$split(\"a1b2c\", /[0-9]/)");
        assertTrue(result.isArray());
        assertEquals(3, result.size());
    }

    // =========================================================================
    // $join
    // =========================================================================

    @Test
    void join_with_separator() throws Exception {
        assertEquals("a,b,c", eval("$join([\"a\",\"b\",\"c\"], \",\")").textValue());
    }

    @Test
    void join_no_separator() throws Exception {
        assertEquals("abc", eval("$join([\"a\",\"b\",\"c\"])").textValue());
    }

    // =========================================================================
    // $match
    // =========================================================================

    @Test
    void match_basic() throws Exception {
        JsonNode result = eval("$match(\"hello world\", /wor/)");
        assertTrue(result.isArray());
        assertEquals(1, result.size());
        assertEquals("wor", result.get(0).get("match").textValue());
    }

    @Test
    void match_no_result() throws Exception {
        assertTrue(eval("$match(\"hello\", /xyz/)").isNull());
    }

    @Test
    void match_with_groups() throws Exception {
        JsonNode result = eval("$match(\"2024-01-15\", /([0-9]+)-([0-9]+)-([0-9]+)/)");
        assertTrue(result.isArray());
        JsonNode first = result.get(0);
        assertEquals("2024-01-15", first.get("match").textValue());
        assertEquals(3, first.get("groups").size());
        assertEquals("2024", first.get("groups").get(0).textValue());
    }

    // =========================================================================
    // $replace
    // =========================================================================

    @Test
    void replace_literal_all() throws Exception {
        assertEquals("aXbXc", eval("$replace(\"a,b,c\", \",\", \"X\")").textValue());
    }

    @Test
    void replace_regex() throws Exception {
        assertEquals("hello-world", eval("$replace(\"hello world\", / /, \"-\")").textValue());
    }

    @Test
    void replace_with_limit() throws Exception {
        assertEquals("aXb,c", eval("$replace(\"a,b,c\", \",\", \"X\", 1)").textValue());
    }

    // =========================================================================
    // $pad
    // =========================================================================

    @Test
    void pad_right_with_spaces() throws Exception {
        assertEquals("hello     ", eval("$pad(\"hello\", 10)").textValue());
    }

    @Test
    void pad_left_with_spaces() throws Exception {
        assertEquals("     hello", eval("$pad(\"hello\", -10)").textValue());
    }

    @Test
    void pad_right_with_custom_char() throws Exception {
        assertEquals("hello-----", eval("$pad(\"hello\", 10, \"-\")").textValue());
    }

    @Test
    void pad_left_with_custom_char() throws Exception {
        assertEquals("-----hello", eval("$pad(\"hello\", -10, \"-\")").textValue());
    }

    @Test
    void pad_no_change_when_already_wide() throws Exception {
        assertEquals("hello world", eval("$pad(\"hello world\", 5)").textValue());
    }

    @Test
    void pad_exact_width_no_change() throws Exception {
        assertEquals("hello", eval("$pad(\"hello\", 5)").textValue());
    }

    // =========================================================================
    // $eval
    // =========================================================================

    @Test
    void eval_arithmetic() throws Exception {
        assertEquals(7L, eval("$eval(\"3 + 4\")").longValue());
    }

    @Test
    void eval_with_context() throws Exception {
        JsonNode result = eval("$eval(\"x + 1\", {\"x\": 10})");
        assertEquals(11L, result.longValue());
    }

    @Test
    void eval_json_object() throws Exception {
        JsonNode result = eval("$eval(\"{\\\"a\\\": 1}\")");
        assertTrue(result.isObject());
        assertEquals(1L, result.get("a").longValue());
    }

    @Test
    void eval_missing_returns_null() throws Exception {
        assertTrue(eval("$eval($notDefined)").isNull());
    }

    // =========================================================================
    // $base64encode / $base64decode
    // =========================================================================

    @Test
    void base64encode_basic() throws Exception {
        assertEquals("aGVsbG8=", eval("$base64encode(\"hello\")").textValue());
    }

    @Test
    void base64decode_basic() throws Exception {
        assertEquals("hello", eval("$base64decode(\"aGVsbG8=\")").textValue());
    }

    @Test
    void base64_roundtrip() throws Exception {
        String encoded = eval("$base64encode(\"Hello, World!\")").textValue();
        String decoded = eval("$base64decode(\"" + encoded + "\")").textValue();
        assertEquals("Hello, World!", decoded);
    }

    @Test
    void base64encode_missing_returns_null() throws Exception {
        assertTrue(eval("$base64encode($notDefined)").isNull());
    }

    // =========================================================================
    // $encodeUrlComponent / $decodeUrlComponent
    // =========================================================================

    @Test
    void encodeUrlComponent_spaces_and_special() throws Exception {
        // space → %20, & → %26, = → %3D
        String result = eval("$encodeUrlComponent(\"hello world&a=b\")").textValue();
        assertEquals("hello%20world%26a%3Db", result);
    }

    @Test
    void decodeUrlComponent_basic() throws Exception {
        assertEquals("hello world", eval("$decodeUrlComponent(\"hello%20world\")").textValue());
    }

    @Test
    void encodeUrlComponent_unreserved_unchanged() throws Exception {
        assertEquals("hello-world.test~ok", eval("$encodeUrlComponent(\"hello-world.test~ok\")").textValue());
    }

    @Test
    void encodeUrlComponent_roundtrip() throws Exception {
        String original = "name=John Doe&city=New York";
        String encoded = eval("$encodeUrlComponent(\"" + original + "\")").textValue();
        String decoded = eval("$decodeUrlComponent(\"" + encoded + "\")").textValue();
        assertEquals(original, decoded);
    }

    // =========================================================================
    // $encodeUrl / $decodeUrl
    // =========================================================================

    @Test
    void encodeUrl_preserves_structure_chars() throws Exception {
        // : / ? # are preserved; space is encoded
        String result = eval("$encodeUrl(\"http://example.com/path?q=hello world\")").textValue();
        assertEquals("http://example.com/path?q=hello%20world", result);
    }

    @Test
    void decodeUrl_basic() throws Exception {
        assertEquals("http://example.com/path?q=hello world",
                eval("$decodeUrl(\"http://example.com/path?q=hello%20world\")").textValue());
    }

    @Test
    void encodeUrl_unreserved_and_reserved_unchanged() throws Exception {
        // All reserved chars should be preserved
        String input = "http://user@host:8080/path?a=1&b=2#frag";
        assertEquals(input, eval("$encodeUrl(\"" + input + "\")").textValue());
    }

    @Test
    void encodeUrl_encodes_non_safe_chars() throws Exception {
        // ^ is not reserved or unreserved → must be encoded
        String result = eval("$encodeUrl(\"hello^world\")").textValue();
        assertEquals("hello%5Eworld", result);
    }

    @Test
    void encodeUrl_roundtrip() throws Exception {
        String original = "http://example.com/search?q=hello world&lang=en";
        String encoded = eval("$encodeUrl(\"" + original + "\")").textValue();
        String decoded = eval("$decodeUrl(\"" + encoded + "\")").textValue();
        assertEquals(original, decoded);
    }
}
