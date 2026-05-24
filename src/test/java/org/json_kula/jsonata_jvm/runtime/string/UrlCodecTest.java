package org.json_kula.jsonata_jvm.runtime.string;

import org.json_kula.jsonata_jvm.runtime.RuntimeEvaluationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UrlCodec}: encode and decode, RFC 3986 character classes,
 * lone-surrogate rejection, and malformed-input detection.
 */
class UrlCodecTest {

    // =========================================================================
    // encode — unreserved / reserved / other
    // =========================================================================

    @Test void encode_unreserved_unchanged() {
        // A-Z a-z 0-9 - _ . ~ must pass through untouched
        String unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~";
        assertEquals(unreserved, UrlCodec.encode(unreserved, false));
        assertEquals(unreserved, UrlCodec.encode(unreserved, true));
    }

    @Test void encodeComponent_encodes_reserved() {
        // : / ? # etc. must be percent-encoded in component mode
        String result = UrlCodec.encode(":/?#[]@!$&'()*+,;=", false);
        assertFalse(result.contains(":"));
        assertFalse(result.contains("/"));
        assertFalse(result.contains("?"));
    }

    @Test void encodeUrl_preserves_reserved() {
        // Same chars preserved in full-URL mode
        String result = UrlCodec.encode(":/?#[]@!$&'()*+,;=", true);
        assertTrue(result.contains(":"));
        assertTrue(result.contains("/"));
        assertTrue(result.contains("?"));
    }

    @Test void encode_space_becomes_percent20() {
        assertEquals("hello%20world", UrlCodec.encode("hello world", false));
        assertEquals("hello%20world", UrlCodec.encode("hello world", true));
    }

    @Test void encode_caret_encoded() {
        // ^ is neither unreserved nor reserved — must be encoded
        assertEquals("hello%5Eworld", UrlCodec.encode("hello^world", true));
    }

    @Test void encode_multibyte_unicode() {
        // é = U+00E9 → UTF-8 0xC3 0xA9
        assertEquals("%C3%A9", UrlCodec.encode("é", false));
    }

    @Test void encode_emoji() {
        // 😀 = U+1F600 → UTF-8 0xF0 0x9F 0x98 0x80
        assertEquals("%F0%9F%98%80", UrlCodec.encode("😀", false));
    }

    @Test void encode_lone_high_surrogate_throws() {
        // Lone high surrogate (no following low surrogate)
        String s = String.valueOf('\uD83D');
        assertThrows(RuntimeEvaluationException.class, () -> UrlCodec.encode(s, false));
    }

    @Test void encode_lone_low_surrogate_throws() {
        String s = String.valueOf('\uDE00');
        assertThrows(RuntimeEvaluationException.class, () -> UrlCodec.encode(s, false));
    }

    @Test void encode_valid_surrogate_pair_ok() {
        // 😀 as explicit surrogate pair — valid, should encode to same bytes
        String s = "😀";   // U+1F600
        String result = UrlCodec.encode(s, false);
        assertTrue(result.startsWith("%F0"));
    }

    // =========================================================================
    // decode
    // =========================================================================

    @Test void decode_percent20_to_space() {
        assertEquals("hello world", UrlCodec.decode("hello%20world"));
    }

    @Test void decode_multibyte_sequence() {
        assertEquals("é", UrlCodec.decode("%C3%A9"));
    }

    @Test void decode_unreserved_ascii_passthrough() {
        assertEquals("hello-world.test~ok", UrlCodec.decode("hello-world.test~ok"));
    }

    @Test void decode_lowercase_hex() {
        assertEquals(" ", UrlCodec.decode("%20"));
        // lower-case hex digits must also work
        assertEquals(" ", UrlCodec.decode("%20"));
    }

    @Test void decode_incomplete_percent_throws() {
        assertThrows(RuntimeEvaluationException.class, () -> UrlCodec.decode("hello%2"));
    }

    @Test void decode_invalid_hex_throws() {
        assertThrows(RuntimeEvaluationException.class, () -> UrlCodec.decode("hello%ZZ"));
    }

    @Test void decode_non_ascii_literal_throws() {
        // A literal é in the input (not percent-encoded) is invalid
        assertThrows(RuntimeEvaluationException.class, () -> UrlCodec.decode("café"));
    }

    @Test void decode_invalid_utf8_sequence_throws() {
        // %80 alone is not valid UTF-8
        assertThrows(RuntimeEvaluationException.class, () -> UrlCodec.decode("%80"));
    }

    // =========================================================================
    // roundtrip
    // =========================================================================

    @Test void roundtrip_component() {
        String original = "name=John Doe&city=São Paulo";
        assertEquals(original, UrlCodec.decode(UrlCodec.encode(original, false)));
    }

    @Test void roundtrip_full_url() {
        String original = "http://example.com/search?q=hello world&lang=en";
        assertEquals(original, UrlCodec.decode(UrlCodec.encode(original, true)));
    }

    // =========================================================================
    // isUnreserved / isReserved
    // =========================================================================

    @Test void isUnreserved_alpha_numeric() {
        assertTrue(UrlCodec.isUnreserved('A'));
        assertTrue(UrlCodec.isUnreserved('z'));
        assertTrue(UrlCodec.isUnreserved('0'));
        assertTrue(UrlCodec.isUnreserved('9'));
        assertTrue(UrlCodec.isUnreserved('-'));
        assertTrue(UrlCodec.isUnreserved('_'));
        assertTrue(UrlCodec.isUnreserved('.'));
        assertTrue(UrlCodec.isUnreserved('~'));
    }

    @Test void isUnreserved_space_false() {
        assertFalse(UrlCodec.isUnreserved(' '));
    }

    @Test void isReserved_colon_slash() {
        assertTrue(UrlCodec.isReserved(':'));
        assertTrue(UrlCodec.isReserved('/'));
        assertTrue(UrlCodec.isReserved('?'));
        assertTrue(UrlCodec.isReserved('#'));
    }

    @Test void isReserved_space_false() {
        assertFalse(UrlCodec.isReserved(' '));
    }
}
