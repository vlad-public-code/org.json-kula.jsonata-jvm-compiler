package org.json_kula.jsonata_jvm.runtime.string;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RegexOps}: {@link RegexOps#bytePosToCharPos} and
 * {@link RegexOps#expandReplacement}.
 */
class RegexOpsTest {

    // =========================================================================
    // bytePosToCharPos
    // =========================================================================

    @Test void bytePos_ascii_identity() {
        String s = "hello";
        // Each ASCII char is 1 byte, so byte pos == char pos
        for (int i = 0; i <= 5; i++) assertEquals(i, RegexOps.bytePosToCharPos(s, i));
    }

    @Test void bytePos_two_byte_char() {
        // "é" = U+00E9 → UTF-8 bytes: C3 A9 (2 bytes)
        String s = "aéb";
        // byte 0 → char 0 ('a'), byte 1 → char 1 ('é'), byte 3 → char 2 ('b')
        assertEquals(0, RegexOps.bytePosToCharPos(s, 0));
        assertEquals(1, RegexOps.bytePosToCharPos(s, 1));
        assertEquals(2, RegexOps.bytePosToCharPos(s, 3));
    }

    @Test void bytePos_four_byte_emoji() {
        // "😀" = U+1F600 → UTF-8: F0 9F 98 80 (4 bytes), stored as 2 Java chars (surrogate pair)
        String s = "a😀b";
        assertEquals(0, RegexOps.bytePosToCharPos(s, 0));  // 'a'
        assertEquals(1, RegexOps.bytePosToCharPos(s, 1));  // start of emoji
        assertEquals(3, RegexOps.bytePosToCharPos(s, 5));  // 'b' — after emoji (4 bytes) + 'a' (1 byte)
    }

    @Test void bytePos_three_byte_char() {
        // "中" = U+4E2D → UTF-8: E4 B8 AD (3 bytes)
        String s = "a中b";
        assertEquals(0, RegexOps.bytePosToCharPos(s, 0));
        assertEquals(1, RegexOps.bytePosToCharPos(s, 1));
        assertEquals(2, RegexOps.bytePosToCharPos(s, 4));
    }

    @Test void bytePos_zero_always_zero() {
        assertEquals(0, RegexOps.bytePosToCharPos("", 0));
        assertEquals(0, RegexOps.bytePosToCharPos("hello", 0));
    }

    // =========================================================================
    // expandReplacement
    // =========================================================================

    private static String expand(String repl, String whole, String input, org.joni.Region region) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return RegexOps.expandReplacement(repl, whole, bytes, region);
    }

    @Test void expand_no_groups_literal() {
        assertEquals("XYZ", expand("XYZ", "match", "anything", null));
    }

    @Test void expand_dollar_zero_whole_match() {
        assertEquals("[match]", expand("[$0]", "match", "anything", null));
    }

    @Test void expand_dollar_dollar_literal() {
        assertEquals("$", expand("$$", "match", "anything", null));
    }

    @Test void expand_dollar_dollar_in_context() {
        assertEquals("a$b", expand("a$$b", "match", "anything", null));
    }

    @Test void expand_no_region_group_ref_produces_empty() {
        // $1 with null region — no capture group, should produce empty + remaining literal
        String result = expand("$1", "match", "hello", null);
        assertEquals("", result);
    }

    @Test void expand_trailing_literal_after_dollar_zero() {
        // "$0world" — $0 = whole match, then literal "world"
        assertEquals("matchworld", expand("$0world", "match", "anything", null));
    }

    @Test void expand_plain_dollar_at_end() {
        // "$" with no digit following — emitted literally (no group reference)
        String result = expand("hello$", "match", "anything", null);
        assertEquals("hello$", result);
    }
}
